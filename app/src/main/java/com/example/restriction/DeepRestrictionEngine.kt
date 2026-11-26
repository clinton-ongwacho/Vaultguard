package com.example.restriction

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.example.data.AppGuardRuntimeCache
import com.example.data.db.VaultDao
import com.example.data.entity.NotificationPrivacyMode
import com.example.data.entity.RestrictedAppStateEntity
import com.example.service.VaultDeviceAdminReceiver
import com.example.service.VaultNotificationListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Coordinates automatic inspection, strongest non-DND enforcement, truthful verification,
 * guided Android settings launch intents, continuous channel syncing, and restoration
 * when an app is unrestricted.
 */
class DeepRestrictionEngine(
    private val context: Context,
    private val dao: VaultDao,
    private val inspector: PackageCapabilitiesInspector = PackageCapabilitiesInspector(context),
) {

    suspend fun hydrateTrackedChannelsFromDb() = withContext(Dispatchers.IO) {
        val states = dao.getRestrictedAppStatesOnce()
        for (state in states) {
            val channels = RestrictionStateCodec.decodeChannels(state.discoveredChannelsJson)
            UniversalChannelTracker.seedFromPersisted(state.packageName, channels)
        }
    }

    suspend fun applyRestrictionWithSnapshot(
        packageName: String,
        appName: String,
        privacyMode: NotificationPrivacyMode,
        integrity: SystemIntegrityStatus,
    ): PackageInspectionReport = withContext(Dispatchers.IO) {
        val existingEntity = dao.getRestrictedAppState(packageName)
        val persistedChannels = existingEntity?.let {
            RestrictionStateCodec.decodeChannels(it.discoveredChannelsJson)
        }.orEmpty()
        UniversalChannelTracker.seedFromPersisted(packageName, persistedChannels)
        VaultNotificationListenerService.refreshChannelsForPackage(packageName)
        val liveChannels = UniversalChannelTracker.getChannelsForPackage(packageName)

        var snapshot = existingEntity?.let { RestrictionStateCodec.decodeSnapshot(it.snapshotJson) }
        val rawBefore = inspector.inspectRawCapabilities(packageName, appName)
        val bannedBefore = UniversalChannelTracker.isPackageNotificationsBanned(packageName)

        if (snapshot == null) {
            snapshot = AppRestrictionSnapshot(
                packageName = packageName,
                capturedAtMillis = System.currentTimeMillis(),
                hadPostNotificationsGranted = rawBefore.postNotificationsGranted,
                hadOverlayAllowed = rawBefore.overlayAllowed,
                hadFullScreenIntentAllowed = rawBefore.fullScreenIntentAllowed,
                hadAppNotificationsActive = !bannedBefore && (rawBefore.postNotificationsGranted != false),
                wasSuspendedByDeviceOwner = rawBefore.isSuspendedByDeviceOwner,
                wasRestrictedApplied = true,
                channelBaselines = liveChannels.map { ch ->
                    ChannelBaselineRecord(
                        channelId = ch.channelId,
                        channelName = ch.channelName,
                        wasBlockedBeforeRestriction = ch.isBlockedInSystem,
                        baselineImportance = ch.importance,
                        isCallChannel = ch.isCallChannel,
                        hadSound = ch.hasSound,
                        hadVibration = ch.hasVibration,
                    )
                },
            )
        } else {
            snapshot = mergeChannelBaselines(snapshot, liveChannels).copy(
                wasRestrictedApplied = true,
                userRestoredGuidedSteps = emptyList(),
            )
        }

        // Strongest automatic enforcement when Device Owner is active (no root required).
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val adminComponent = ComponentName(context, VaultDeviceAdminReceiver::class.java)
        var modifiedByDeviceOwner = snapshot
        if (dpm != null && dpm.isDeviceOwnerApp(context.packageName)) {
            if (privacyMode == NotificationPrivacyMode.SILENT_AND_HIDE &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ) {
                val denied = runCatching {
                    dpm.setPermissionGrantState(
                        adminComponent,
                        packageName,
                        Manifest.permission.POST_NOTIFICATIONS,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED,
                    )
                }.getOrDefault(false)
                if (denied) {
                    modifiedByDeviceOwner = modifiedByDeviceOwner.copy(
                        deviceOwnerDeniedPostNotifications = true,
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val failed = runCatching {
                    dpm.setPackagesSuspended(adminComponent, arrayOf(packageName), true)
                }.getOrNull()
                if (failed != null && !failed.contains(packageName)) {
                    modifiedByDeviceOwner = modifiedByDeviceOwner.copy(
                        deviceOwnerSuspendedPackage = true,
                    )
                }
            }
        }

        val report = inspectAndPersistState(
            packageName = packageName,
            fallbackAppName = appName,
            isRestricted = true,
            silentAndHide = privacyMode == NotificationPrivacyMode.SILENT_AND_HIDE,
            integrity = integrity,
            overrideSnapshot = modifiedByDeviceOwner,
        )
        AppGuardRuntimeCache.bumpBlockingVersion()
        report
    }

    /**
     * Attempts to end restriction for [packageName]. Restores any Device-Owner overrides,
     * then checks whether any user/OS settings turned off during restriction still remain off.
     *
     * Returns `(ended = true, report)` ONLY when every turned-off setting has been restored;
     * otherwise returns `(ended = false, report)` with `report.restoreSteps` populated so
     * callers keep the restriction active until restoration completes.
     */
    suspend fun tryEndRestrictionAfterRestore(
        packageName: String,
        appName: String,
        silentAndHide: Boolean,
        integrity: SystemIntegrityStatus,
    ): Pair<Boolean, PackageInspectionReport> = withContext(Dispatchers.IO) {
        val existingEntity = dao.getRestrictedAppState(packageName)
        val snapshot = existingEntity?.let { RestrictionStateCodec.decodeSnapshot(it.snapshotJson) }
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val adminComponent = ComponentName(context, VaultDeviceAdminReceiver::class.java)

        if (snapshot != null && dpm != null && dpm.isDeviceOwnerApp(context.packageName)) {
            if (snapshot.deviceOwnerSuspendedPackage && !snapshot.wasSuspendedByDeviceOwner &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
            ) {
                runCatching {
                    dpm.setPackagesSuspended(adminComponent, arrayOf(packageName), false)
                }
            }
            if (snapshot.deviceOwnerDeniedPostNotifications &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ) {
                val restoreGrant = if (snapshot.hadPostNotificationsGranted == true) {
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                } else {
                    DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
                }
                runCatching {
                    dpm.setPermissionGrantState(
                        adminComponent,
                        packageName,
                        Manifest.permission.POST_NOTIFICATIONS,
                        restoreGrant,
                    )
                }
            }
        }

        val checkUnrestricted = inspectAndPersistState(
            packageName = packageName,
            fallbackAppName = appName,
            isRestricted = false,
            silentAndHide = false,
            integrity = integrity,
            overrideSnapshot = snapshot,
        )

        if (checkUnrestricted.restoreSteps.isEmpty()) {
            dao.deleteRestrictedAppState(packageName)
            AppGuardRuntimeCache.bumpBlockingVersion()
            true to checkUnrestricted
        } else {
            val stillRestrictedReport = inspectAndPersistState(
                packageName = packageName,
                fallbackAppName = appName,
                isRestricted = true,
                silentAndHide = silentAndHide,
                integrity = integrity,
                overrideSnapshot = snapshot,
            )
            AppGuardRuntimeCache.bumpBlockingVersion()
            false to stillRestrictedReport
        }
    }

    suspend fun recordGuidedSystemStepOpened(packageName: String, step: SystemRestrictionStep) =
        withContext(Dispatchers.IO) {
            VaultNotificationListenerService.refreshChannelsForPackage(packageName)
            val existing = dao.getRestrictedAppState(packageName)
            val liveChannels = UniversalChannelTracker.getChannelsForPackage(packageName)
            val bannedNow = UniversalChannelTracker.isPackageNotificationsBanned(packageName)
            if (existing != null) {
                var snapshot = RestrictionStateCodec.decodeSnapshot(existing.snapshotJson) ?: return@withContext
                snapshot = mergeChannelBaselines(snapshot, liveChannels)
                if (step.isRestoreStep && step.kind == SystemStepKind.APP_NOTIFICATIONS) {
                    val rawNow = inspector.inspectRawCapabilities(packageName, packageName)
                    if (rawNow.postNotificationsGranted != false) {
                        UniversalChannelTracker.markPackageNotificationsBanned(packageName, false)
                    }
                }
                val updatedSteps = if (step.isRestoreStep) {
                    snapshot.userGuidedSystemSteps
                } else {
                    (snapshot.userGuidedSystemSteps + step.kind.name).distinct()
                }
                val updatedRestored = if (step.isRestoreStep) {
                    (snapshot.userRestoredGuidedSteps + step.kind.name).distinct()
                } else {
                    snapshot.userRestoredGuidedSteps - step.kind.name
                }
                var updatedSnapshot = snapshot.copy(
                    userGuidedSystemSteps = updatedSteps,
                    userRestoredGuidedSteps = updatedRestored,
                )
                if (!step.channelId.isNullOrBlank() &&
                    updatedSnapshot.channelBaselines.none { it.channelId == step.channelId }
                ) {
                    val liveCh = liveChannels.firstOrNull { it.channelId == step.channelId }
                    updatedSnapshot = updatedSnapshot.copy(
                        channelBaselines = updatedSnapshot.channelBaselines + ChannelBaselineRecord(
                            channelId = step.channelId,
                            channelName = liveCh?.channelName ?: step.title,
                            wasBlockedBeforeRestriction = liveCh?.isBlockedInSystem ?: false,
                            baselineImportance = liveCh?.importance ?: 3,
                            isCallChannel = liveCh?.isCallChannel ?: false,
                            hadSound = liveCh?.hasSound ?: false,
                            hadVibration = liveCh?.hasVibration ?: false,
                        )
                    )
                }
                dao.upsertRestrictedAppState(
                    existing.copy(
                        snapshotJson = RestrictionStateCodec.encodeSnapshot(updatedSnapshot),
                        discoveredChannelsJson = RestrictionStateCodec.encodeChannels(liveChannels),
                    )
                )
            } else {
                if (step.isRestoreStep) return@withContext
                val rawBefore = inspector.inspectRawCapabilities(packageName, packageName)
                val baselines = liveChannels.map { ch ->
                    ChannelBaselineRecord(
                        channelId = ch.channelId,
                        channelName = ch.channelName,
                        wasBlockedBeforeRestriction = ch.isBlockedInSystem,
                        baselineImportance = ch.importance,
                        isCallChannel = ch.isCallChannel,
                        hadSound = ch.hasSound,
                        hadVibration = ch.hasVibration,
                    )
                }.toMutableList()
                if (!step.channelId.isNullOrBlank() && baselines.none { it.channelId == step.channelId }) {
                    baselines += ChannelBaselineRecord(
                        channelId = step.channelId,
                        channelName = step.title,
                        wasBlockedBeforeRestriction = false,
                        baselineImportance = 3,
                        isCallChannel = false,
                    )
                }
                val initialSnapshot = AppRestrictionSnapshot(
                    packageName = packageName,
                    capturedAtMillis = System.currentTimeMillis(),
                    hadPostNotificationsGranted = rawBefore.postNotificationsGranted,
                    hadOverlayAllowed = rawBefore.overlayAllowed,
                    hadFullScreenIntentAllowed = rawBefore.fullScreenIntentAllowed,
                    hadAppNotificationsActive = !bannedNow && (rawBefore.postNotificationsGranted != false),
                    wasSuspendedByDeviceOwner = rawBefore.isSuspendedByDeviceOwner,
                    userGuidedSystemSteps = listOf(step.kind.name),
                    channelBaselines = baselines,
                )
                dao.upsertRestrictedAppState(
                    RestrictedAppStateEntity(
                        packageName = packageName,
                        snapshotJson = RestrictionStateCodec.encodeSnapshot(initialSnapshot),
                        discoveredChannelsJson = RestrictionStateCodec.encodeChannels(liveChannels),
                        lastVerificationState = EnforcementVerificationState.UNRESTRICTED.name,
                        lastInspectedAtMillis = System.currentTimeMillis(),
                    )
                )
            }
        }

    suspend fun recordChannelModifiedWhileRestricted(
        packageName: String,
        channelInfo: DetectedChannelInfo,
    ) = withContext(Dispatchers.IO) {
        val existing = dao.getRestrictedAppState(packageName)
        val liveChannels = UniversalChannelTracker.getChannelsForPackage(packageName)
        val existingSnapshot = existing?.let { RestrictionStateCodec.decodeSnapshot(it.snapshotJson) }
        val baseSnapshot = existingSnapshot ?: run {
            val rawBefore = inspector.inspectRawCapabilities(packageName, packageName)
            AppRestrictionSnapshot(
                packageName = packageName,
                capturedAtMillis = System.currentTimeMillis(),
                hadPostNotificationsGranted = rawBefore.postNotificationsGranted,
                hadOverlayAllowed = rawBefore.overlayAllowed,
                hadFullScreenIntentAllowed = rawBefore.fullScreenIntentAllowed,
                hadAppNotificationsActive = !UniversalChannelTracker.isPackageNotificationsBanned(packageName) &&
                    (rawBefore.postNotificationsGranted != false),
                wasSuspendedByDeviceOwner = rawBefore.isSuspendedByDeviceOwner,
            )
        }
        val existingBaselines = baseSnapshot.channelBaselines
        val updatedBaselines = if (existingBaselines.any { it.channelId == channelInfo.channelId }) {
            existingBaselines
        } else {
            // If modified while restricted/setting up and now turned off/silenced, record baseline as active
            // so the user is required to restore it before ending restriction.
            val inferredBaselineImportance = if (channelInfo.isBlockedInSystem || channelInfo.importance < 3) {
                3
            } else {
                channelInfo.importance
            }
            existingBaselines + ChannelBaselineRecord(
                channelId = channelInfo.channelId,
                channelName = channelInfo.channelName,
                wasBlockedBeforeRestriction = false,
                baselineImportance = inferredBaselineImportance,
                isCallChannel = channelInfo.isCallChannel,
                hadSound = channelInfo.hasSound,
                hadVibration = channelInfo.hasVibration,
            )
        }
        val finalSnapshot = mergeChannelBaselines(
            baseSnapshot.copy(channelBaselines = updatedBaselines),
            liveChannels,
        )
        dao.upsertRestrictedAppState(
            RestrictedAppStateEntity(
                packageName = packageName,
                snapshotJson = RestrictionStateCodec.encodeSnapshot(finalSnapshot),
                discoveredChannelsJson = RestrictionStateCodec.encodeChannels(liveChannels),
                lastVerificationState = existing?.lastVerificationState
                    ?: EnforcementVerificationState.ACTIVE_WITH_SYSTEM_GAPS.name,
                lastInspectedAtMillis = System.currentTimeMillis(),
            )
        )
    }

    suspend fun inspectSingleApp(
        packageName: String,
        appName: String,
        isRestricted: Boolean,
        silentAndHide: Boolean,
        integrity: SystemIntegrityStatus,
    ): PackageInspectionReport = withContext(Dispatchers.IO) {
        val stateEntity = dao.getRestrictedAppState(packageName)
        val snapshot = stateEntity?.let { RestrictionStateCodec.decodeSnapshot(it.snapshotJson) }
        val persistedChannels = stateEntity?.let {
            RestrictionStateCodec.decodeChannels(it.discoveredChannelsJson)
        }.orEmpty()
        UniversalChannelTracker.seedFromPersisted(packageName, persistedChannels)
        VaultNotificationListenerService.refreshChannelsForPackage(packageName)
        val liveChannels = UniversalChannelTracker.getChannelsForPackage(packageName)
        val raw = inspector.inspectRawCapabilities(packageName, appName)
        DeepRestrictionEvaluator.evaluateReport(
            capabilities = raw,
            isRestricted = isRestricted,
            silentAndHide = silentAndHide,
            discoveredChannels = liveChannels,
            snapshot = snapshot,
            integrity = integrity,
            supportsPostNotificationsRuntime = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            isAppNotificationsBannedByOs = UniversalChannelTracker.isPackageNotificationsBanned(packageName),
        )
    }

    suspend fun hasRestrictedAppState(packageName: String): Boolean = withContext(Dispatchers.IO) {
        dao.getRestrictedAppState(packageName) != null
    }

    suspend fun inspectAllKnownApps(
        apps: List<Pair<String, String>>,
        integrity: SystemIntegrityStatus,
    ): Map<String, PackageInspectionReport> = withContext(Dispatchers.IO) {
        val rulesMap = dao.getLimitedAppsOnce().associateBy { it.packageName }
        val stateMap = dao.getRestrictedAppStatesOnce().associateBy { it.packageName }
        val reports = LinkedHashMap<String, PackageInspectionReport>(apps.size)

        for ((pkg, name) in apps) {
            val rule = rulesMap[pkg]
            val isRestricted = rule?.isLimited == true
            val stateEntity = stateMap[pkg]
            if (!isRestricted && stateEntity == null) continue

            val silentAndHide = (rule?.notificationPrivacyEnum ?: NotificationPrivacyMode.SILENT_AND_HIDE) ==
                NotificationPrivacyMode.SILENT_AND_HIDE
            var snapshot = stateEntity?.let { RestrictionStateCodec.decodeSnapshot(it.snapshotJson) }
            val persistedChannels = stateEntity?.let {
                RestrictionStateCodec.decodeChannels(it.discoveredChannelsJson)
            }.orEmpty()
            UniversalChannelTracker.seedFromPersisted(pkg, persistedChannels)
            val liveChannels = UniversalChannelTracker.getChannelsForPackage(pkg)

            if (isRestricted && snapshot != null) {
                snapshot = mergeChannelBaselines(snapshot, liveChannels)
            }

            val raw = inspector.inspectRawCapabilities(pkg, name)
            val report = DeepRestrictionEvaluator.evaluateReport(
                capabilities = raw,
                isRestricted = isRestricted,
                silentAndHide = silentAndHide,
                discoveredChannels = liveChannels,
                snapshot = snapshot,
                integrity = integrity,
                supportsPostNotificationsRuntime = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                isAppNotificationsBannedByOs = UniversalChannelTracker.isPackageNotificationsBanned(pkg),
            )
            reports[pkg] = report

            if (isRestricted || report.restoreSteps.isNotEmpty()) {
                val encodedChannels = RestrictionStateCodec.encodeChannels(liveChannels)
                val encodedSnapshot = snapshot?.let { RestrictionStateCodec.encodeSnapshot(it) }.orEmpty()
                if (stateEntity == null ||
                    stateEntity.snapshotJson != encodedSnapshot ||
                    stateEntity.discoveredChannelsJson != encodedChannels ||
                    stateEntity.lastVerificationState != report.verificationState.name
                ) {
                    dao.upsertRestrictedAppState(
                        RestrictedAppStateEntity(
                            packageName = pkg,
                            snapshotJson = encodedSnapshot,
                            discoveredChannelsJson = encodedChannels,
                            lastVerificationState = report.verificationState.name,
                            lastInspectedAtMillis = System.currentTimeMillis(),
                        )
                    )
                }
            } else if (stateEntity != null && AppGuardRuntimeCache.activeSetupPackage != pkg) {
                dao.deleteRestrictedAppState(pkg)
            }
        }
        reports
    }

    private fun mergeChannelBaselines(
        snapshot: AppRestrictionSnapshot,
        liveChannels: List<DetectedChannelInfo>,
    ): AppRestrictionSnapshot {
        if (liveChannels.isEmpty()) return snapshot
        val existingIds = snapshot.channelBaselines.map { it.channelId }.toHashSet()
        val newlySeenActive = liveChannels.filter { ch ->
            ch.channelId !in existingIds && !ch.isBlockedInSystem
        }.map { ch ->
            ChannelBaselineRecord(
                channelId = ch.channelId,
                channelName = ch.channelName,
                wasBlockedBeforeRestriction = false,
                baselineImportance = ch.importance,
                isCallChannel = ch.isCallChannel,
                hadSound = ch.hasSound,
                hadVibration = ch.hasVibration,
            )
        }
        if (newlySeenActive.isEmpty()) return snapshot
        return snapshot.copy(channelBaselines = snapshot.channelBaselines + newlySeenActive)
    }

    private suspend fun inspectAndPersistState(
        packageName: String,
        fallbackAppName: String,
        isRestricted: Boolean,
        silentAndHide: Boolean,
        integrity: SystemIntegrityStatus,
        overrideSnapshot: AppRestrictionSnapshot?,
    ): PackageInspectionReport {
        val existing = dao.getRestrictedAppState(packageName)
        val persistedChannels = existing?.let {
            RestrictionStateCodec.decodeChannels(it.discoveredChannelsJson)
        }.orEmpty()
        UniversalChannelTracker.seedFromPersisted(packageName, persistedChannels)
        VaultNotificationListenerService.refreshChannelsForPackage(packageName)
        val liveChannels = UniversalChannelTracker.getChannelsForPackage(packageName)

        val raw = inspector.inspectRawCapabilities(packageName, fallbackAppName)
        val rawSnapshot = overrideSnapshot ?: existing?.let { RestrictionStateCodec.decodeSnapshot(it.snapshotJson) }
        val snapshot = if (isRestricted && rawSnapshot != null) {
            mergeChannelBaselines(rawSnapshot, liveChannels)
        } else {
            rawSnapshot
        }
        val report = DeepRestrictionEvaluator.evaluateReport(
            capabilities = raw,
            isRestricted = isRestricted,
            silentAndHide = silentAndHide,
            discoveredChannels = liveChannels,
            snapshot = snapshot,
            integrity = integrity,
            supportsPostNotificationsRuntime = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            isAppNotificationsBannedByOs = UniversalChannelTracker.isPackageNotificationsBanned(packageName),
        )

        dao.upsertRestrictedAppState(
            RestrictedAppStateEntity(
                packageName = packageName,
                snapshotJson = snapshot?.let { RestrictionStateCodec.encodeSnapshot(it) }.orEmpty(),
                discoveredChannelsJson = RestrictionStateCodec.encodeChannels(liveChannels),
                lastVerificationState = report.verificationState.name,
                lastInspectedAtMillis = System.currentTimeMillis(),
            )
        )
        return report
    }

    fun buildSystemSettingsIntent(step: SystemRestrictionStep): Intent {
        return buildSystemSettingsIntentChain(step).first()
    }

    fun buildSystemSettingsIntentChain(step: SystemRestrictionStep): List<Intent> {
        val pkg = step.targetPackage
        val pkgUri = Uri.fromParts("package", pkg, null)
        val appUid = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    pkg,
                    android.content.pm.PackageManager.ApplicationInfoFlags.of(0L),
                ).uid
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(pkg, 0).uid
            }
        }.getOrNull()

        fun appNotificationSettingsIntent(): Intent {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                    putExtra("app_package", pkg)
                    if (appUid != null) putExtra("app_uid", appUid)
                }
            } else {
                Intent("android.settings.APP_NOTIFICATION_SETTINGS").apply {
                    putExtra("app_package", pkg)
                    if (appUid != null) putExtra("app_uid", appUid)
                }
            }
        }

        fun appDetailsIntent(): Intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)

        val candidates = when (step.kind) {
            SystemStepKind.APP_NOTIFICATIONS -> listOf(
                appNotificationSettingsIntent(),
                appDetailsIntent(),
            )
            SystemStepKind.CHANNEL_ALERTS -> buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !step.channelId.isNullOrBlank()) {
                    add(
                        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                            putExtra("app_package", pkg)
                            putExtra(Settings.EXTRA_CHANNEL_ID, step.channelId)
                            if (appUid != null) putExtra("app_uid", appUid)
                        }
                    )
                }
                add(appNotificationSettingsIntent())
                add(appDetailsIntent())
            }
            SystemStepKind.FULL_SCREEN_INTENT -> buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    add(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri))
                    add(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT))
                }
                add(appNotificationSettingsIntent())
                add(appDetailsIntent())
            }
            SystemStepKind.OVERLAY -> listOf(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri),
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                appDetailsIntent(),
            )
            SystemStepKind.APP_DETAILS -> listOf(appDetailsIntent())
        }

        val pm = context.packageManager
        val resolved = candidates.filter { intent ->
            runCatching { intent.resolveActivity(pm) != null }.getOrDefault(false)
        }
        val finalChain = if (resolved.isNotEmpty()) resolved else candidates
        return finalChain.map { intent ->
            intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }
    }
}
