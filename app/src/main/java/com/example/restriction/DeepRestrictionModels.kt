package com.example.restriction

import org.json.JSONArray
import org.json.JSONObject

enum class EnforcementVerificationState(val shortLabel: String) {
    UNRESTRICTED("Off"),
    FULLY_ENFORCED("Enforced"),
    ACTIVE_WITH_SYSTEM_GAPS("Partial"),
    DEGRADED_PERMISSION_MISSING("Paused"),
}

enum class SystemStepKind {
    APP_NOTIFICATIONS,
    CHANNEL_ALERTS,
    FULL_SCREEN_INTENT,
    OVERLAY,
    APP_DETAILS,
}

data class DetectedChannelInfo(
    val channelId: String,
    val channelName: String,
    val importance: Int,
    val isCallChannel: Boolean,
    val hasSound: Boolean,
    val hasVibration: Boolean,
    val canBypassDnd: Boolean,
    val isBlockedInSystem: Boolean,
    val discoveredAtMillis: Long,
)

data class SystemRestrictionStep(
    val kind: SystemStepKind,
    val title: String,
    val targetPackage: String,
    val channelId: String? = null,
    val isRestoreStep: Boolean = false,
)

data class ChannelBaselineRecord(
    val channelId: String,
    val channelName: String,
    val wasBlockedBeforeRestriction: Boolean = false,
    val baselineImportance: Int = 3,
    val isCallChannel: Boolean = false,
    val hadSound: Boolean = false,
    val hadVibration: Boolean = false,
)

data class AppRestrictionSnapshot(
    val packageName: String,
    val capturedAtMillis: Long,
    val hadPostNotificationsGranted: Boolean? = null,
    val hadOverlayAllowed: Boolean? = null,
    val hadFullScreenIntentAllowed: Boolean? = null,
    val hadAppNotificationsActive: Boolean = false,
    val wasSuspendedByDeviceOwner: Boolean = false,
    val deviceOwnerDeniedPostNotifications: Boolean = false,
    val deviceOwnerSuspendedPackage: Boolean = false,
    val userGuidedSystemSteps: List<String> = emptyList(),
    val userRestoredGuidedSteps: List<String> = emptyList(),
    val wasRestrictedApplied: Boolean = false,
    val channelBaselines: List<ChannelBaselineRecord> = emptyList(),
)

data class RawPackageCapabilities(
    val packageName: String,
    val appName: String,
    val requestsPostNotifications: Boolean,
    val postNotificationsGranted: Boolean?,
    val requestsSystemAlertWindow: Boolean,
    val overlayAllowed: Boolean?,
    val requestsFullScreenIntent: Boolean,
    val fullScreenIntentAllowed: Boolean?,
    val requestsVibrate: Boolean,
    val requestsForegroundService: Boolean,
    val requestsCallCapabilities: Boolean,
    val isDeviceOwnerAvailable: Boolean,
    val isSuspendedByDeviceOwner: Boolean,
)

data class PackageInspectionReport(
    val packageName: String,
    val appName: String,
    val isRestricted: Boolean,
    val requestsPostNotifications: Boolean,
    val postNotificationsGranted: Boolean?,
    val requestsSystemAlertWindow: Boolean,
    val overlayAllowed: Boolean?,
    val requestsFullScreenIntent: Boolean,
    val fullScreenIntentAllowed: Boolean?,
    val requestsVibrate: Boolean,
    val requestsForegroundService: Boolean,
    val requestsCallCapabilities: Boolean,
    val isDeviceOwnerAvailable: Boolean,
    val isSuspendedByDeviceOwner: Boolean,
    val discoveredChannels: List<DetectedChannelInfo>,
    val callChannelsCount: Int,
    val unblockedChannelsCount: Int,
    val isAppNotificationsBannedByOs: Boolean = false,
    val verificationState: EnforcementVerificationState,
    val missingCorePermissions: List<String>,
    val pendingSystemSteps: List<SystemRestrictionStep>,
    val restoreSteps: List<SystemRestrictionStep>,
) {
    val activeVectorsCount: Int
        get() {
            var count = 0
            if (postNotificationsGranted == true) count += 1
            if (unblockedChannelsCount > 0) count += 1
            if (overlayAllowed == true) count += 1
            if (fullScreenIntentAllowed == true) count += 1
            return count
        }

    val canApplyRestriction: Boolean
        get() = missingCorePermissions.isEmpty() && (pendingSystemSteps.isEmpty() || isSuspendedByDeviceOwner || isDeviceOwnerAvailable)

    val statusBadgeText: String
        get() = when (verificationState) {
            EnforcementVerificationState.UNRESTRICTED -> "Off"
            EnforcementVerificationState.FULLY_ENFORCED -> "Enforced"
            EnforcementVerificationState.ACTIVE_WITH_SYSTEM_GAPS ->
                if (pendingSystemSteps.isEmpty()) "Listener Active" else "${pendingSystemSteps.size} System"
            EnforcementVerificationState.DEGRADED_PERMISSION_MISSING -> "Paused"
        }
}

data class SystemIntegrityStatus(
    val isAccessibilityEnabled: Boolean = false,
    val isAccessibilityServiceBound: Boolean = false,
    val isNotificationListenerEnabled: Boolean = false,
    val isNotificationListenerBound: Boolean = false,
    val isDeviceAdminEnabled: Boolean = false,
    val isCameraPermissionGranted: Boolean = false,
    val isPostNotificationsGranted: Boolean = true,
    val isDeviceOwnerActive: Boolean = false,
    val guardActive: Boolean = true,
    val notificationBlockEnabled: Boolean = true,
    val uninstallProtectionEnabled: Boolean = true,
    val capturePhotoOnOpenEnabled: Boolean = true,
    val requiresPostNotificationsForMaskedAlerts: Boolean = false,
) {
    val missingRequirements: List<SystemRequirementIssue>
        get() = buildList {
            if (!isAccessibilityEnabled) {
                add(SystemRequirementIssue.ACCESSIBILITY)
            }
            if (notificationBlockEnabled && !isNotificationListenerEnabled) {
                add(SystemRequirementIssue.NOTIFICATION_LISTENER)
            }
            if (uninstallProtectionEnabled && !isDeviceAdminEnabled) {
                add(SystemRequirementIssue.DEVICE_ADMIN)
            }
            if (capturePhotoOnOpenEnabled && !isCameraPermissionGranted) {
                add(SystemRequirementIssue.CAMERA)
            }
            if (requiresPostNotificationsForMaskedAlerts && !isPostNotificationsGranted) {
                add(SystemRequirementIssue.POST_NOTIFICATIONS)
            }
        }

    val isIntegrityConfirmed: Boolean
        get() = missingRequirements.isEmpty()

    val isEnforcementOperational: Boolean
        get() = guardActive && isAccessibilityEnabled
}

enum class SystemRequirementIssue(val label: String) {
    ACCESSIBILITY("App Limiter"),
    NOTIFICATION_LISTENER("Notification Access"),
    DEVICE_ADMIN("Device Admin"),
    CAMERA("Camera"),
    POST_NOTIFICATIONS("Post Notifications"),
}

object DeepRestrictionEvaluator {

    fun evaluateReport(
        capabilities: RawPackageCapabilities,
        isRestricted: Boolean,
        silentAndHide: Boolean,
        discoveredChannels: List<DetectedChannelInfo>,
        snapshot: AppRestrictionSnapshot?,
        integrity: SystemIntegrityStatus,
        supportsPostNotificationsRuntime: Boolean,
        isAppNotificationsBannedByOs: Boolean = false,
    ): PackageInspectionReport {
        val callChannels = discoveredChannels.filter { it.isCallChannel }
        val unblockedChannels = discoveredChannels.filter { !it.isBlockedInSystem }
        val unblockedCallOrAlertChannels = unblockedChannels.sortedWith(
            compareByDescending<DetectedChannelInfo> { it.isCallChannel }
                .thenByDescending { UniversalChannelTracker.isMessageRelatedChannelMetadata(it.channelId, it.channelName) }
                .thenByDescending { it.importance }
                .thenByDescending { it.hasSound || it.hasVibration }
        )

        val missingCore = buildList {
            if (!integrity.isAccessibilityEnabled) add("App Limiter")
            if (!integrity.isNotificationListenerEnabled) {
                add("Notification Access")
            }
            if (!integrity.isCameraPermissionGranted) {
                add("Camera")
            }
            if (!silentAndHide && !integrity.isPostNotificationsGranted) {
                add("Post Notifications")
            }
        }.distinct()

        if (!isRestricted) {
            val restoreSteps = buildRestoreSteps(
                capabilities = capabilities,
                snapshot = snapshot,
                discoveredChannels = discoveredChannels,
                isAppNotificationsBannedByOs = isAppNotificationsBannedByOs,
            )
            return PackageInspectionReport(
                packageName = capabilities.packageName,
                appName = capabilities.appName,
                isRestricted = false,
                requestsPostNotifications = capabilities.requestsPostNotifications,
                postNotificationsGranted = capabilities.postNotificationsGranted,
                requestsSystemAlertWindow = capabilities.requestsSystemAlertWindow,
                overlayAllowed = capabilities.overlayAllowed,
                requestsFullScreenIntent = capabilities.requestsFullScreenIntent,
                fullScreenIntentAllowed = capabilities.fullScreenIntentAllowed,
                requestsVibrate = capabilities.requestsVibrate,
                requestsForegroundService = capabilities.requestsForegroundService,
                requestsCallCapabilities = capabilities.requestsCallCapabilities,
                isDeviceOwnerAvailable = capabilities.isDeviceOwnerAvailable,
                isSuspendedByDeviceOwner = capabilities.isSuspendedByDeviceOwner,
                discoveredChannels = discoveredChannels,
                callChannelsCount = callChannels.size,
                unblockedChannelsCount = unblockedChannels.size,
                isAppNotificationsBannedByOs = isAppNotificationsBannedByOs,
                verificationState = EnforcementVerificationState.UNRESTRICTED,
                missingCorePermissions = missingCore,
                pendingSystemSteps = emptyList(),
                restoreSteps = restoreSteps,
            )
        }

        val pendingSteps = if (capabilities.isSuspendedByDeviceOwner) {
            emptyList()
        } else {
            buildPendingRestrictionSteps(
                capabilities = capabilities,
                silentAndHide = silentAndHide,
                discoveredChannels = discoveredChannels,
                unblockedChannels = unblockedCallOrAlertChannels,
                snapshot = snapshot,
                supportsPostNotificationsRuntime = supportsPostNotificationsRuntime,
                isAppNotificationsBannedByOs = isAppNotificationsBannedByOs,
            )
        }
        val restoreSteps = buildRestoreSteps(
            capabilities = capabilities,
            snapshot = snapshot,
            discoveredChannels = discoveredChannels,
            isAppNotificationsBannedByOs = isAppNotificationsBannedByOs,
        )

        val state = when {
            !integrity.guardActive -> EnforcementVerificationState.DEGRADED_PERMISSION_MISSING
            missingCore.isNotEmpty() -> EnforcementVerificationState.DEGRADED_PERMISSION_MISSING
            capabilities.isSuspendedByDeviceOwner -> EnforcementVerificationState.FULLY_ENFORCED
            pendingSteps.isEmpty() -> EnforcementVerificationState.FULLY_ENFORCED
            else -> EnforcementVerificationState.ACTIVE_WITH_SYSTEM_GAPS
        }

        return PackageInspectionReport(
            packageName = capabilities.packageName,
            appName = capabilities.appName,
            isRestricted = true,
            requestsPostNotifications = capabilities.requestsPostNotifications,
            postNotificationsGranted = capabilities.postNotificationsGranted,
            requestsSystemAlertWindow = capabilities.requestsSystemAlertWindow,
            overlayAllowed = capabilities.overlayAllowed,
            requestsFullScreenIntent = capabilities.requestsFullScreenIntent,
            fullScreenIntentAllowed = capabilities.fullScreenIntentAllowed,
            requestsVibrate = capabilities.requestsVibrate,
            requestsForegroundService = capabilities.requestsForegroundService,
            requestsCallCapabilities = capabilities.requestsCallCapabilities,
            isDeviceOwnerAvailable = capabilities.isDeviceOwnerAvailable,
            isSuspendedByDeviceOwner = capabilities.isSuspendedByDeviceOwner,
            discoveredChannels = discoveredChannels,
            callChannelsCount = callChannels.size,
            unblockedChannelsCount = unblockedChannels.size,
            isAppNotificationsBannedByOs = isAppNotificationsBannedByOs,
            verificationState = state,
            missingCorePermissions = missingCore,
            pendingSystemSteps = pendingSteps,
            restoreSteps = restoreSteps,
        )
    }

    private fun buildPendingRestrictionSteps(
        capabilities: RawPackageCapabilities,
        silentAndHide: Boolean,
        discoveredChannels: List<DetectedChannelInfo>,
        unblockedChannels: List<DetectedChannelInfo>,
        snapshot: AppRestrictionSnapshot?,
        supportsPostNotificationsRuntime: Boolean,
        isAppNotificationsBannedByOs: Boolean,
    ): List<SystemRestrictionStep> {
        val steps = mutableListOf<SystemRestrictionStep>()
        val guidedSteps = snapshot?.userGuidedSystemSteps.orEmpty()
        val appNotificationsConfirmedBlocked = isAppNotificationsBannedByOs ||
            (supportsPostNotificationsRuntime && capabilities.postNotificationsGranted == false)

        val notificationsStillEnabled = when {
            appNotificationsConfirmedBlocked -> false
            supportsPostNotificationsRuntime && capabilities.postNotificationsGranted == true -> true
            discoveredChannels.isNotEmpty() -> unblockedChannels.isNotEmpty()
            else -> SystemStepKind.APP_NOTIFICATIONS.name !in guidedSteps
        }

        if (silentAndHide && notificationsStillEnabled) {
            steps += SystemRestrictionStep(
                kind = SystemStepKind.APP_NOTIFICATIONS,
                title = "Block App Notifications",
                targetPackage = capabilities.packageName,
            )
        }

        // Only require individual channel steps when app-level notifications aren't already blocked.
        // In Show/Hide Content mode, only require silencing incoming Call channels so masked messages can still arrive.
        if (!appNotificationsConfirmedBlocked) {
            unblockedChannels.take(8).forEach { channel ->
                val isMessage = UniversalChannelTracker.isMessageRelatedChannelMetadata(
                    channel.channelId,
                    channel.channelName,
                )
                val shouldSurfaceChannel = if (silentAndHide) {
                    true
                } else {
                    channel.isCallChannel
                }
                if (shouldSurfaceChannel) {
                    val labelName = channel.channelName.ifBlank { channel.channelId }
                    val stepTitle = when {
                        channel.isCallChannel -> "Silence Call Channel: $labelName"
                        isMessage -> "Mute Message Channel: $labelName"
                        else -> "Mute Channel: $labelName"
                    }
                    steps += SystemRestrictionStep(
                        kind = SystemStepKind.CHANNEL_ALERTS,
                        title = stepTitle,
                        targetPackage = capabilities.packageName,
                        channelId = channel.channelId,
                    )
                }
            }
        }

        val fsiNeedsStep = capabilities.requestsFullScreenIntent && (
            capabilities.fullScreenIntentAllowed == true ||
                (capabilities.fullScreenIntentAllowed == null &&
                    SystemStepKind.FULL_SCREEN_INTENT.name !in guidedSteps)
            )
        if (fsiNeedsStep) {
            steps += SystemRestrictionStep(
                kind = SystemStepKind.FULL_SCREEN_INTENT,
                title = if (capabilities.fullScreenIntentAllowed == true) {
                    "Deny Full-Screen Alerts"
                } else {
                    "Check Full-Screen Alerts"
                },
                targetPackage = capabilities.packageName,
            )
        }

        val overlayNeedsStep = capabilities.requestsSystemAlertWindow && (
            capabilities.overlayAllowed == true ||
                (capabilities.overlayAllowed == null &&
                    SystemStepKind.OVERLAY.name !in guidedSteps)
            )
        if (overlayNeedsStep) {
            steps += SystemRestrictionStep(
                kind = SystemStepKind.OVERLAY,
                title = if (capabilities.overlayAllowed == true) {
                    "Revoke Overlay Display"
                } else {
                    "Check Overlay Display"
                },
                targetPackage = capabilities.packageName,
            )
        }

        return steps
    }

    private fun buildRestoreSteps(
        capabilities: RawPackageCapabilities,
        snapshot: AppRestrictionSnapshot?,
        discoveredChannels: List<DetectedChannelInfo>,
        isAppNotificationsBannedByOs: Boolean,
    ): List<SystemRestrictionStep> {
        if (snapshot == null) return emptyList()
        val steps = mutableListOf<SystemRestrictionStep>()
        val restoredGuided = snapshot.userRestoredGuidedSteps
        val checkGuidedRestore = snapshot.wasRestrictedApplied

        val runtimePostRevoked = capabilities.postNotificationsGranted == false && (
            snapshot.hadPostNotificationsGranted == true ||
                (snapshot.hadPostNotificationsGranted == null &&
                    snapshot.userGuidedSystemSteps.contains(SystemStepKind.APP_NOTIFICATIONS.name))
            )
        val osNotificationsBanned = snapshot.hadAppNotificationsActive && isAppNotificationsBannedByOs
        val unverifiedNotifsGuided = checkGuidedRestore &&
            capabilities.postNotificationsGranted == null &&
            !isAppNotificationsBannedByOs &&
            snapshot.userGuidedSystemSteps.contains(SystemStepKind.APP_NOTIFICATIONS.name) &&
            !restoredGuided.contains(SystemStepKind.APP_NOTIFICATIONS.name)

        if (runtimePostRevoked || osNotificationsBanned || unverifiedNotifsGuided) {
            steps += SystemRestrictionStep(
                kind = SystemStepKind.APP_NOTIFICATIONS,
                title = "Restore App Notifications",
                targetPackage = capabilities.packageName,
                isRestoreStep = true,
            )
        }

        val baselineMap = snapshot.channelBaselines.associateBy { it.channelId }
        val discoveredMap = discoveredChannels.associateBy { it.channelId }

        val modifiedChannels = discoveredChannels.filter { ch ->
            val baseline = baselineMap[ch.channelId] ?: return@filter false
            if (baseline.wasBlockedBeforeRestriction) return@filter false
            val importanceDropped = ch.isBlockedInSystem || (baseline.baselineImportance >= 3 && ch.importance in 1..2)
            val soundTurnedOff = baseline.hadSound && !ch.hasSound
            val vibrationTurnedOff = baseline.hadVibration && !ch.hasVibration
            importanceDropped || soundTurnedOff || vibrationTurnedOff
        }

        modifiedChannels.sortedWith(
            compareByDescending<DetectedChannelInfo> { it.isCallChannel }
                .thenByDescending { UniversalChannelTracker.isMessageRelatedChannelMetadata(it.channelId, it.channelName) }
                .thenBy { it.channelName.lowercase() }
        ).forEach { ch ->
            val label = ch.channelName.ifBlank { ch.channelId }
            val isMessage = UniversalChannelTracker.isMessageRelatedChannelMetadata(ch.channelId, ch.channelName)
            val title = when {
                ch.isCallChannel -> "Restore Call Channel: $label"
                isMessage -> "Restore Message Alerts: $label"
                else -> "Restore Channel: $label"
            }
            steps += SystemRestrictionStep(
                kind = SystemStepKind.CHANNEL_ALERTS,
                title = title,
                targetPackage = capabilities.packageName,
                channelId = ch.channelId,
                isRestoreStep = true,
            )
        }

        // Fallback when legacy snapshot tracked CHANNEL_ALERTS without individual channel baselines
        if (modifiedChannels.isEmpty() &&
            snapshot.channelBaselines.isEmpty() &&
            snapshot.userGuidedSystemSteps.contains(SystemStepKind.CHANNEL_ALERTS.name)
        ) {
            val blockedCh = discoveredChannels.firstOrNull { it.isBlockedInSystem }
            if (blockedCh != null) {
                steps += SystemRestrictionStep(
                    kind = SystemStepKind.CHANNEL_ALERTS,
                    title = "Restore Channel: ${blockedCh.channelName.ifBlank { blockedCh.channelId }}",
                    targetPackage = capabilities.packageName,
                    channelId = blockedCh.channelId,
                    isRestoreStep = true,
                )
            } else if (discoveredMap.isNotEmpty()) {
                // Only require restore if a channel is still blocked
            }
        }

        val verifiedFsiNeedsRestore = snapshot.hadFullScreenIntentAllowed == true &&
            capabilities.fullScreenIntentAllowed == false &&
            capabilities.requestsFullScreenIntent
        val guidedFsiNeedsRestore = checkGuidedRestore &&
            capabilities.fullScreenIntentAllowed == null &&
            capabilities.requestsFullScreenIntent &&
            snapshot.userGuidedSystemSteps.contains(SystemStepKind.FULL_SCREEN_INTENT.name) &&
            !restoredGuided.contains(SystemStepKind.FULL_SCREEN_INTENT.name)

        if (verifiedFsiNeedsRestore || guidedFsiNeedsRestore) {
            steps += SystemRestrictionStep(
                kind = SystemStepKind.FULL_SCREEN_INTENT,
                title = "Restore Full-Screen Alerts",
                targetPackage = capabilities.packageName,
                isRestoreStep = true,
            )
        }

        val verifiedOverlayNeedsRestore = snapshot.hadOverlayAllowed == true &&
            capabilities.overlayAllowed == false &&
            capabilities.requestsSystemAlertWindow
        val guidedOverlayNeedsRestore = checkGuidedRestore &&
            capabilities.overlayAllowed == null &&
            capabilities.requestsSystemAlertWindow &&
            snapshot.userGuidedSystemSteps.contains(SystemStepKind.OVERLAY.name) &&
            !restoredGuided.contains(SystemStepKind.OVERLAY.name)

        if (verifiedOverlayNeedsRestore || guidedOverlayNeedsRestore) {
            steps += SystemRestrictionStep(
                kind = SystemStepKind.OVERLAY,
                title = "Restore Overlay Display",
                targetPackage = capabilities.packageName,
                isRestoreStep = true,
            )
        }

        return steps
    }
}

object RestrictionStateCodec {

    fun encodeSnapshot(snapshot: AppRestrictionSnapshot): String {
        val obj = JSONObject()
        obj.put("packageName", snapshot.packageName)
        obj.put("capturedAtMillis", snapshot.capturedAtMillis)
        if (snapshot.hadPostNotificationsGranted != null) {
            obj.put("hadPostNotificationsGranted", snapshot.hadPostNotificationsGranted)
        }
        if (snapshot.hadOverlayAllowed != null) {
            obj.put("hadOverlayAllowed", snapshot.hadOverlayAllowed)
        }
        if (snapshot.hadFullScreenIntentAllowed != null) {
            obj.put("hadFullScreenIntentAllowed", snapshot.hadFullScreenIntentAllowed)
        }
        obj.put("hadAppNotificationsActive", snapshot.hadAppNotificationsActive)
        obj.put("wasSuspendedByDeviceOwner", snapshot.wasSuspendedByDeviceOwner)
        obj.put("deviceOwnerDeniedPostNotifications", snapshot.deviceOwnerDeniedPostNotifications)
        obj.put("deviceOwnerSuspendedPackage", snapshot.deviceOwnerSuspendedPackage)
        val stepsArr = JSONArray()
        snapshot.userGuidedSystemSteps.forEach { stepsArr.put(it) }
        obj.put("userGuidedSystemSteps", stepsArr)
        val restoredStepsArr = JSONArray()
        snapshot.userRestoredGuidedSteps.forEach { restoredStepsArr.put(it) }
        obj.put("userRestoredGuidedSteps", restoredStepsArr)
        obj.put("wasRestrictedApplied", snapshot.wasRestrictedApplied)
        val baselinesArr = JSONArray()
        snapshot.channelBaselines.forEach { ch ->
            baselinesArr.put(
                JSONObject().apply {
                    put("channelId", ch.channelId)
                    put("channelName", ch.channelName)
                    put("wasBlockedBeforeRestriction", ch.wasBlockedBeforeRestriction)
                    put("baselineImportance", ch.baselineImportance)
                    put("isCallChannel", ch.isCallChannel)
                    put("hadSound", ch.hadSound)
                    put("hadVibration", ch.hadVibration)
                }
            )
        }
        obj.put("channelBaselines", baselinesArr)
        return obj.toString()
    }

    fun decodeSnapshot(raw: String): AppRestrictionSnapshot? {
        if (raw.isBlank()) return null
        return runCatching {
            val obj = JSONObject(raw)
            val stepsArr = obj.optJSONArray("userGuidedSystemSteps")
            val steps = buildList {
                if (stepsArr != null) {
                    for (i in 0 until stepsArr.length()) {
                        stepsArr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }
            val restoredArr = obj.optJSONArray("userRestoredGuidedSteps")
            val restoredSteps = buildList {
                if (restoredArr != null) {
                    for (i in 0 until restoredArr.length()) {
                        restoredArr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }
            val baselinesArr = obj.optJSONArray("channelBaselines")
            val baselines = buildList {
                if (baselinesArr != null) {
                    for (i in 0 until baselinesArr.length()) {
                        val bObj = baselinesArr.optJSONObject(i) ?: continue
                        val id = bObj.optString("channelId")
                        if (id.isBlank()) continue
                        add(
                            ChannelBaselineRecord(
                                channelId = id,
                                channelName = bObj.optString("channelName"),
                                wasBlockedBeforeRestriction = bObj.optBoolean("wasBlockedBeforeRestriction", false),
                                baselineImportance = bObj.optInt("baselineImportance", 3),
                                isCallChannel = bObj.optBoolean("isCallChannel", false),
                                hadSound = bObj.optBoolean("hadSound", false),
                                hadVibration = bObj.optBoolean("hadVibration", false),
                            )
                        )
                    }
                }
            }
            AppRestrictionSnapshot(
                packageName = obj.getString("packageName"),
                capturedAtMillis = obj.optLong("capturedAtMillis", 0L),
                hadPostNotificationsGranted = if (obj.has("hadPostNotificationsGranted")) {
                    obj.getBoolean("hadPostNotificationsGranted")
                } else {
                    null
                },
                hadOverlayAllowed = if (obj.has("hadOverlayAllowed")) {
                    obj.getBoolean("hadOverlayAllowed")
                } else {
                    null
                },
                hadFullScreenIntentAllowed = if (obj.has("hadFullScreenIntentAllowed")) {
                    obj.getBoolean("hadFullScreenIntentAllowed")
                } else {
                    null
                },
                hadAppNotificationsActive = obj.optBoolean("hadAppNotificationsActive", false),
                wasSuspendedByDeviceOwner = obj.optBoolean("wasSuspendedByDeviceOwner", false),
                deviceOwnerDeniedPostNotifications = obj.optBoolean("deviceOwnerDeniedPostNotifications", false),
                deviceOwnerSuspendedPackage = obj.optBoolean("deviceOwnerSuspendedPackage", false),
                userGuidedSystemSteps = steps,
                userRestoredGuidedSteps = restoredSteps,
                wasRestrictedApplied = obj.optBoolean("wasRestrictedApplied", false),
                channelBaselines = baselines,
            )
        }.getOrNull()
    }

    fun encodeChannels(channels: List<DetectedChannelInfo>): String {
        val arr = JSONArray()
        channels.forEach { ch ->
            arr.put(
                JSONObject().apply {
                    put("channelId", ch.channelId)
                    put("channelName", ch.channelName)
                    put("importance", ch.importance)
                    put("isCallChannel", ch.isCallChannel)
                    put("hasSound", ch.hasSound)
                    put("hasVibration", ch.hasVibration)
                    put("canBypassDnd", ch.canBypassDnd)
                    put("isBlockedInSystem", ch.isBlockedInSystem)
                    put("discoveredAtMillis", ch.discoveredAtMillis)
                }
            )
        }
        return arr.toString()
    }

    fun decodeChannels(raw: String): List<DetectedChannelInfo> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("channelId")
                    if (id.isBlank()) continue
                    add(
                        DetectedChannelInfo(
                            channelId = id,
                            channelName = obj.optString("channelName"),
                            importance = obj.optInt("importance", 3),
                            isCallChannel = obj.optBoolean("isCallChannel", false),
                            hasSound = obj.optBoolean("hasSound", false),
                            hasVibration = obj.optBoolean("hasVibration", false),
                            canBypassDnd = obj.optBoolean("canBypassDnd", false),
                            isBlockedInSystem = obj.optBoolean("isBlockedInSystem", false),
                            discoveredAtMillis = obj.optLong("discoveredAtMillis", 0L),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
