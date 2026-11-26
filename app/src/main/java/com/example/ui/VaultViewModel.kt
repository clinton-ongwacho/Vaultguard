package com.example.ui

import android.content.Intent
import android.content.IntentSender
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.AppGuardRuntimeCache
import com.example.data.InstalledAppInfo
import com.example.data.cloud.CloudSyncRepository
import com.example.data.db.AppRestrictionFrequency
import com.example.data.entity.AccessEventEntity
import com.example.data.entity.AdminConfigEntity
import com.example.data.entity.AppGuardMode
import com.example.data.entity.DisguiseIdentity
import com.example.data.entity.LimitedAppEntity
import com.example.data.entity.NotificationPrivacyMode
import com.example.data.entity.RedirectDecoyMode
import com.example.data.entity.RedirectDisplayStyle
import com.example.data.entity.SecurityQuestionEntity
import com.example.data.entity.VaultMediaEntity
import com.example.data.repository.VaultRepository
import com.example.ota.OtaUiState
import com.example.ota.OtaUpdateManager
import com.example.restriction.EnforcementVerificationState
import com.example.restriction.PackageInspectionReport
import com.example.restriction.SystemIntegrityStatus
import com.example.restriction.SystemRestrictionStep
import com.example.restriction.UniversalChannelTracker
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppRuleRow(
    val appInfo: InstalledAppInfo,
    val rule: LimitedAppEntity?,
    val inspectionReport: PackageInspectionReport? = null,
    val restrictionFrequency: Int = 0,
    val lastRestrictedAtMillis: Long = 0L,
) {
    val isLimited: Boolean get() = rule?.isLimited == true
    val guardMode: AppGuardMode get() = rule?.guardModeEnum ?: AppGuardMode.NONE
    val notificationPrivacyMode: NotificationPrivacyMode
        get() = rule?.notificationPrivacyEnum ?: NotificationPrivacyMode.SILENT_AND_HIDE
}

data class VaultUiState(
    val currentUserId: String = "",
    val userDisplayName: String = "",
    val userEmail: String = "",
    val cloudSyncError: String? = null,
    val isConfigLoaded: Boolean = false,
    val config: AdminConfigEntity = AdminConfigEntity(),
    val isSessionUnlocked: Boolean = false,
    val stealthWakeActive: Boolean = false,
    val wakeSource: String? = null,
    val authError: String? = null,
    val failedUnlockAttempts: Int = 0,
    val isVaultMediaUnlocked: Boolean = false,
    val vaultUnlockError: String? = null,
    val vaultMediaItems: List<VaultMediaEntity> = emptyList(),
    val isImportingMedia: Boolean = false,
    val pendingGalleryDeleteSender: IntentSender? = null,
    val securityQuestions: List<SecurityQuestionEntity> = emptyList(),
    val statusBannerMessage: String? = null,
    val apps: List<AppRuleRow> = emptyList(),
    val inspectionReports: Map<String, PackageInspectionReport> = emptyMap(),
    val isLoadingApps: Boolean = true,
    val appSearchQuery: String = "",
    val showLimitedOnly: Boolean = false,
    val showSystemApps: Boolean = false,
    val recentEvents: List<AccessEventEntity> = emptyList(),
    val systemIntegrity: SystemIntegrityStatus = SystemIntegrityStatus(),
    val isAccessibilityEnabled: Boolean = false,
    val isDeviceAdminEnabled: Boolean = false,
    val isNotificationListenerEnabled: Boolean = false,
    val isCameraPermissionGranted: Boolean = false,
    val isPostNotificationsGranted: Boolean = true,
    val pendingEndRestrictionPackage: String? = null,
    val suggestedFrequentApps: List<AppRuleRow> = emptyList(),
    val hasFrequentRestrictionHistory: Boolean = false,
    val otaState: OtaUiState = OtaUiState(),
) {
    val limitedCount: Int get() = apps.count { it.isLimited }
    val pendingSystemGapCount: Int
        get() = apps.count {
            it.isLimited && it.inspectionReport?.verificationState == EnforcementVerificationState.ACTIVE_WITH_SYSTEM_GAPS
        }
    val activeDisguise: DisguiseIdentity get() = DisguiseIdentity.fromName(config.disguiseName)
    val activeRedirectDisplay: RedirectDisplayStyle get() = RedirectDisplayStyle.fromName(config.redirectDisplayName)
    val activeRedirectDecoyMode: RedirectDecoyMode get() = RedirectDecoyMode.fromName(config.redirectDecoyMode)
    val filteredApps: List<AppRuleRow>
        get() = apps.filter { row ->
            val matchesSystem = showSystemApps || !row.appInfo.isSystemApp || row.isLimited
            val matchesLimited = !showLimitedOnly || row.isLimited
            val matchesQuery = appSearchQuery.isBlank() ||
                row.appInfo.appName.contains(appSearchQuery, ignoreCase = true) ||
                row.appInfo.packageName.contains(appSearchQuery, ignoreCase = true)
            matchesSystem && matchesLimited && matchesQuery
        }
}

private data class LocalControlState(
    val isSessionUnlocked: Boolean = false,
    val stealthWakeActive: Boolean = false,
    val wakeSource: String? = null,
    val authError: String? = null,
    val failedUnlockAttempts: Int = 0,
    val isVaultMediaUnlocked: Boolean = false,
    val vaultUnlockError: String? = null,
    val isImportingMedia: Boolean = false,
    val pendingGalleryDeleteSender: IntentSender? = null,
    val pendingGalleryDeleteIds: List<String> = emptyList(),
    val statusBannerMessage: String? = null,
    val cloudSyncError: String? = null,
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val inspectionReports: Map<String, PackageInspectionReport> = emptyMap(),
    val pinnedTopPackages: Set<String>? = null,
    val isLoadingApps: Boolean = true,
    val appSearchQuery: String = "",
    val showLimitedOnly: Boolean = false,
    val showSystemApps: Boolean = false,
    val isAccessibilityEnabled: Boolean = false,
    val isAccessibilityBound: Boolean = false,
    val isDeviceAdminEnabled: Boolean = false,
    val isNotificationListenerEnabled: Boolean = false,
    val isNotificationListenerBound: Boolean = false,
    val isCameraPermissionGranted: Boolean = false,
    val isPostNotificationsGranted: Boolean = true,
    val isDeviceOwnerActive: Boolean = false,
    val pendingEndRestrictionPackage: String? = null,
)

private data class CoreDbSnapshot(
    val config: AdminConfigEntity,
    val limitedRules: List<LimitedAppEntity>,
    val events: List<AccessEventEntity>,
    val vaultMedia: List<VaultMediaEntity>,
    val securityQuestions: List<SecurityQuestionEntity>,
    val frequencies: List<AppRestrictionFrequency>,
)

class VaultViewModel(
    private val repository: VaultRepository,
    private val cloudSyncRepository: CloudSyncRepository,
    private val otaUpdateManager: OtaUpdateManager,
    private val currentUserId: String,
    private val userDisplayName: String,
    private val userEmail: String,
) : ViewModel() {

    private val localState = MutableStateFlow(LocalControlState())

    @Volatile
    private var latestLimitedPackages: Set<String> = emptySet()

    private val dbSnapshotFlow = combine(
        combine(
            repository.configFlow,
            repository.limitedAppsFlow,
            repository.recentEventsFlow,
        ) { config, limitedRules, events -> Triple(config, limitedRules, events) },
        combine(
            repository.vaultMediaFlow,
            repository.securityQuestionsFlow,
            repository.appRestrictionFrequenciesFlow,
        ) { vaultMedia, securityQuestions, frequencies -> Triple(vaultMedia, securityQuestions, frequencies) },
    ) { (config, limitedRules, events), (vaultMedia, securityQuestions, frequencies) ->
        CoreDbSnapshot(
            config = config,
            limitedRules = limitedRules,
            events = events,
            vaultMedia = vaultMedia,
            securityQuestions = securityQuestions,
            frequencies = frequencies,
        )
    }

    val uiState: StateFlow<VaultUiState> = combine(
        dbSnapshotFlow,
        localState,
        otaUpdateManager.state,
    ) { snapshot, local, ota ->
        val limitedRules = snapshot.limitedRules
        latestLimitedPackages = limitedRules.filter { it.isLimited }.map { it.packageName }.toSet()
        val rulesMap = limitedRules.associateBy { it.packageName }
        val freqMap = snapshot.frequencies.associateBy { it.packageName }
        val pinned = local.pinnedTopPackages
        val rows = local.installedApps.map { info ->
            val rule = rulesMap[info.packageName]
            val freqStat = freqMap[info.packageName]
            val combinedFreq = (freqStat?.eventCount ?: 0) +
                (rule?.blockCount ?: 0) +
                (if (rule?.isLimited == true) 1 else 0)
            val lastRestrictedAt = maxOf(
                freqStat?.lastEventAtMillis ?: 0L,
                rule?.lastBlockedAtMillis ?: 0L,
            )
            AppRuleRow(
                appInfo = info,
                rule = rule,
                inspectionReport = local.inspectionReports[info.packageName],
                restrictionFrequency = combinedFreq,
                lastRestrictedAtMillis = lastRestrictedAt,
            )
        }.sortedWith(
            compareByDescending<AppRuleRow> { row ->
                if (pinned != null) {
                    row.appInfo.packageName in pinned
                } else {
                    row.isLimited
                }
            }
                .thenBy { it.appInfo.isSystemApp }
                .thenBy { it.appInfo.appName.lowercase() }
        )

        val frequentCandidates = rows.filter { it.restrictionFrequency > 0 }
        val hasFrequentHistory = frequentCandidates.isNotEmpty()
        val suggestedFrequent = if (hasFrequentHistory) {
            frequentCandidates.sortedWith(
                compareByDescending<AppRuleRow> { it.restrictionFrequency }
                    .thenBy { it.isLimited }
                    .thenByDescending { it.lastRestrictedAtMillis }
                    .thenBy { it.appInfo.appName.lowercase() }
            ).take(8)
        } else {
            rows.filter { !it.appInfo.isSystemApp }
                .sortedWith(
                    compareByDescending<AppRuleRow> { categoryPriority(it.appInfo.category) }
                        .thenBy { it.appInfo.appName.lowercase() }
                )
                .take(6)
        }

        val requiresMaskedNotifications = limitedRules.any {
            it.isLimited && it.notificationPrivacyEnum == NotificationPrivacyMode.SHOW_HIDE_SENSITIVE
        }
        val integrity = SystemIntegrityStatus(
            isAccessibilityEnabled = local.isAccessibilityEnabled,
            isAccessibilityServiceBound = local.isAccessibilityBound,
            isNotificationListenerEnabled = local.isNotificationListenerEnabled,
            isNotificationListenerBound = local.isNotificationListenerBound,
            isDeviceAdminEnabled = local.isDeviceAdminEnabled,
            isCameraPermissionGranted = local.isCameraPermissionGranted,
            isPostNotificationsGranted = local.isPostNotificationsGranted,
            isDeviceOwnerActive = local.isDeviceOwnerActive,
            guardActive = snapshot.config.guardActive,
            notificationBlockEnabled = snapshot.config.notificationBlockEnabled,
            uninstallProtectionEnabled = snapshot.config.uninstallProtectionEnabled,
            capturePhotoOnOpenEnabled = snapshot.config.capturePhotoOnOpenEnabled,
            requiresPostNotificationsForMaskedAlerts = requiresMaskedNotifications,
        )

        VaultUiState(
            currentUserId = currentUserId,
            userDisplayName = userDisplayName,
            userEmail = userEmail,
            cloudSyncError = local.cloudSyncError,
            isConfigLoaded = true,
            config = snapshot.config,
            isSessionUnlocked = local.isSessionUnlocked,
            stealthWakeActive = local.stealthWakeActive,
            wakeSource = local.wakeSource,
            authError = local.authError,
            failedUnlockAttempts = local.failedUnlockAttempts,
            isVaultMediaUnlocked = local.isVaultMediaUnlocked,
            vaultUnlockError = local.vaultUnlockError,
            vaultMediaItems = snapshot.vaultMedia,
            isImportingMedia = local.isImportingMedia,
            pendingGalleryDeleteSender = local.pendingGalleryDeleteSender,
            securityQuestions = snapshot.securityQuestions,
            statusBannerMessage = local.statusBannerMessage,
            apps = rows,
            inspectionReports = local.inspectionReports,
            isLoadingApps = local.isLoadingApps,
            appSearchQuery = local.appSearchQuery,
            showLimitedOnly = local.showLimitedOnly,
            showSystemApps = local.showSystemApps,
            recentEvents = snapshot.events,
            systemIntegrity = integrity,
            isAccessibilityEnabled = local.isAccessibilityEnabled,
            isDeviceAdminEnabled = local.isDeviceAdminEnabled,
            isNotificationListenerEnabled = local.isNotificationListenerEnabled,
            isCameraPermissionGranted = local.isCameraPermissionGranted,
            isPostNotificationsGranted = local.isPostNotificationsGranted,
            pendingEndRestrictionPackage = local.pendingEndRestrictionPackage,
            suggestedFrequentApps = suggestedFrequent,
            hasFrequentRestrictionHistory = hasFrequentHistory,
            otaState = ota,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = VaultUiState(
            currentUserId = currentUserId,
            userDisplayName = userDisplayName,
            userEmail = userEmail,
            isConfigLoaded = AppGuardRuntimeCache.config.isInitialized,
            config = if (AppGuardRuntimeCache.config.isInitialized) {
                AppGuardRuntimeCache.config
            } else {
                AdminConfigEntity()
            },
            otaState = otaUpdateManager.state.value,
        ),
    )

    init {
        refreshSystemStatus()
        refreshInstalledApps()
        observeRuntimeTelemetry()
        observeSystemPermissionSettings()
        observeForegroundPermissionChanges()
        if (currentUserId.isNotBlank()) {
            syncSignedInAccount()
            observeCloudRules()
            observeAppReleases()
            otaUpdateManager.checkForUpdates()
        }
    }

    private fun categoryPriority(category: Int): Int {
        return when (category) {
            ApplicationInfo.CATEGORY_SOCIAL -> 5
            ApplicationInfo.CATEGORY_VIDEO -> 4
            ApplicationInfo.CATEGORY_GAME -> 3
            ApplicationInfo.CATEGORY_AUDIO -> 2
            ApplicationInfo.CATEGORY_NEWS -> 1
            else -> 0
        }
    }

    private fun observeRuntimeTelemetry() {
        viewModelScope.launch {
            combine(
                AppGuardRuntimeCache.accessibilityConnected,
                AppGuardRuntimeCache.notificationListenerConnected,
                UniversalChannelTracker.channelVersion,
                AppGuardRuntimeCache.restrictionStateVersion,
            ) { a11y, nls, chVer, rsVer ->
                "$a11y:$nls:$chVer:$rsVer"
            }.collectLatest {
                refreshSystemStatus()
            }
        }
    }

    private fun observeSystemPermissionSettings() {
        viewModelScope.launch {
            repository.observeSystemPermissionSettings().collectLatest {
                refreshSystemStatus()
            }
        }
    }

    private fun observeForegroundPermissionChanges() {
        viewModelScope.launch {
            AppGuardRuntimeCache.foregroundActive.collectLatest { active ->
                if (!active) return@collectLatest
                while (true) {
                    delay(1_200L)
                    val cur = localState.value
                    val coreChanged =
                        repository.isAccessibilityServiceEnabled() != cur.isAccessibilityEnabled ||
                            repository.isDeviceAdminActive() != cur.isDeviceAdminEnabled ||
                            repository.isNotificationListenerEnabled() != cur.isNotificationListenerEnabled ||
                            repository.isCameraPermissionGranted() != cur.isCameraPermissionGranted ||
                            repository.isPostNotificationsGranted() != cur.isPostNotificationsGranted ||
                            repository.isDeviceOwnerActive() != cur.isDeviceOwnerActive
                    if (coreChanged) {
                        refreshSystemStatus()
                    } else {
                        val inspected = activeInspectedConfig
                        val targetPkg = inspected?.first ?: cur.pendingEndRestrictionPackage
                        if (targetPkg != null) {
                            val matched = cur.installedApps.firstOrNull { it.packageName == targetPkg }
                            if (matched != null) {
                                val liveReport = repository.inspectSingleAppRestriction(
                                    packageName = matched.packageName,
                                    appName = matched.appName,
                                    isRestricted = inspected?.second ?: false,
                                    silentAndHide = inspected?.third ?: true,
                                )
                                if (liveReport != cur.inspectionReports[targetPkg]) {
                                    reverifyRestrictedApps()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun syncSignedInAccount() {
        viewModelScope.launch {
            val result = cloudSyncRepository.upsertSignedInUserProfile()
            result.onFailure { err ->
                localState.update {
                    it.copy(cloudSyncError = err.localizedMessage ?: "Failed to sync account profile.")
                }
            }
        }
    }

    private fun observeCloudRules() {
        viewModelScope.launch {
            cloudSyncRepository.observeCloudLimitedApps(currentUserId)
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val rootError = firestoreError ?: error
                    Log.w("VaultVM", "Error observing cloud rules", rootError)
                    localState.update {
                        it.copy(cloudSyncError = rootError.message ?: "Cloud rule sync unavailable")
                    }
                }
                .collect {
                    localState.update { state -> state.copy(cloudSyncError = null) }
                }
        }
    }

    private fun observeAppReleases() {
        viewModelScope.launch {
            cloudSyncRepository.observeLatestAppRelease()
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    Log.w("VaultVM", "Error observing app releases", firestoreError ?: error)
                }
                .collect { release ->
                    otaUpdateManager.onLatestReleaseObserved(release)
                }
        }
    }

    fun refreshSystemStatus() {
        otaUpdateManager.refreshInstallCapability()
        localState.update {
            it.copy(
                isAccessibilityEnabled = repository.isAccessibilityServiceEnabled(),
                isAccessibilityBound = AppGuardRuntimeCache.accessibilityConnected.value,
                isDeviceAdminEnabled = repository.isDeviceAdminActive(),
                isNotificationListenerEnabled = repository.isNotificationListenerEnabled(),
                isNotificationListenerBound = AppGuardRuntimeCache.notificationListenerConnected.value,
                isCameraPermissionGranted = repository.isCameraPermissionGranted(),
                isPostNotificationsGranted = repository.isPostNotificationsGranted(),
                isDeviceOwnerActive = repository.isDeviceOwnerActive(),
            )
        }
        reverifyRestrictedApps()
    }

    @Volatile
    private var activeInspectedConfig: Triple<String, Boolean, Boolean>? = null

    private var reverifyJob: Job? = null

    fun reverifyRestrictedApps() {
        val currentApps = localState.value.installedApps
        if (currentApps.isEmpty()) return
        val initialOverride = activeInspectedConfig
        val pendingEndPkg = localState.value.pendingEndRestrictionPackage
        reverifyJob?.cancel()
        reverifyJob = viewModelScope.launch {
            if (initialOverride != null) {
                val (pkg, isRestrictedPreview, silentAndHide) = initialOverride
                val matched = currentApps.firstOrNull { it.packageName == pkg }
                if (matched != null) {
                    val quickReport = repository.inspectSingleAppRestriction(
                        packageName = matched.packageName,
                        appName = matched.appName,
                        isRestricted = isRestrictedPreview,
                        silentAndHide = silentAndHide,
                    )
                    localState.update { current ->
                        current.copy(inspectionReports = current.inspectionReports + (pkg to quickReport))
                    }
                }
            }
            val reports = repository.inspectAllAppRestrictions(currentApps).toMutableMap()
            val latestOverride = activeInspectedConfig ?: initialOverride
            if (latestOverride != null) {
                val (pkg, isRestrictedPreview, silentAndHide) = latestOverride
                val matched = currentApps.firstOrNull { it.packageName == pkg }
                if (matched != null) {
                    reports[pkg] = repository.inspectSingleAppRestriction(
                        packageName = matched.packageName,
                        appName = matched.appName,
                        isRestricted = isRestrictedPreview,
                        silentAndHide = silentAndHide,
                    )
                }
            }
            if (pendingEndPkg != null) {
                val pendingReport = reports[pendingEndPkg]
                val matchedApp = currentApps.firstOrNull { it.packageName == pendingEndPkg }
                if (pendingReport != null && pendingReport.restoreSteps.isEmpty() && matchedApp != null) {
                    val finalizeResult = repository.setAppGuardRule(
                        packageName = matchedApp.packageName,
                        appName = matchedApp.appName,
                        guardMode = AppGuardMode.NONE,
                    )
                    finalizeResult.onSuccess { finalizedReport ->
                        reports[pendingEndPkg] = finalizedReport
                        if (!finalizedReport.isRestricted) {
                            activeInspectedConfig = null
                            AppGuardRuntimeCache.activeSetupPackage = null
                            localState.update {
                                it.copy(
                                    pendingEndRestrictionPackage = null,
                                    statusBannerMessage = "All settings restored. ${matchedApp.appName} restriction ended.",
                                )
                            }
                            if (currentUserId.isNotBlank()) {
                                cloudSyncRepository.syncLimitedApp(
                                    packageName = matchedApp.packageName,
                                    appName = matchedApp.appName,
                                    limited = false,
                                    blockCount = 0,
                                )
                            }
                        }
                    }
                }
            }
            localState.update { it.copy(inspectionReports = reports) }
        }
    }

    fun clearPendingEndRestriction() {
        AppGuardRuntimeCache.activeSetupPackage = null
        localState.update { it.copy(pendingEndRestrictionPackage = null) }
    }

    fun inspectAppForConfig(
        row: AppRuleRow,
        isRestrictedPreview: Boolean,
        silentAndHide: Boolean,
    ) {
        val pkg = row.appInfo.packageName
        AppGuardRuntimeCache.activeSetupPackage = pkg
        activeInspectedConfig = Triple(pkg, isRestrictedPreview, silentAndHide)
        viewModelScope.launch {
            val report = repository.inspectSingleAppRestriction(
                packageName = pkg,
                appName = row.appInfo.appName,
                isRestricted = isRestrictedPreview,
                silentAndHide = silentAndHide,
            )
            localState.update { current ->
                current.copy(inspectionReports = current.inspectionReports + (pkg to report))
            }
        }
    }

    fun clearInspectedAppForConfig() {
        activeInspectedConfig = null
        AppGuardRuntimeCache.activeSetupPackage = null
        reverifyRestrictedApps()
    }

    fun onAppsScreenOpened() {
        localState.update { it.copy(pinnedTopPackages = latestLimitedPackages) }
        refreshSystemStatus()
    }

    fun refreshInstalledApps() {
        viewModelScope.launch {
            localState.update { it.copy(isLoadingApps = true) }
            val apps = repository.loadInstalledApps()
            val reports = repository.inspectAllAppRestrictions(apps)
            localState.update {
                it.copy(
                    installedApps = apps,
                    inspectionReports = reports,
                    pinnedTopPackages = latestLimitedPackages,
                    isLoadingApps = false,
                )
            }
        }
    }

    fun buildSystemSettingsIntent(step: SystemRestrictionStep): Intent =
        repository.buildSystemSettingsIntent(step)

    fun buildSystemSettingsIntentChain(step: SystemRestrictionStep): List<Intent> =
        repository.buildSystemSettingsIntentChain(step)

    fun onGuidedSystemStepOpened(packageName: String, step: SystemRestrictionStep) {
        viewModelScope.launch {
            repository.recordGuidedSystemStepOpened(packageName, step)
            reverifyRestrictedApps()
        }
    }

    fun onStealthWakeReceived(source: String?) {
        localState.update {
            it.copy(
                stealthWakeActive = true,
                wakeSource = source,
                authError = null,
            )
        }
        viewModelScope.launch {
            repository.logEvent(
                packageName = "stealth",
                appName = source ?: "Stealth Trigger",
                eventType = "STEALTH_WAKE",
                detail = "Hidden vault summoned via ${source ?: "secret trigger"}",
            )
        }
    }

    fun initializeAdmin(
        password: String,
        wakeWord: String,
        dialerPin: String,
        securityQuestions: List<Pair<String, String>>,
    ) {
        viewModelScope.launch {
            repository.initializeAdmin(
                password = password,
                wakeWord = wakeWord,
                dialerPin = dialerPin,
                securityQuestions = securityQuestions,
            )
            localState.update {
                it.copy(
                    isSessionUnlocked = true,
                    authError = null,
                    failedUnlockAttempts = 0,
                    statusBannerMessage = "Admin vault initialized with backup reset questions.",
                )
            }
            refreshSystemStatus()
        }
    }

    fun unlockAdminSession(password: String) {
        viewModelScope.launch {
            val valid = repository.verifyAdminPassword(password)
            if (valid) {
                localState.update {
                    it.copy(
                        isSessionUnlocked = true,
                        authError = null,
                        failedUnlockAttempts = 0,
                        stealthWakeActive = false,
                    )
                }
                refreshSystemStatus()
            } else {
                localState.update {
                    it.copy(
                        authError = "Incorrect Admin password.",
                        failedUnlockAttempts = it.failedUnlockAttempts + 1,
                    )
                }
                repository.logEvent(
                    packageName = "admin",
                    appName = "VaultGuard",
                    eventType = "AUTH_FAILED",
                    detail = "Failed admin console login attempt",
                )
            }
        }
    }

    fun restoreSessionUnlockedFromAliasHandoff() {
        localState.update {
            it.copy(
                isSessionUnlocked = true,
                authError = null,
                failedUnlockAttempts = 0,
            )
        }
        refreshSystemStatus()
    }

    fun lockSession() {
        repository.clearUnlockedVaultTempFiles()
        localState.update {
            it.copy(
                isSessionUnlocked = false,
                isVaultMediaUnlocked = false,
                stealthWakeActive = false,
                authError = null,
                vaultUnlockError = null,
                failedUnlockAttempts = 0,
            )
        }
    }

    fun clearAuthError() {
        localState.update { it.copy(authError = null) }
    }

    fun clearStatusBanner() {
        localState.update { it.copy(statusBannerMessage = null) }
    }

    fun showStatusBanner(message: String) {
        localState.update { it.copy(statusBannerMessage = message) }
    }

    fun unlockVaultMedia(password: String) {
        viewModelScope.launch {
            val valid = repository.verifyAdminPassword(password)
            if (valid) {
                localState.update {
                    it.copy(
                        isVaultMediaUnlocked = true,
                        vaultUnlockError = null,
                    )
                }
            } else {
                localState.update {
                    it.copy(
                        isVaultMediaUnlocked = false,
                        vaultUnlockError = "Incorrect Admin password. Vault remains locked.",
                    )
                }
            }
        }
    }

    fun lockVaultMedia() {
        repository.clearUnlockedVaultTempFiles()
        localState.update {
            if (!it.isVaultMediaUnlocked && it.vaultUnlockError == null) {
                it
            } else {
                it.copy(
                    isVaultMediaUnlocked = false,
                    vaultUnlockError = null,
                )
            }
        }
    }

    fun clearVaultUnlockError() {
        localState.update { it.copy(vaultUnlockError = null) }
    }

    fun importMediaToVault(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            localState.update { it.copy(isImportingMedia = true) }
            val result = repository.importMediaToVault(uris)
            localState.update {
                it.copy(
                    isImportingMedia = false,
                    pendingGalleryDeleteSender = result.pendingDeleteIntentSender,
                    pendingGalleryDeleteIds = result.pendingDeleteItemIds,
                    statusBannerMessage = when {
                        result.importedItems.isEmpty() -> "Could not read selected file(s)."
                        result.pendingDeleteIntentSender != null ->
                            "Saved ${result.importedItems.size} item(s) to Vault. Confirm removal from device gallery."
                        result.removedCount == result.importedItems.size ->
                            "Hidden ${result.importedItems.size} item(s) in Vault & removed from phone gallery."
                        result.removedCount > 0 ->
                            "Added ${result.importedItems.size} item(s) to Vault (${result.removedCount} removed from gallery)."
                        else ->
                            "Encrypted copy saved in Vault. Delete the original file manually if external provider blocked removal."
                    },
                )
            }
        }
    }

    fun onGalleryDeleteCompleted(confirmed: Boolean) {
        val ids = localState.value.pendingGalleryDeleteIds
        viewModelScope.launch {
            if (confirmed && ids.isNotEmpty()) {
                repository.markVaultMediaHiddenFromGallery(ids)
            }
            localState.update {
                it.copy(
                    pendingGalleryDeleteSender = null,
                    pendingGalleryDeleteIds = emptyList(),
                    statusBannerMessage = if (confirmed) {
                        "Original media removed from phone gallery and hidden in Vault."
                    } else {
                        "Copy kept inside Vault; original remained in phone gallery."
                    },
                )
            }
        }
    }

    fun deleteVaultMediaReturningToGallery(item: VaultMediaEntity) {
        viewModelScope.launch {
            val result = repository.deleteVaultMediaReturningToGallery(item)
            result.fold(
                onSuccess = { msg ->
                    localState.update { it.copy(statusBannerMessage = msg) }
                },
                onFailure = { err ->
                    localState.update {
                        it.copy(statusBannerMessage = err.localizedMessage ?: "Failed to return item to gallery.")
                    }
                },
            )
        }
    }

    suspend fun decryptVaultPhotoBytes(item: VaultMediaEntity): ByteArray? =
        repository.decryptVaultPhotoBytes(item)

    suspend fun decryptVaultStreamFile(item: VaultMediaEntity): java.io.File? =
        repository.decryptVaultStreamFile(item)

    suspend fun decryptVaultDocPreviewFile(item: VaultMediaEntity): java.io.File? =
        repository.decryptVaultDocPreviewFile(item)

    fun saveSecurityQuestions(
        questionsAndAnswers: List<Pair<String, String>>,
        onDone: (Boolean, String) -> Unit,
    ) {
        viewModelScope.launch {
            val cleaned = questionsAndAnswers
                .map { (q, a) -> q.trim() to a.trim() }
                .filter { (q, a) -> q.isNotBlank() && a.isNotBlank() }
            if (cleaned.size < 3) {
                onDone(false, "Please save at least 3 security questions with answers.")
                return@launch
            }
            repository.saveSecurityQuestions(cleaned)
            localState.update {
                it.copy(statusBannerMessage = "Saved ${cleaned.size} encrypted backup reset questions.")
            }
            onDone(true, "Backup reset questions saved.")
        }
    }

    fun decryptStoredAnswer(entity: SecurityQuestionEntity): String =
        repository.decryptStoredAnswer(entity)

    fun verifySecurityAnswersForReset(
        answersByQuestionId: Map<Long, String>,
        onResult: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            onResult(repository.verifySecurityAnswersForReset(answersByQuestionId))
        }
    }

    fun resetPasswordViaSecurityQuestions(
        answersByQuestionId: Map<Long, String>,
        newPassword: String,
        onResult: (Boolean, String) -> Unit,
    ) {
        viewModelScope.launch {
            if (newPassword.length < 4) {
                onResult(false, "New Admin password must be at least 4 characters.")
                return@launch
            }
            val success = repository.resetPasswordViaSecurityQuestions(answersByQuestionId, newPassword)
            if (success) {
                localState.update {
                    it.copy(
                        isSessionUnlocked = true,
                        authError = null,
                        failedUnlockAttempts = 0,
                        stealthWakeActive = false,
                        statusBannerMessage = "Admin password reset successfully.",
                    )
                }
                refreshSystemStatus()
                onResult(true, "Admin password reset successfully.")
            } else {
                onResult(false, "At least 2 matching answers (out of 3+ saved questions) are required.")
            }
        }
    }

    fun setAppSearchQuery(query: String) {
        localState.update { it.copy(appSearchQuery = query) }
    }

    fun setShowLimitedOnly(limitedOnly: Boolean) {
        localState.update { it.copy(showLimitedOnly = limitedOnly) }
    }

    fun setShowSystemApps(showSystem: Boolean) {
        localState.update { it.copy(showSystemApps = showSystem) }
    }

    fun setAppGuardRule(
        row: AppRuleRow,
        guardMode: AppGuardMode,
        privacyMode: NotificationPrivacyMode = NotificationPrivacyMode.SILENT_AND_HIDE,
    ) {
        activeInspectedConfig = null
        if (guardMode != AppGuardMode.NONE) {
            AppGuardRuntimeCache.activeSetupPackage = null
        }
        viewModelScope.launch {
            val result = repository.setAppGuardRule(
                packageName = row.appInfo.packageName,
                appName = row.appInfo.appName,
                guardMode = guardMode,
                privacyMode = privacyMode,
            )
            result.fold(
                onSuccess = { report ->
                    val restrictBlockedByPermissions = guardMode != AppGuardMode.NONE &&
                        !report.canApplyRestriction
                    val endedBlockedByRestore = guardMode == AppGuardMode.NONE &&
                        report.isRestricted &&
                        report.restoreSteps.isNotEmpty()
                    if (restrictBlockedByPermissions) {
                        AppGuardRuntimeCache.activeSetupPackage = row.appInfo.packageName
                        val msg = if (report.missingCorePermissions.isNotEmpty()) {
                            "Enable ${report.missingCorePermissions.joinToString(" & ")} before restricting ${row.appInfo.appName}."
                        } else {
                            "Complete ${report.pendingSystemSteps.size} permission approval(s) before restricting ${row.appInfo.appName}."
                        }
                        localState.update {
                            it.copy(statusBannerMessage = msg)
                        }
                    } else if (endedBlockedByRestore) {
                        AppGuardRuntimeCache.activeSetupPackage = row.appInfo.packageName
                        localState.update {
                            it.copy(
                                pendingEndRestrictionPackage = row.appInfo.packageName,
                                statusBannerMessage = "Restore ${report.restoreSteps.size} setting(s) for ${row.appInfo.appName} before ending restriction.",
                            )
                        }
                    } else if (guardMode == AppGuardMode.NONE && !report.isRestricted) {
                        AppGuardRuntimeCache.activeSetupPackage = null
                        localState.update { current ->
                            current.copy(
                                pendingEndRestrictionPackage = if (current.pendingEndRestrictionPackage == row.appInfo.packageName) {
                                    null
                                } else {
                                    current.pendingEndRestrictionPackage
                                },
                                statusBannerMessage = "${row.appInfo.appName} restriction ended.",
                            )
                        }
                    }

                    refreshSystemStatus()
                    if (currentUserId.isNotBlank() && !endedBlockedByRestore && !restrictBlockedByPermissions) {
                        cloudSyncRepository.syncLimitedApp(
                            packageName = row.appInfo.packageName,
                            appName = row.appInfo.appName,
                            limited = guardMode != AppGuardMode.NONE,
                            blockCount = row.rule?.blockCount ?: 0,
                        )
                    }
                    if (!endedBlockedByRestore && !restrictBlockedByPermissions && guardMode != AppGuardMode.NONE) {
                        val banner = when (report.verificationState) {
                            EnforcementVerificationState.DEGRADED_PERMISSION_MISSING ->
                                "Enable ${report.missingCorePermissions.joinToString(" & ")} to enforce ${row.appInfo.appName}."
                            EnforcementVerificationState.ACTIVE_WITH_SYSTEM_GAPS ->
                                "${row.appInfo.appName}: ${report.pendingSystemSteps.size} system toggle(s) need approval."
                            else -> null
                        }
                        if (banner != null) {
                            localState.update { it.copy(statusBannerMessage = banner) }
                        }
                    }
                },
                onFailure = { err ->
                    localState.update {
                        it.copy(statusBannerMessage = err.localizedMessage ?: "Failed to update app rule.")
                    }
                },
            )
        }
    }

    fun toggleAppLimit(row: AppRuleRow) {
        val targetMode = if (row.isLimited) AppGuardMode.NONE else AppGuardMode.RESTRICTED
        setAppGuardRule(row, targetMode, row.notificationPrivacyMode)
    }

    fun setGuardActive(active: Boolean) {
        viewModelScope.launch {
            repository.setGuardActive(active)
            refreshSystemStatus()
        }
    }

    fun setUninstallProtection(enabled: Boolean) {
        viewModelScope.launch {
            repository.setUninstallProtection(enabled)
            refreshSystemStatus()
        }
    }

    fun setInstallGuard(enabled: Boolean) {
        viewModelScope.launch {
            repository.setInstallGuard(enabled)
        }
    }

    fun setNotificationBlockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setNotificationBlockEnabled(enabled)
            refreshSystemStatus()
        }
    }

    fun setCapturePhotoOnOpenEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setCapturePhotoOnOpenEnabled(enabled)
            refreshSystemStatus()
        }
    }

    fun applyDisguiseAndHiddenState(disguise: DisguiseIdentity, hideFromLauncher: Boolean) {
        viewModelScope.launch {
            repository.setDisguiseAndVisibility(disguise, hideFromLauncher)
            localState.update {
                it.copy(
                    statusBannerMessage = if (hideFromLauncher) {
                        "App icon hidden from launcher."
                    } else {
                        "Launcher disguised as ${disguise.displayLabel}."
                    }
                )
            }
        }
    }

    fun setRedirectDisplayStyle(style: RedirectDisplayStyle) {
        viewModelScope.launch {
            repository.setRedirectDisplayStyle(style)
            localState.update {
                it.copy(statusBannerMessage = "Icon display: ${style.displayLabel}.")
            }
        }
    }

    fun setRedirectDecoyMode(mode: RedirectDecoyMode) {
        viewModelScope.launch {
            repository.setRedirectDecoyMode(mode)
            localState.update {
                it.copy(
                    statusBannerMessage = if (mode == RedirectDecoyMode.FAKE_CRASH) {
                        "Fake Crash enabled."
                    } else {
                        "Icon Display enabled."
                    }
                )
            }
        }
    }

    fun setImmediateLockTimeoutSeconds(seconds: Int) {
        viewModelScope.launch {
            repository.setImmediateLockTimeoutSeconds(seconds)
            val label = when (seconds) {
                0 -> "Immediate"
                60 -> "1 minute"
                else -> "$seconds seconds"
            }
            localState.update {
                it.copy(statusBannerMessage = "Re-lock delay: $label.")
            }
        }
    }

    fun checkForUpdates() {
        otaUpdateManager.checkForUpdates()
    }

    fun downloadAndInstallUpdate() {
        otaUpdateManager.downloadAndInstallUpdate()
    }

    fun installVerifiedApk() {
        otaUpdateManager.launchVerifiedApkInstaller()
    }

    fun openUnknownSourcesSettings() {
        otaUpdateManager.openUnknownSourcesSettings()
    }

    fun dismissUpdateBanner() {
        otaUpdateManager.dismissUpdateBanner()
    }

    fun clearOtaMessages() {
        otaUpdateManager.clearUpdateMessages()
    }

    fun inspectApkForPublish(uri: Uri) {
        otaUpdateManager.inspectApkUriForPublish(uri)
    }

    fun publishOtaUpdate(
        releaseNotes: String,
        versionCodeInput: String,
        versionNameInput: String,
    ) {
        otaUpdateManager.publishUpdate(
            releaseNotes = releaseNotes,
            overrideVersionCode = versionCodeInput,
            overrideVersionName = versionNameInput,
        )
    }

    fun updateStealthTriggers(wakeWord: String, dialerPin: String) {
        viewModelScope.launch {
            repository.updateStealthTriggers(wakeWord, dialerPin)
            localState.update {
                it.copy(statusBannerMessage = "Stealth triggers updated.")
            }
        }
    }

    fun verifyAdminPassword(password: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val valid = repository.verifyAdminPassword(password)
            onResult(valid)
        }
    }

    fun changeAdminPassword(currentPassword: String, newPassword: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val valid = repository.verifyAdminPassword(currentPassword)
            if (!valid) {
                onResult(false, "Current Admin password is incorrect.")
                return@launch
            }
            repository.updatePassword(newPassword)
            onResult(true, "Admin password updated.")
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            repository.clearEvents()
        }
    }

    fun checkWakeOrDialerInDisguise(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return false
        return AppGuardRuntimeCache.matchesWakeWord(trimmed) ||
            AppGuardRuntimeCache.matchesDialerSecret(trimmed) ||
            trimmed == AppGuardRuntimeCache.config.dialerPin
    }
}

class VaultViewModelFactory(
    private val repository: VaultRepository,
    private val cloudSyncRepository: CloudSyncRepository,
    private val otaUpdateManager: OtaUpdateManager,
    private val currentUserId: String,
    private val userDisplayName: String,
    private val userEmail: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VaultViewModel::class.java)) {
            return VaultViewModel(
                repository = repository,
                cloudSyncRepository = cloudSyncRepository,
                otaUpdateManager = otaUpdateManager,
                currentUserId = currentUserId,
                userDisplayName = userDisplayName,
                userEmail = userEmail,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
