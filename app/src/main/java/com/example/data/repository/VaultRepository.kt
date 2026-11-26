package com.example.data.repository

import android.Manifest
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.example.data.AppGuardRuntimeCache
import java.io.File
import com.example.data.InstalledAppInfo
import com.example.data.InstalledAppsProvider
import com.example.data.VaultImportResult
import com.example.data.VaultMediaManager
import com.example.data.db.AppRestrictionFrequency
import com.example.data.db.VaultDao
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
import com.example.launcher.LauncherDisguiseManager
import com.example.restriction.DeepRestrictionEngine
import com.example.restriction.PackageInspectionReport
import com.example.restriction.SystemIntegrityStatus
import com.example.restriction.SystemRestrictionStep
import com.example.security.PasswordHasher
import com.example.security.SecretCipher
import com.example.service.AppGuardAccessibilityService
import com.example.service.VaultDeviceAdminReceiver
import com.example.service.VaultNotificationListenerService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map

class VaultRepository(
    private val context: Context,
    private val dao: VaultDao,
    private val appsProvider: InstalledAppsProvider,
    private val launcherManager: LauncherDisguiseManager,
    private val vaultMediaManager: VaultMediaManager = VaultMediaManager(context),
    private val deepRestrictionEngine: DeepRestrictionEngine = DeepRestrictionEngine(context, dao),
) {
    val configFlow: Flow<AdminConfigEntity> = dao.observeConfig().map { it ?: AdminConfigEntity() }
    val limitedAppsFlow: Flow<List<LimitedAppEntity>> = dao.observeLimitedApps()
    val recentEventsFlow: Flow<List<AccessEventEntity>> = dao.observeRecentEvents()
    val appRestrictionFrequenciesFlow: Flow<List<AppRestrictionFrequency>> = dao.observeRestrictionEventFrequencies()
    val vaultMediaFlow: Flow<List<VaultMediaEntity>> = vaultMediaManager.vaultMediaFlow
    val securityQuestionsFlow: Flow<List<SecurityQuestionEntity>> = dao.observeSecurityQuestions()

    suspend fun syncRuntimeCache() {
        val config = dao.getConfigOnce() ?: AdminConfigEntity().also { dao.upsertConfig(it) }
        val rules = dao.getLimitedAppsOnce()
        deepRestrictionEngine.hydrateTrackedChannelsFromDb()
        AppGuardRuntimeCache.updateConfig(config)
        AppGuardRuntimeCache.updateLimitedApps(rules)
    }

    suspend fun initializeAdmin(
        password: String,
        wakeWord: String,
        dialerPin: String,
        securityQuestions: List<Pair<String, String>> = emptyList(),
    ) {
        val cleanWake = wakeWord.trim().lowercase().ifBlank { "vault" }
        val cleanPin = dialerPin.filter { it.isDigit() }.ifBlank {
            PasswordHasher.wakeWordToT9Digits(cleanWake).ifBlank { "82858" }
        }
        val config = AdminConfigEntity(
            id = 1,
            isInitialized = true,
            passwordHash = PasswordHasher.hashPassword(password),
            wakeWord = cleanWake,
            dialerPin = cleanPin,
            disguiseName = DisguiseIdentity.DEFAULT.name,
            isAppHidden = false,
            uninstallProtectionEnabled = true,
            installGuardEnabled = false,
            notificationBlockEnabled = true,
            guardActive = true,
        )
        dao.upsertConfig(config)
        AppGuardRuntimeCache.updateConfig(config)
        if (securityQuestions.size >= 3) {
            saveSecurityQuestions(securityQuestions)
        }
        logEvent(
            packageName = context.packageName,
            appName = "VaultGuard",
            eventType = "VAULT_INITIALIZED",
            detail = "Admin password, wake word & ${securityQuestions.size} reset questions configured",
        )
    }

    suspend fun saveSecurityQuestions(questionsAndAnswers: List<Pair<String, String>>) {
        val now = System.currentTimeMillis()
        val entities = questionsAndAnswers
            .map { (q, a) -> q.trim() to a.trim() }
            .filter { (q, a) -> q.isNotBlank() && a.isNotBlank() }
            .map { (q, a) ->
                SecurityQuestionEntity(
                    question = q,
                    answerHash = PasswordHasher.hashSecurityAnswer(a),
                    encryptedAnswer = SecretCipher.encryptAnswer(a),
                    createdAtMillis = now,
                )
            }
        require(entities.size >= 3) { "At least 3 security questions with answers are required." }
        dao.replaceSecurityQuestions(entities)
        logEvent(
            packageName = context.packageName,
            appName = "VaultGuard",
            eventType = "RESET_QUESTIONS_UPDATED",
            detail = "Saved ${entities.size} encrypted backup password reset answers",
        )
    }

    fun decryptStoredAnswer(entity: SecurityQuestionEntity): String {
        val decrypted = SecretCipher.decryptAnswer(entity.encryptedAnswer)
        return decrypted.ifBlank { "(Saved securely)" }
    }

    suspend fun verifySecurityAnswersForReset(answersByQuestionId: Map<Long, String>): Boolean {
        val saved = dao.getSecurityQuestionsOnce()
        val hashMap = saved.associate { it.id to it.answerHash }
        return PasswordHasher.qualifiesForPasswordReset(
            storedHashesByQuestionId = hashMap,
            providedAnswersByQuestionId = answersByQuestionId,
            minQuestionsRequired = 3,
            minMatchesRequired = 2,
        )
    }

    suspend fun resetPasswordViaSecurityQuestions(
        answersByQuestionId: Map<Long, String>,
        newPassword: String,
    ): Boolean {
        if (!verifySecurityAnswersForReset(answersByQuestionId)) return false
        val current = dao.getConfigOnce() ?: return false
        val updated = current.copy(passwordHash = PasswordHasher.hashPassword(newPassword))
        dao.upsertConfig(updated)
        AppGuardRuntimeCache.updateConfig(updated)
        logEvent(
            packageName = context.packageName,
            appName = "VaultGuard",
            eventType = "PASSWORD_RESET",
            detail = "Admin password reset after verifying backup security questions",
        )
        return true
    }

    suspend fun importMediaToVault(uris: List<Uri>): VaultImportResult {
        val result = vaultMediaManager.copyUrisToPrivateVault(uris)
        if (result.importedItems.isEmpty()) return result
        logEvent(
            packageName = context.packageName,
            appName = "Media Vault",
            eventType = "VAULT_MEDIA_HIDDEN",
            detail = "Encrypted & hid ${result.importedItems.size} item(s) in private Vault storage",
        )
        return result
    }

    suspend fun markVaultMediaHiddenFromGallery(ids: List<String>) {
        vaultMediaManager.markItemsRemovedFromGallery(ids)
    }

    suspend fun deleteVaultMediaReturningToGallery(item: VaultMediaEntity): Result<String> {
        val res = vaultMediaManager.deleteFromVaultReturningToGallery(item)
        res.onSuccess {
            logEvent(
                packageName = context.packageName,
                appName = "Media Vault",
                eventType = "VAULT_MEDIA_RETURNED_TO_GALLERY",
                detail = "Removed ${item.displayName} from Vault & returned to ${item.originalRelativePath.ifBlank { "phone gallery" }}",
            )
        }
        return res
    }

    suspend fun decryptVaultPhotoBytes(item: VaultMediaEntity): ByteArray? =
        vaultMediaManager.decryptPhotoBytes(item)

    suspend fun decryptVaultStreamFile(item: VaultMediaEntity): File? =
        vaultMediaManager.decryptToInAppStreamFile(item)

    suspend fun decryptVaultDocPreviewFile(item: VaultMediaEntity): File? =
        vaultMediaManager.decryptToDocPreviewFile(item)

    fun clearUnlockedVaultTempFiles() {
        vaultMediaManager.clearUnlockedTempPreviews()
    }

    suspend fun verifyAdminPassword(password: String): Boolean {
        val config = dao.getConfigOnce() ?: return false
        return PasswordHasher.verifyPassword(password, config.passwordHash)
    }

    suspend fun updatePassword(newPassword: String) {
        val current = dao.getConfigOnce() ?: AdminConfigEntity()
        val updated = current.copy(passwordHash = PasswordHasher.hashPassword(newPassword))
        dao.upsertConfig(updated)
        AppGuardRuntimeCache.updateConfig(updated)
        logEvent(context.packageName, "VaultGuard", "PASSWORD_CHANGED", "Admin password rotated")
    }

    suspend fun setGuardActive(active: Boolean) {
        val current = dao.getConfigOnce() ?: AdminConfigEntity()
        val updated = current.copy(guardActive = active)
        dao.upsertConfig(updated)
        AppGuardRuntimeCache.updateConfig(updated)
        logEvent(
            context.packageName,
            "VaultGuard",
            if (active) "GUARD_ENABLED" else "GUARD_PAUSED",
            if (active) "App limiting enforcement active" else "App limiting enforcement paused",
        )
    }

    suspend fun setUninstallProtection(enabled: Boolean) {
        val current = dao.getConfigOnce() ?: AdminConfigEntity()
        val updated = current.copy(uninstallProtectionEnabled = enabled)
        dao.upsertConfig(updated)
        AppGuardRuntimeCache.updateConfig(updated)
    }

    suspend fun setInstallGuard(enabled: Boolean) {
        val current = dao.getConfigOnce() ?: AdminConfigEntity()
        val updated = current.copy(installGuardEnabled = enabled)
        dao.upsertConfig(updated)
        AppGuardRuntimeCache.updateConfig(updated)
        logEvent(
            context.packageName,
            "VaultGuard",
            if (enabled) "INSTALL_GUARD_ON" else "INSTALL_GUARD_OFF",
            if (enabled) "New app installations require admin password" else "Install guard disabled",
        )
    }

    suspend fun setNotificationBlockEnabled(enabled: Boolean) {
        val current = dao.getConfigOnce() ?: AdminConfigEntity()
        val updated = current.copy(notificationBlockEnabled = enabled)
        dao.upsertConfig(updated)
        AppGuardRuntimeCache.updateConfig(updated)
    }

    suspend fun setCapturePhotoOnOpenEnabled(enabled: Boolean) {
        val current = dao.getConfigOnce() ?: AdminConfigEntity()
        val updated = current.copy(capturePhotoOnOpenEnabled = enabled)
        dao.upsertConfig(updated)
        AppGuardRuntimeCache.updateConfig(updated)
    }

    suspend fun updateStealthTriggers(wakeWord: String, dialerPin: String) {
        val current = dao.getConfigOnce() ?: AdminConfigEntity()
        val cleanWake = wakeWord.trim().lowercase().ifBlank { current.wakeWord }
        val cleanPin = dialerPin.filter { it.isDigit() }.ifBlank {
            PasswordHasher.wakeWordToT9Digits(cleanWake).ifBlank { "82858" }
        }
        val updated = current.copy(wakeWord = cleanWake, dialerPin = cleanPin)
        dao.upsertConfig(updated)
        AppGuardRuntimeCache.updateConfig(updated)
        logEvent(
            context.packageName,
            "VaultGuard",
            "STEALTH_UPDATED",
            "Wake word & dialer trigger updated",
        )
    }

    suspend fun setDisguiseAndVisibility(
        disguise: DisguiseIdentity,
        hideFromLauncher: Boolean,
    ) {
        launcherManager.applyLauncherState(disguise, hideFromLauncher)
        val current = dao.getConfigOnce() ?: AdminConfigEntity()
        val updated = current.copy(
            disguiseName = disguise.name,
            isAppHidden = hideFromLauncher,
        )
        dao.upsertConfig(updated)
        AppGuardRuntimeCache.updateConfig(updated)
        logEvent(
            context.packageName,
            disguise.displayLabel,
            if (hideFromLauncher) "HIDDEN_MODE_ON" else "DISGUISE_APPLIED",
            if (hideFromLauncher) {
                "App hidden from launcher; wake via search or dialer"
            } else {
                "Launcher identity changed to ${disguise.displayLabel}"
            },
        )
    }

    suspend fun setRedirectDisplayStyle(style: RedirectDisplayStyle) {
        val current = dao.getConfigOnce() ?: AdminConfigEntity()
        val updated = current.copy(redirectDisplayName = style.name)
        dao.upsertConfig(updated)
        AppGuardRuntimeCache.updateConfig(updated)
        logEvent(
            context.packageName,
            "VaultGuard",
            "ICON_DISPLAY_UPDATED",
            "Redirect decoy display set to ${style.displayLabel}",
        )
    }

    suspend fun setRedirectDecoyMode(mode: RedirectDecoyMode) {
        val current = dao.getConfigOnce() ?: AdminConfigEntity()
        val updated = current.copy(redirectDecoyMode = mode.name)
        dao.upsertConfig(updated)
        AppGuardRuntimeCache.updateConfig(updated)
        val label = if (mode == RedirectDecoyMode.FAKE_CRASH) "Fake Crash" else "Icon Display"
        logEvent(
            context.packageName,
            "VaultGuard",
            "REDIRECT_DECOY_MODE",
            "Redirect gate switched exclusively to $label",
        )
    }

    suspend fun setImmediateLockTimeoutSeconds(seconds: Int) {
        val clamped = when (seconds) {
            5, 15, 60 -> seconds
            else -> 0
        }
        val current = dao.getConfigOnce() ?: AdminConfigEntity()
        val updated = current.copy(immediateLockTimeoutSeconds = clamped)
        dao.upsertConfig(updated)
        AppGuardRuntimeCache.updateConfig(updated)
        val label = when (clamped) {
            0 -> "Immediate"
            60 -> "1 minute"
            else -> "$clamped seconds"
        }
        logEvent(
            context.packageName,
            "VaultGuard",
            "RE_LOCK_TIMER_UPDATED",
            "App switch re-lock delay set to $label",
        )
    }

    suspend fun setAppGuardRule(
        packageName: String,
        appName: String,
        guardMode: AppGuardMode,
        privacyMode: NotificationPrivacyMode = NotificationPrivacyMode.SILENT_AND_HIDE,
    ): Result<PackageInspectionReport> {
        val config = dao.getConfigOnce()
        if (guardMode != AppGuardMode.NONE && config?.isInitialized != true) {
            return Result.failure(IllegalStateException("Set Admin password first."))
        }
        val existing = dao.getLimitedApp(packageName)
        val isLimited = guardMode != AppGuardMode.NONE
        val effectivePrivacy = if (isLimited) {
            privacyMode
        } else {
            existing?.notificationPrivacyEnum ?: NotificationPrivacyMode.SILENT_AND_HIDE
        }

        val integrity = computeSystemIntegrityStatus(config, dao.getLimitedAppsOnce())
        val silentAndHide = effectivePrivacy == NotificationPrivacyMode.SILENT_AND_HIDE

        if (isLimited) {
            val preReport = deepRestrictionEngine.inspectSingleApp(
                packageName = packageName,
                appName = appName,
                isRestricted = true,
                silentAndHide = silentAndHide,
                integrity = integrity,
            )
            if (!preReport.canApplyRestriction) {
                logEvent(
                    packageName = packageName,
                    appName = appName,
                    eventType = "RESTRICTION_PERMISSIONS_REQUIRED",
                    detail = when {
                        preReport.missingCorePermissions.isNotEmpty() ->
                            "Missing ${preReport.missingCorePermissions.joinToString(", ")}"
                        else ->
                            "${preReport.pendingSystemSteps.size} system approval(s) required"
                    },
                )
                return Result.success(
                    preReport.copy(isRestricted = existing?.isLimited == true)
                )
            }
        }

        val report = if (isLimited) {
            val updated = (existing ?: LimitedAppEntity(packageName = packageName, appName = appName))
                .copy(
                    appName = appName,
                    isLimited = true,
                    guardMode = AppGuardMode.RESTRICTED.name,
                    notificationPrivacyMode = effectivePrivacy.name,
                    tempUnlockUntilMillis = 0L,
                )
            dao.upsertLimitedApp(updated)
            syncRuntimeCache()
            deepRestrictionEngine.applyRestrictionWithSnapshot(
                packageName = packageName,
                appName = appName,
                privacyMode = effectivePrivacy,
                integrity = integrity,
            )
        } else {
            val (ended, restoreReport) = deepRestrictionEngine.tryEndRestrictionAfterRestore(
                packageName = packageName,
                appName = appName,
                silentAndHide = silentAndHide,
                integrity = integrity,
            )
            if (ended) {
                val updated = (existing ?: LimitedAppEntity(packageName = packageName, appName = appName))
                    .copy(
                        appName = appName,
                        isLimited = false,
                        guardMode = AppGuardMode.NONE.name,
                        notificationPrivacyMode = effectivePrivacy.name,
                        tempUnlockUntilMillis = 0L,
                    )
                dao.upsertLimitedApp(updated)
                syncRuntimeCache()
            } else if (existing?.isLimited != true) {
                val keptRestricted = (existing ?: LimitedAppEntity(packageName = packageName, appName = appName))
                    .copy(
                        appName = appName,
                        isLimited = true,
                        guardMode = AppGuardMode.RESTRICTED.name,
                        notificationPrivacyMode = effectivePrivacy.name,
                        tempUnlockUntilMillis = 0L,
                    )
                dao.upsertLimitedApp(keptRestricted)
                syncRuntimeCache()
            }
            restoreReport
        }

        val detail = when {
            isLimited -> {
                val privText = if (effectivePrivacy == NotificationPrivacyMode.SILENT_AND_HIDE) {
                    "Silent & Hidden"
                } else {
                    "Show, Hide Content"
                }
                "Restricted • $privText • ${report.statusBadgeText}"
            }
            report.isRestricted -> "Restore required (${report.restoreSteps.size} setting(s) still off)"
            else -> "Unrestricted"
        }
        logEvent(
            packageName = packageName,
            appName = appName,
            eventType = when {
                isLimited -> "APP_RESTRICTED"
                report.isRestricted -> "RESTORE_REQUIRED"
                else -> "APP_UNRESTRICTED"
            },
            detail = detail,
        )
        return Result.success(report)
    }

    suspend fun setAppLimited(packageName: String, appName: String, limited: Boolean) {
        setAppGuardRule(
            packageName = packageName,
            appName = appName,
            guardMode = if (limited) AppGuardMode.RESTRICTED else AppGuardMode.NONE,
            privacyMode = NotificationPrivacyMode.SILENT_AND_HIDE,
        )
    }

    suspend fun recordChannelModifiedWhileRestricted(
        packageName: String,
        channelInfo: com.example.restriction.DetectedChannelInfo,
    ) {
        deepRestrictionEngine.recordChannelModifiedWhileRestricted(packageName, channelInfo)
    }

    suspend fun inspectAllAppRestrictions(
        installedApps: List<InstalledAppInfo>,
    ): Map<String, PackageInspectionReport> {
        val config = dao.getConfigOnce() ?: AdminConfigEntity()
        val rules = dao.getLimitedAppsOnce()
        val integrity = computeSystemIntegrityStatus(config, rules)
        val pairs = installedApps.map { it.packageName to it.appName }
        return deepRestrictionEngine.inspectAllKnownApps(pairs, integrity)
    }

    suspend fun inspectSingleAppRestriction(
        packageName: String,
        appName: String,
        isRestricted: Boolean,
        silentAndHide: Boolean,
    ): PackageInspectionReport {
        val config = dao.getConfigOnce() ?: AdminConfigEntity()
        val rules = dao.getLimitedAppsOnce()
        val integrity = computeSystemIntegrityStatus(config, rules)
        return deepRestrictionEngine.inspectSingleApp(
            packageName = packageName,
            appName = appName,
            isRestricted = isRestricted,
            silentAndHide = silentAndHide,
            integrity = integrity,
        )
    }

    suspend fun recordGuidedSystemStepOpened(packageName: String, step: SystemRestrictionStep) {
        deepRestrictionEngine.recordGuidedSystemStepOpened(packageName, step)
    }

    suspend fun hasRestrictedAppState(packageName: String): Boolean =
        deepRestrictionEngine.hasRestrictedAppState(packageName)

    fun buildSystemSettingsIntent(step: SystemRestrictionStep) =
        deepRestrictionEngine.buildSystemSettingsIntent(step)

    fun buildSystemSettingsIntentChain(step: SystemRestrictionStep) =
        deepRestrictionEngine.buildSystemSettingsIntentChain(step)

    suspend fun grantSessionUnlock(
        packageName: String,
        appName: String,
        photoPath: String? = null,
    ) {
        AppGuardRuntimeCache.grantSessionUnlock(packageName)
        logEvent(
            packageName = packageName,
            appName = appName,
            eventType = "SESSION_UNLOCKED",
            detail = "Unlocked",
            photoPath = photoPath,
        )
    }

    suspend fun recordRedirectBlock(
        packageName: String,
        appName: String,
        decoyMode: RedirectDecoyMode = RedirectDecoyMode.ICON_DISPLAY,
        photoPath: String? = null,
    ): Long? {
        val existing = dao.getLimitedApp(packageName) ?: return null
        val now = System.currentTimeMillis()
        dao.upsertLimitedApp(
            existing.copy(
                blockCount = existing.blockCount + 1,
                lastBlockedAtMillis = now,
            )
        )
        syncRuntimeCache()
        val gateDesc = if (decoyMode == RedirectDecoyMode.FAKE_CRASH) "Fake Crash" else "Icon Display"
        return logEvent(
            packageName = packageName,
            appName = appName,
            eventType = "RESTRICTED_GATE",
            detail = gateDesc,
            photoPath = photoPath,
        )
    }

    suspend fun attachPhotoToEvent(eventId: Long, photoPath: String) {
        dao.updateEventPhoto(eventId, photoPath)
    }

    suspend fun attachPhotoToEvents(eventIds: Iterable<Long>, photoPath: String) {
        if (!hasValidPhotoFile(photoPath)) return
        eventIds.forEach { eventId ->
            dao.updateEventPhoto(eventId, photoPath)
        }
    }

    fun hasValidPhotoFile(photoPath: String?): Boolean {
        if (photoPath.isNullOrBlank()) return false
        val file = File(photoPath)
        return file.exists() && file.length() > 0L
    }

    suspend fun logEvent(
        packageName: String,
        appName: String,
        eventType: String,
        detail: String,
        photoPath: String? = null,
    ): Long {
        return dao.insertEvent(
            AccessEventEntity(
                packageName = packageName,
                appName = appName,
                eventType = eventType,
                detail = detail,
                photoPath = photoPath,
                timestampMillis = System.currentTimeMillis(),
            )
        )
    }

    suspend fun clearEvents() {
        dao.clearEvents()
        runCatching {
            File(context.filesDir, "session_photos").deleteRecursively()
        }
    }

    suspend fun loadInstalledApps(): List<InstalledAppInfo> = appsProvider.loadLaunchableApps()

    fun getInstalledAppInfo(packageName: String): InstalledAppInfo? =
        appsProvider.getAppInfoForPackage(packageName)

    fun observeSystemPermissionSettings(): Flow<Unit> = callbackFlow {
        val cr = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Unit)
            }
        }
        val uris = listOfNotNull(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
            Settings.Secure.getUriFor("enabled_notification_listeners"),
        )
        uris.forEach { uri ->
            runCatching { cr.registerContentObserver(uri, false, observer) }
        }
        awaitClose {
            runCatching { cr.unregisterContentObserver(observer) }
        }
    }

    fun isAccessibilityServiceEnabled(): Boolean {
        val accMaster = runCatching {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, -1)
        }.getOrDefault(-1)
        if (accMaster == 0) {
            return false
        }
        val expectedComponent = ComponentName(context, AppGuardAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        if (enabledServices.isNullOrBlank()) {
            return false
        }

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val componentStr = splitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentStr)
            if (enabledComponent == expectedComponent) {
                return true
            }
        }
        return false
    }

    fun isNotificationListenerEnabled(): Boolean {
        val expected = ComponentName(context, VaultNotificationListenerService::class.java)
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        )
        if (!enabledListeners.isNullOrBlank()) {
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledListeners)
            while (splitter.hasNext()) {
                val component = ComponentName.unflattenFromString(splitter.next())
                if (component == expected) {
                    return true
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null && runCatching { nm.isNotificationListenerAccessGranted(expected) }.getOrDefault(false)) {
                return true
            }
        }
        return false
    }

    fun isCameraPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasAnyCameraHardware(): Boolean {
        val hasFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
        if (!hasFeature) return false
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
        return runCatching { cm?.cameraIdList?.isNotEmpty() == true }.getOrDefault(true)
    }

    fun isPostNotificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isDeviceAdminActive(): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        val adminComponent = ComponentName(context, VaultDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(adminComponent)
    }

    fun isDeviceOwnerActive(): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    fun computeSystemIntegrityStatus(
        config: AdminConfigEntity?,
        limitedApps: List<LimitedAppEntity>,
    ): SystemIntegrityStatus {
        val effectiveConfig = config ?: AdminConfigEntity()
        val anyMaskedAlerts = limitedApps.any {
            it.isLimited && it.notificationPrivacyEnum == NotificationPrivacyMode.SHOW_HIDE_SENSITIVE
        }
        return SystemIntegrityStatus(
            isAccessibilityEnabled = isAccessibilityServiceEnabled(),
            isAccessibilityServiceBound = AppGuardRuntimeCache.accessibilityConnected.value,
            isNotificationListenerEnabled = isNotificationListenerEnabled(),
            isNotificationListenerBound = AppGuardRuntimeCache.notificationListenerConnected.value,
            isDeviceAdminEnabled = isDeviceAdminActive(),
            isCameraPermissionGranted = isCameraPermissionGranted(),
            isPostNotificationsGranted = isPostNotificationsGranted(),
            isDeviceOwnerActive = isDeviceOwnerActive(),
            guardActive = effectiveConfig.guardActive,
            notificationBlockEnabled = effectiveConfig.notificationBlockEnabled,
            uninstallProtectionEnabled = effectiveConfig.uninstallProtectionEnabled,
            capturePhotoOnOpenEnabled = effectiveConfig.capturePhotoOnOpenEnabled,
            requiresPostNotificationsForMaskedAlerts = anyMaskedAlerts,
        )
    }
}
