package com.example

import android.app.Notification
import com.example.data.AppGuardRuntimeCache
import com.example.data.entity.AdminConfigEntity
import com.example.data.entity.AppGuardMode
import com.example.data.entity.LimitedAppEntity
import com.example.data.entity.NotificationPrivacyMode
import com.example.data.entity.RedirectDecoyMode
import com.example.data.entity.RedirectDisplayStyle
import com.example.data.entity.VaultMediaCategory
import com.example.ota.ApkSecurityVerifier
import com.example.restriction.AppRestrictionSnapshot
import com.example.restriction.DeepRestrictionEvaluator
import com.example.restriction.DetectedChannelInfo
import com.example.restriction.EnforcementVerificationState
import com.example.restriction.RawPackageCapabilities
import com.example.restriction.SystemIntegrityStatus
import com.example.restriction.SystemRequirementIssue
import com.example.restriction.SystemStepKind
import com.example.restriction.UniversalChannelTracker
import com.example.security.PasswordHasher
import com.example.security.VaultFileCrypto
import com.example.service.CallNotificationClassifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import javax.crypto.spec.SecretKeySpec

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun wakeWordT9Conversion_andStealthMatching_work() {
        assertEquals("742369", PasswordHasher.wakeWordToT9Digits("shadow"))
        AppGuardRuntimeCache.updateConfig(
            AdminConfigEntity(
                isInitialized = true,
                wakeWord = "shadow",
                dialerPin = "742369",
                guardActive = true,
            )
        )
        assertTrue(AppGuardRuntimeCache.matchesWakeWord("shadow"))
        assertTrue(AppGuardRuntimeCache.matchesWakeWord("open shadow"))
        assertFalse(AppGuardRuntimeCache.matchesWakeWord("calculator"))

        assertTrue(AppGuardRuntimeCache.matchesDialerSecret("*#*#742369#*#*"))
        assertTrue(AppGuardRuntimeCache.matchesDialerSecret("*#*#82858#*#*"))
        assertFalse(AppGuardRuntimeCache.matchesDialerSecret("12345"))
    }

    @Test
    fun limitedAppCache_sessionUnlockRearmsOnExitAndBlocksOthers() {
        val now = 1_000_000L
        AppGuardRuntimeCache.clearSessionUnlock()
        AppGuardRuntimeCache.updateConfig(
            AdminConfigEntity(isInitialized = true, guardActive = true)
        )
        AppGuardRuntimeCache.updateLimitedApps(
            listOf(
                LimitedAppEntity(
                    packageName = "com.social.app",
                    appName = "Social",
                    isLimited = true,
                ),
                LimitedAppEntity(
                    packageName = "com.video.app",
                    appName = "Video",
                    isLimited = true,
                ),
            )
        )

        // Both restricted apps start blocked (offline + silent + Suspended gate)
        assertTrue(AppGuardRuntimeCache.isPackageCurrentlyBlocked("com.social.app"))
        assertTrue(AppGuardRuntimeCache.isPackageCurrentlyBlocked("com.video.app"))
        assertEquals(setOf("com.social.app", "com.video.app"), AppGuardRuntimeCache.currentlyBlockedPackageNames().toSet())

        // Entering Admin password unlocks only the active foreground session
        AppGuardRuntimeCache.grantSessionUnlock("com.social.app", now)
        assertFalse(AppGuardRuntimeCache.isPackageCurrentlyBlocked("com.social.app"))
        assertTrue(AppGuardRuntimeCache.isPackageCurrentlyBlocked("com.video.app"))

        // Target app enters foreground, then user leaves to launcher -> lock re-arms immediately
        AppGuardRuntimeCache.onForegroundPackageChanged("com.social.app", now + 200L)
        assertFalse(AppGuardRuntimeCache.isPackageCurrentlyBlocked("com.social.app"))

        AppGuardRuntimeCache.onForegroundPackageChanged("com.android.launcher3", now + 800L)
        assertTrue(AppGuardRuntimeCache.isPackageCurrentlyBlocked("com.social.app"))
        assertTrue(AppGuardRuntimeCache.isPackageCurrentlyBlocked("com.video.app"))
    }

    @Test
    fun callNotificationClassifier_detectsCallsAndDeclineActions() {
        assertTrue(CallNotificationClassifier.isDeclineActionTitle("Decline"))
        assertTrue(CallNotificationClassifier.isDeclineActionTitle("Reject"))
        assertTrue(CallNotificationClassifier.isDeclineActionTitle("Hang up"))
        assertTrue(CallNotificationClassifier.isDeclineActionTitle("End"))
        assertFalse(CallNotificationClassifier.isDeclineActionTitle("Answer"))
        assertFalse(CallNotificationClassifier.isDeclineActionTitle("Accept"))
        assertFalse(CallNotificationClassifier.isDeclineActionTitle("Reply"))

        assertTrue(
            CallNotificationClassifier.isIncomingCallSignals(
                category = Notification.CATEGORY_CALL,
                template = null,
                hasFullScreenIntent = false,
                actionTitles = emptyList(),
                titleAndText = "WhatsApp",
            )
        )
        assertTrue(
            CallNotificationClassifier.isIncomingCallSignals(
                category = null,
                template = "android.app.Notification\$CallStyle",
                hasFullScreenIntent = false,
                actionTitles = listOf("Decline", "Answer"),
                titleAndText = "Caller",
            )
        )
        assertTrue(
            CallNotificationClassifier.isIncomingCallSignals(
                category = null,
                template = null,
                hasFullScreenIntent = false,
                actionTitles = emptyList(),
                titleAndText = "Incoming voice call",
            )
        )
        assertFalse(
            CallNotificationClassifier.isIncomingCallSignals(
                category = Notification.CATEGORY_MESSAGE,
                template = null,
                hasFullScreenIntent = false,
                actionTitles = listOf("Mark as read", "Reply"),
                titleAndText = "Alex: Hey!",
            )
        )
    }

    @Test
    fun otaVerifier_andRedirectDisplayStyle_workCorrectly() {
        assertTrue(ApkSecurityVerifier.isVersionNewer(candidateCode = 2L, installedCode = 1L))
        assertFalse(ApkSecurityVerifier.isVersionNewer(candidateCode = 1L, installedCode = 1L))
        assertFalse(ApkSecurityVerifier.isVersionNewer(candidateCode = 1L, installedCode = 3L))

        val hashA = ApkSecurityVerifier.sha256Hex("vaultguard-key-a".toByteArray())
        val hashB = ApkSecurityVerifier.sha256Hex("attacker-key-b".toByteArray())
        assertEquals(64, hashA.length)

        assertTrue(
            ApkSecurityVerifier.doSigningHashesMatch(
                installedHashes = setOf(hashA),
                candidateHashes = setOf(hashA.uppercase()),
                expectedReleaseHash = hashA,
            )
        )
        assertFalse(
            ApkSecurityVerifier.doSigningHashesMatch(
                installedHashes = setOf(hashA),
                candidateHashes = setOf(hashB),
                expectedReleaseHash = hashB,
            )
        )
        assertFalse(
            ApkSecurityVerifier.doSigningHashesMatch(
                installedHashes = setOf(hashA),
                candidateHashes = setOf(hashA),
                expectedReleaseHash = hashB,
            )
        )
        assertFalse(
            ApkSecurityVerifier.doSigningHashesMatch(
                installedHashes = emptySet(),
                candidateHashes = setOf(hashA),
            )
        )

        assertEquals(RedirectDisplayStyle.SUSPENDED, RedirectDisplayStyle.fromName("UNKNOWN"))
        assertEquals(RedirectDisplayStyle.TEMPORARILY_PAUSED, RedirectDisplayStyle.fromName("TEMPORARILY_PAUSED"))
        assertEquals(RedirectDisplayStyle.BLOCKED, RedirectDisplayStyle.fromName("BLOCKED"))
        assertEquals(RedirectDisplayStyle.DISABLED, RedirectDisplayStyle.fromName("DISABLED"))
        assertEquals(RedirectDisplayStyle.UNAVAILABLE, RedirectDisplayStyle.fromName("UNAVAILABLE"))
        assertEquals(RedirectDisplayStyle.CANNOT_BE_REACHED, RedirectDisplayStyle.fromName("CANNOT_BE_REACHED"))
        assertEquals(RedirectDisplayStyle.FLAGGED, RedirectDisplayStyle.fromName("FLAGGED"))
        assertEquals(RedirectDisplayStyle.SUSPICIOUS, RedirectDisplayStyle.fromName("SUSPICIOUS"))
        assertEquals(RedirectDisplayStyle.CONTACT_SUPPORT, RedirectDisplayStyle.fromName("CONTACT_SUPPORT"))
        assertEquals(RedirectDisplayStyle.UNDER_REVIEW, RedirectDisplayStyle.fromName("UNDER_REVIEW"))
        assertEquals(RedirectDisplayStyle.ACCESS_DENIED, RedirectDisplayStyle.fromName("ACCESS_DENIED"))
        assertEquals(RedirectDisplayStyle.NOT_ALLOWED, RedirectDisplayStyle.fromName("NOT_ALLOWED"))
    }

    @Test
    fun vaultMediaCategory_detectsAllRequestedMediaTypes() {
        assertEquals(VaultMediaCategory.PHOTO, VaultMediaCategory.detectCategory("image/jpeg", "vacation.jpg"))
        assertEquals(VaultMediaCategory.PHOTO, VaultMediaCategory.detectCategory("", "scan.PNG"))
        assertEquals(VaultMediaCategory.VIDEO, VaultMediaCategory.detectCategory("video/mp4", "clip.mp4"))
        assertEquals(VaultMediaCategory.VIDEO, VaultMediaCategory.detectCategory("", "movie.mkv"))
        assertEquals(VaultMediaCategory.AUDIO, VaultMediaCategory.detectCategory("audio/mpeg", "song.mp3"))
        assertEquals(VaultMediaCategory.AUDIO, VaultMediaCategory.detectCategory("", "voice_note.m4a"))
        assertEquals(VaultMediaCategory.DOCUMENT, VaultMediaCategory.detectCategory("application/pdf", "contract.pdf"))
        assertEquals(VaultMediaCategory.DOCUMENT, VaultMediaCategory.detectCategory("text/plain", "notes.txt"))
        assertEquals(VaultMediaCategory.DOCUMENT, VaultMediaCategory.detectCategory("", "report.docx"))
        assertEquals(VaultMediaCategory.OTHER, VaultMediaCategory.detectCategory("application/octet-stream", "archive.bin"))
    }

    @Test
    fun vaultFileCrypto_encryptsAndDecryptsLocallyAcrossChunks() {
        val rawKeyBytes = ByteArray(32) { i -> (i * 7 + 3).toByte() }
        val secretKey = SecretKeySpec(rawKeyBytes, "AES")
        val plainBytes = ByteArray(150_000) { i -> (i % 251).toByte() }

        val encryptedOut = ByteArrayOutputStream()
        VaultFileCrypto.encryptStream(plainBytes.inputStream(), encryptedOut, secretKey)
        val cipherBytes = encryptedOut.toByteArray()

        assertTrue(cipherBytes.size > plainBytes.size)
        assertEquals('V'.code.toByte(), cipherBytes[0])
        assertEquals('G'.code.toByte(), cipherBytes[1])
        assertFalse(plainBytes.contentEquals(cipherBytes))

        val decryptedOut = ByteArrayOutputStream()
        VaultFileCrypto.decryptToStream(cipherBytes.inputStream(), decryptedOut, secretKey)
        assertArrayEquals(plainBytes, decryptedOut.toByteArray())
    }

    @Test
    fun restrictedAppMode_andNotificationPrivacy_workWithoutLockBadges() {
        val restrictedSilent = LimitedAppEntity(
            packageName = "com.chat.silent",
            appName = "SilentChat",
            isLimited = true,
            guardMode = AppGuardMode.RESTRICTED.name,
            notificationPrivacyMode = NotificationPrivacyMode.SILENT_AND_HIDE.name,
        )
        assertEquals(AppGuardMode.RESTRICTED, restrictedSilent.guardModeEnum)
        assertEquals(NotificationPrivacyMode.SILENT_AND_HIDE, restrictedSilent.notificationPrivacyEnum)

        val restrictedHiddenInfo = LimitedAppEntity(
            packageName = "com.chat.masked",
            appName = "MaskedChat",
            isLimited = true,
            guardMode = AppGuardMode.RESTRICTED.name,
            notificationPrivacyMode = NotificationPrivacyMode.SHOW_HIDE_SENSITIVE.name,
        )
        assertEquals(AppGuardMode.RESTRICTED, restrictedHiddenInfo.guardModeEnum)
        assertEquals(NotificationPrivacyMode.SHOW_HIDE_SENSITIVE, restrictedHiddenInfo.notificationPrivacyEnum)

        val unrestrictedApp = LimitedAppEntity(
            packageName = "com.open.app",
            appName = "OpenApp",
            isLimited = false,
            guardMode = AppGuardMode.NONE.name,
            notificationPrivacyMode = NotificationPrivacyMode.SILENT_AND_HIDE.name,
        )
        assertEquals(AppGuardMode.NONE, unrestrictedApp.guardModeEnum)

        assertEquals(RedirectDecoyMode.ICON_DISPLAY, RedirectDecoyMode.fromName("UNKNOWN"))
        assertEquals(RedirectDecoyMode.FAKE_CRASH, RedirectDecoyMode.fromName("FAKE_CRASH"))
    }

    @Test
    fun immediateLockTimeout_delaysRelockForConfiguredDuration() {
        val base = 2_000_000L
        AppGuardRuntimeCache.clearSessionUnlock()
        AppGuardRuntimeCache.updateConfig(
            AdminConfigEntity(
                isInitialized = true,
                guardActive = true,
                immediateLockTimeoutSeconds = 15,
            )
        )
        AppGuardRuntimeCache.updateLimitedApps(
            listOf(
                LimitedAppEntity(
                    packageName = "com.mail.app",
                    appName = "Mail",
                    isLimited = true,
                    guardMode = AppGuardMode.RESTRICTED.name,
                )
            )
        )

        AppGuardRuntimeCache.grantSessionUnlock("com.mail.app", base)
        AppGuardRuntimeCache.onForegroundPackageChanged("com.mail.app", base + 200L)
        assertFalse(AppGuardRuntimeCache.isPackageCurrentlyBlocked("com.mail.app", base + 300L))

        // Switch away to launcher at t = base + 1_000 -> remains unlocked during 15s window
        AppGuardRuntimeCache.onForegroundPackageChanged("com.android.launcher3", base + 1_000L)
        assertFalse(AppGuardRuntimeCache.isPackageCurrentlyBlocked("com.mail.app", base + 10_000L))

        // Returning before 15s keeps session alive and cancels timeout countdown
        AppGuardRuntimeCache.onForegroundPackageChanged("com.mail.app", base + 10_500L)
        assertFalse(AppGuardRuntimeCache.isPackageCurrentlyBlocked("com.mail.app", base + 20_000L))

        // Leaving again and waiting >= 15s re-arms lock automatically even if onForegroundPackageChanged runs first
        AppGuardRuntimeCache.onForegroundPackageChanged("com.android.launcher3", base + 21_000L)
        assertFalse(AppGuardRuntimeCache.isPackageCurrentlyBlocked("com.mail.app", base + 35_000L))
        AppGuardRuntimeCache.onForegroundPackageChanged("com.mail.app", base + 36_000L)
        assertTrue(AppGuardRuntimeCache.isPackageCurrentlyBlocked("com.mail.app", base + 36_000L))

        // Immediate mode (0s) re-arms immediately on switching away (even when switching to VaultGuard or quick exit)
        AppGuardRuntimeCache.updateConfig(
            AdminConfigEntity(
                isInitialized = true,
                guardActive = true,
                immediateLockTimeoutSeconds = 0,
            )
        )
        AppGuardRuntimeCache.grantSessionUnlock("com.mail.app", base + 40_000L)
        AppGuardRuntimeCache.onForegroundPackageChanged("com.mail.app", base + 40_100L)
        assertFalse(AppGuardRuntimeCache.isPackageCurrentlyBlocked("com.mail.app", base + 40_150L))
        AppGuardRuntimeCache.onForegroundPackageChanged("com.example", base + 40_200L)
        assertTrue(AppGuardRuntimeCache.isPackageCurrentlyBlocked("com.mail.app", base + 40_200L))
    }

    @Test
    fun universalChannelTracker_identifiesCallChannelsAcrossAnyPackage() {
        assertTrue(UniversalChannelTracker.isCallRelatedChannelMetadata("voip_ring_channel", "Alerts"))
        assertTrue(UniversalChannelTracker.isCallRelatedChannelMetadata("ch_01", "Incoming Voice Call"))
        assertTrue(UniversalChannelTracker.isCallRelatedChannelMetadata("video_call_v2", "Media"))
        assertFalse(UniversalChannelTracker.isCallRelatedChannelMetadata("direct_msgs", "Chat Messages"))
    }

    @Test
    fun systemIntegrityStatus_detectsMissingLivePermissionsWithoutAssumingGranted() {
        val revoked = SystemIntegrityStatus(
            isAccessibilityEnabled = false,
            isNotificationListenerEnabled = false,
            isDeviceAdminEnabled = false,
            isCameraPermissionGranted = false,
            isPostNotificationsGranted = false,
            guardActive = true,
            notificationBlockEnabled = true,
            uninstallProtectionEnabled = true,
            capturePhotoOnOpenEnabled = true,
            requiresPostNotificationsForMaskedAlerts = true,
        )
        assertFalse(revoked.isIntegrityConfirmed)
        assertFalse(revoked.isEnforcementOperational)
        assertEquals(
            setOf(
                SystemRequirementIssue.ACCESSIBILITY,
                SystemRequirementIssue.NOTIFICATION_LISTENER,
                SystemRequirementIssue.DEVICE_ADMIN,
                SystemRequirementIssue.CAMERA,
                SystemRequirementIssue.POST_NOTIFICATIONS,
            ),
            revoked.missingRequirements.toSet(),
        )

        val intact = SystemIntegrityStatus(
            isAccessibilityEnabled = true,
            isNotificationListenerEnabled = true,
            isDeviceAdminEnabled = true,
            isCameraPermissionGranted = true,
            isPostNotificationsGranted = true,
            guardActive = true,
            notificationBlockEnabled = true,
            uninstallProtectionEnabled = true,
            capturePhotoOnOpenEnabled = true,
            requiresPostNotificationsForMaskedAlerts = true,
        )
        assertTrue(intact.isIntegrityConfirmed)
        assertTrue(intact.isEnforcementOperational)
        assertTrue(intact.missingRequirements.isEmpty())
    }

    @Test
    fun deepRestrictionEvaluator_truthfullyReportsGapsFullEnforcementAndRestoreSteps() {
        val intactIntegrity = SystemIntegrityStatus(
            isAccessibilityEnabled = true,
            isNotificationListenerEnabled = true,
            isDeviceAdminEnabled = true,
            isCameraPermissionGranted = true,
            guardActive = true,
            notificationBlockEnabled = true,
        )
        val missingIntegrity = SystemIntegrityStatus(
            isAccessibilityEnabled = false,
            isNotificationListenerEnabled = false,
            guardActive = true,
            notificationBlockEnabled = true,
        )

        val openCaps = RawPackageCapabilities(
            packageName = "com.example.messenger",
            appName = "Messenger",
            requestsPostNotifications = true,
            postNotificationsGranted = true,
            requestsSystemAlertWindow = true,
            overlayAllowed = true,
            requestsFullScreenIntent = true,
            fullScreenIntentAllowed = true,
            requestsVibrate = true,
            requestsForegroundService = true,
            requestsCallCapabilities = true,
            isDeviceOwnerAvailable = false,
            isSuspendedByDeviceOwner = false,
        )
        val channels = listOf(
            DetectedChannelInfo(
                channelId = "call_alerts",
                channelName = "Incoming Calls",
                importance = 4,
                isCallChannel = true,
                hasSound = true,
                hasVibration = true,
                canBypassDnd = true,
                isBlockedInSystem = false,
                discoveredAtMillis = 100L,
            ),
            DetectedChannelInfo(
                channelId = "messages",
                channelName = "Messages",
                importance = 3,
                isCallChannel = false,
                hasSound = true,
                hasVibration = false,
                canBypassDnd = false,
                isBlockedInSystem = false,
                discoveredAtMillis = 100L,
            ),
        )

        // Core permissions missing -> DEGRADED_PERMISSION_MISSING (never claim enforcement)
        val degraded = DeepRestrictionEvaluator.evaluateReport(
            capabilities = openCaps,
            isRestricted = true,
            silentAndHide = true,
            discoveredChannels = channels,
            snapshot = null,
            integrity = missingIntegrity,
            supportsPostNotificationsRuntime = true,
        )
        assertEquals(EnforcementVerificationState.DEGRADED_PERMISSION_MISSING, degraded.verificationState)
        assertTrue(degraded.missingCorePermissions.containsAll(listOf("App Limiter", "Notification Access")))

        // Core active, but OS toggles still open -> ACTIVE_WITH_SYSTEM_GAPS with prioritized steps
        val partial = DeepRestrictionEvaluator.evaluateReport(
            capabilities = openCaps,
            isRestricted = true,
            silentAndHide = true,
            discoveredChannels = channels,
            snapshot = null,
            integrity = intactIntegrity,
            supportsPostNotificationsRuntime = true,
        )
        assertEquals(EnforcementVerificationState.ACTIVE_WITH_SYSTEM_GAPS, partial.verificationState)
        assertTrue(partial.pendingSystemSteps.any { it.kind == SystemStepKind.APP_NOTIFICATIONS })
        assertTrue(partial.pendingSystemSteps.any { it.kind == SystemStepKind.CHANNEL_ALERTS && it.channelId == "call_alerts" })
        assertTrue(partial.pendingSystemSteps.any { it.kind == SystemStepKind.FULL_SCREEN_INTENT })
        assertTrue(partial.pendingSystemSteps.any { it.kind == SystemStepKind.OVERLAY })

        // All OS vectors blocked -> FULLY_ENFORCED
        val lockedCaps = openCaps.copy(
            postNotificationsGranted = false,
            overlayAllowed = false,
            fullScreenIntentAllowed = false,
        )
        val lockedChannels = channels.map { it.copy(isBlockedInSystem = true) }
        val full = DeepRestrictionEvaluator.evaluateReport(
            capabilities = lockedCaps,
            isRestricted = true,
            silentAndHide = true,
            discoveredChannels = lockedChannels,
            snapshot = null,
            integrity = intactIntegrity,
            supportsPostNotificationsRuntime = true,
        )
        assertEquals(EnforcementVerificationState.FULLY_ENFORCED, full.verificationState)
        assertTrue(full.pendingSystemSteps.isEmpty())

        // Unrestricting after OS modifications -> surfaces accurate Restore steps from snapshot
        val snapshot = AppRestrictionSnapshot(
            packageName = "com.example.messenger",
            capturedAtMillis = 50L,
            hadPostNotificationsGranted = true,
            hadOverlayAllowed = true,
            hadFullScreenIntentAllowed = true,
            userGuidedSystemSteps = listOf(SystemStepKind.APP_NOTIFICATIONS.name),
        )
        val unrestricted = DeepRestrictionEvaluator.evaluateReport(
            capabilities = lockedCaps,
            isRestricted = false,
            silentAndHide = true,
            discoveredChannels = lockedChannels,
            snapshot = snapshot,
            integrity = intactIntegrity,
            supportsPostNotificationsRuntime = true,
        )
        assertEquals(EnforcementVerificationState.UNRESTRICTED, unrestricted.verificationState)
        assertEquals(3, unrestricted.restoreSteps.size)
        assertTrue(unrestricted.restoreSteps.all { it.isRestoreStep })
    }

    @Test
    fun messageChannelClassification_andChannelBaselineRestoreSteps_work() {
        assertTrue(UniversalChannelTracker.isMessageRelatedChannelMetadata("msg_direct", "Direct Notifications"))
        assertTrue(UniversalChannelTracker.isMessageRelatedChannelMetadata("ch_02", "Messages"))
        assertFalse(UniversalChannelTracker.isMessageRelatedChannelMetadata("media_playback", "Playback"))

        val intactIntegrity = SystemIntegrityStatus(
            isAccessibilityEnabled = true,
            isNotificationListenerEnabled = true,
            isCameraPermissionGranted = true,
            guardActive = true,
            notificationBlockEnabled = true,
        )
        val caps = RawPackageCapabilities(
            packageName = "com.chat.app",
            appName = "ChatApp",
            requestsPostNotifications = true,
            postNotificationsGranted = true,
            requestsSystemAlertWindow = false,
            overlayAllowed = false,
            requestsFullScreenIntent = false,
            fullScreenIntentAllowed = false,
            requestsVibrate = true,
            requestsForegroundService = false,
            requestsCallCapabilities = true,
            isDeviceOwnerAvailable = false,
            isSuspendedByDeviceOwner = false,
        )

        val baseline = listOf(
            com.example.restriction.ChannelBaselineRecord(
                channelId = "msg_notifications",
                channelName = "Message notifications",
                wasBlockedBeforeRestriction = false,
                baselineImportance = 4,
                isCallChannel = false,
            ),
            com.example.restriction.ChannelBaselineRecord(
                channelId = "already_off",
                channelName = "Marketing",
                wasBlockedBeforeRestriction = true,
                baselineImportance = 0,
                isCallChannel = false,
            ),
        )
        val snapshot = AppRestrictionSnapshot(
            packageName = "com.chat.app",
            capturedAtMillis = 123L,
            hadPostNotificationsGranted = true,
            channelBaselines = baseline,
        )
        val channelsNow = listOf(
            DetectedChannelInfo(
                channelId = "msg_notifications",
                channelName = "Message notifications",
                importance = 0,
                isCallChannel = false,
                hasSound = false,
                hasVibration = false,
                canBypassDnd = false,
                isBlockedInSystem = true,
                discoveredAtMillis = 200L,
            ),
            DetectedChannelInfo(
                channelId = "already_off",
                channelName = "Marketing",
                importance = 0,
                isCallChannel = false,
                hasSound = false,
                hasVibration = false,
                canBypassDnd = false,
                isBlockedInSystem = true,
                discoveredAtMillis = 200L,
            ),
        )

        val blockedReport = DeepRestrictionEvaluator.evaluateReport(
            capabilities = caps,
            isRestricted = true,
            silentAndHide = true,
            discoveredChannels = channelsNow,
            snapshot = snapshot,
            integrity = intactIntegrity,
            supportsPostNotificationsRuntime = true,
        )
        // Must require restoring ONLY "msg_notifications", NOT "already_off"
        assertEquals(1, blockedReport.restoreSteps.size)
        val restoreMsg = blockedReport.restoreSteps.first()
        assertEquals(SystemStepKind.CHANNEL_ALERTS, restoreMsg.kind)
        assertEquals("msg_notifications", restoreMsg.channelId)
        assertTrue(restoreMsg.title.contains("Message"))
    }

    @Test
    fun pre33TargetAndUnverifiedAppOps_surfaceSetupStepsWithoutFalseRestoreLockout() {
        val intactIntegrity = SystemIntegrityStatus(
            isAccessibilityEnabled = true,
            isNotificationListenerEnabled = true,
            isCameraPermissionGranted = true,
            guardActive = true,
            notificationBlockEnabled = true,
        )
        val pre33Caps = RawPackageCapabilities(
            packageName = "com.legacy.chat",
            appName = "LegacyChat",
            requestsPostNotifications = false,
            postNotificationsGranted = null,
            requestsSystemAlertWindow = true,
            overlayAllowed = null,
            requestsFullScreenIntent = true,
            fullScreenIntentAllowed = null,
            requestsVibrate = true,
            requestsForegroundService = false,
            requestsCallCapabilities = true,
            isDeviceOwnerAvailable = false,
            isSuspendedByDeviceOwner = false,
        )

        // Before user checks OS settings, pending steps surface notifications + FSI + Overlay
        val initialReport = DeepRestrictionEvaluator.evaluateReport(
            capabilities = pre33Caps,
            isRestricted = true,
            silentAndHide = true,
            discoveredChannels = emptyList(),
            snapshot = null,
            integrity = intactIntegrity,
            supportsPostNotificationsRuntime = true,
            isAppNotificationsBannedByOs = false,
        )
        assertEquals(EnforcementVerificationState.ACTIVE_WITH_SYSTEM_GAPS, initialReport.verificationState)
        assertTrue(initialReport.pendingSystemSteps.any { it.kind == SystemStepKind.APP_NOTIFICATIONS })
        assertTrue(initialReport.pendingSystemSteps.any { it.kind == SystemStepKind.FULL_SCREEN_INTENT })
        assertTrue(initialReport.pendingSystemSteps.any { it.kind == SystemStepKind.OVERLAY })

        // When restriction was applied via guided steps for unreadable OS settings, restore requires
        // confirming those guided restore steps before unrestricting.
        val guidedSnapshot = AppRestrictionSnapshot(
            packageName = "com.legacy.chat",
            capturedAtMillis = 100L,
            hadPostNotificationsGranted = null,
            hadOverlayAllowed = null,
            hadFullScreenIntentAllowed = null,
            hadAppNotificationsActive = true,
            wasRestrictedApplied = true,
            userGuidedSystemSteps = listOf(
                SystemStepKind.APP_NOTIFICATIONS.name,
                SystemStepKind.FULL_SCREEN_INTENT.name,
                SystemStepKind.OVERLAY.name,
            ),
        )
        val beforeGuidedRestore = DeepRestrictionEvaluator.evaluateReport(
            capabilities = pre33Caps,
            isRestricted = false,
            silentAndHide = true,
            discoveredChannels = emptyList(),
            snapshot = guidedSnapshot,
            integrity = intactIntegrity,
            supportsPostNotificationsRuntime = true,
            isAppNotificationsBannedByOs = false,
        )
        assertEquals(3, beforeGuidedRestore.restoreSteps.size)

        // Once user confirms removing/restoring all guided steps and OS is not banning alerts, restore clears
        val afterReview = DeepRestrictionEvaluator.evaluateReport(
            capabilities = pre33Caps,
            isRestricted = false,
            silentAndHide = true,
            discoveredChannels = emptyList(),
            snapshot = guidedSnapshot.copy(
                userRestoredGuidedSteps = listOf(
                    SystemStepKind.APP_NOTIFICATIONS.name,
                    SystemStepKind.FULL_SCREEN_INTENT.name,
                    SystemStepKind.OVERLAY.name,
                )
            ),
            integrity = intactIntegrity,
            supportsPostNotificationsRuntime = true,
            isAppNotificationsBannedByOs = false,
        )
        assertTrue(afterReview.restoreSteps.isEmpty())

        // If OS actually banned notifications via NotificationListener REASON_PACKAGE_BANNED, restore IS still required
        val whileBanned = DeepRestrictionEvaluator.evaluateReport(
            capabilities = pre33Caps,
            isRestricted = false,
            silentAndHide = true,
            discoveredChannels = emptyList(),
            snapshot = guidedSnapshot.copy(
                userRestoredGuidedSteps = listOf(
                    SystemStepKind.APP_NOTIFICATIONS.name,
                    SystemStepKind.FULL_SCREEN_INTENT.name,
                    SystemStepKind.OVERLAY.name,
                )
            ),
            integrity = intactIntegrity,
            supportsPostNotificationsRuntime = true,
            isAppNotificationsBannedByOs = true,
        )
        assertEquals(1, whileBanned.restoreSteps.size)
        assertEquals(SystemStepKind.APP_NOTIFICATIONS, whileBanned.restoreSteps.first().kind)
    }

    @Test
    fun soundSilencedOnActiveChannel_requiresRestoreEvenIfImportanceUnchanged() {
        val intactIntegrity = SystemIntegrityStatus(
            isAccessibilityEnabled = true,
            isNotificationListenerEnabled = true,
            isCameraPermissionGranted = true,
            guardActive = true,
            notificationBlockEnabled = true,
        )
        val caps = RawPackageCapabilities(
            packageName = "com.voice.app",
            appName = "Voice",
            requestsPostNotifications = true,
            postNotificationsGranted = true,
            requestsSystemAlertWindow = false,
            overlayAllowed = false,
            requestsFullScreenIntent = false,
            fullScreenIntentAllowed = false,
            requestsVibrate = true,
            requestsForegroundService = false,
            requestsCallCapabilities = true,
            isDeviceOwnerAvailable = false,
            isSuspendedByDeviceOwner = false,
        )
        val snapshot = AppRestrictionSnapshot(
            packageName = "com.voice.app",
            capturedAtMillis = 50L,
            hadPostNotificationsGranted = true,
            channelBaselines = listOf(
                com.example.restriction.ChannelBaselineRecord(
                    channelId = "msg_tone",
                    channelName = "Messages",
                    wasBlockedBeforeRestriction = false,
                    baselineImportance = 3,
                    isCallChannel = false,
                    hadSound = true,
                    hadVibration = true,
                )
            ),
        )
        val silencedChannel = DetectedChannelInfo(
            channelId = "msg_tone",
            channelName = "Messages",
            importance = 3,
            isCallChannel = false,
            hasSound = false,
            hasVibration = false,
            canBypassDnd = false,
            isBlockedInSystem = false,
            discoveredAtMillis = 150L,
        )
        val report = DeepRestrictionEvaluator.evaluateReport(
            capabilities = caps,
            isRestricted = false,
            silentAndHide = true,
            discoveredChannels = listOf(silencedChannel),
            snapshot = snapshot,
            integrity = intactIntegrity,
            supportsPostNotificationsRuntime = true,
        )
        assertEquals(1, report.restoreSteps.size)
        assertEquals("msg_tone", report.restoreSteps.first().channelId)
    }

    @Test
    fun canApplyRestriction_andShowHideSensitiveMode_enforceRequiredPermissions() {
        val noPostNotifIntegrity = SystemIntegrityStatus(
            isAccessibilityEnabled = true,
            isNotificationListenerEnabled = true,
            isCameraPermissionGranted = true,
            isPostNotificationsGranted = false,
            guardActive = true,
        )
        val noCameraIntegrity = SystemIntegrityStatus(
            isAccessibilityEnabled = true,
            isNotificationListenerEnabled = true,
            isCameraPermissionGranted = false,
            isPostNotificationsGranted = true,
            guardActive = true,
        )
        val fullIntegrity = noPostNotifIntegrity.copy(isPostNotificationsGranted = true)
        val caps = RawPackageCapabilities(
            packageName = "com.chat.masked",
            appName = "MaskedChat",
            requestsPostNotifications = true,
            postNotificationsGranted = true,
            requestsSystemAlertWindow = false,
            overlayAllowed = false,
            requestsFullScreenIntent = false,
            fullScreenIntentAllowed = false,
            requestsVibrate = true,
            requestsForegroundService = false,
            requestsCallCapabilities = true,
            isDeviceOwnerAvailable = false,
            isSuspendedByDeviceOwner = false,
        )
        val channels = listOf(
            DetectedChannelInfo(
                channelId = "call_ring",
                channelName = "Incoming Calls",
                importance = 4,
                isCallChannel = true,
                hasSound = true,
                hasVibration = true,
                canBypassDnd = true,
                isBlockedInSystem = false,
                discoveredAtMillis = 100L,
            ),
            DetectedChannelInfo(
                channelId = "msg_alerts",
                channelName = "Messages",
                importance = 4,
                isCallChannel = false,
                hasSound = true,
                hasVibration = false,
                canBypassDnd = false,
                isBlockedInSystem = false,
                discoveredAtMillis = 100L,
            ),
        )

        // Missing Camera permission also blocks restricting an app
        val missingCamera = DeepRestrictionEvaluator.evaluateReport(
            capabilities = caps,
            isRestricted = true,
            silentAndHide = true,
            discoveredChannels = channels,
            snapshot = null,
            integrity = noCameraIntegrity,
            supportsPostNotificationsRuntime = true,
        )
        assertFalse(missingCamera.canApplyRestriction)
        assertTrue(missingCamera.missingCorePermissions.contains("Camera"))

        // Missing VaultGuard Post Notifications permission blocks Show/Hide Content restriction
        val missingPostNotif = DeepRestrictionEvaluator.evaluateReport(
            capabilities = caps,
            isRestricted = true,
            silentAndHide = false,
            discoveredChannels = channels,
            snapshot = null,
            integrity = noPostNotifIntegrity,
            supportsPostNotificationsRuntime = true,
        )
        assertFalse(missingPostNotif.canApplyRestriction)
        assertTrue(missingPostNotif.missingCorePermissions.contains("Post Notifications"))
        assertEquals(1, missingPostNotif.pendingSystemSteps.size)
        assertEquals("call_ring", missingPostNotif.pendingSystemSteps.first().channelId)

        // Even with all core permissions, an active Call channel still blocks applying restriction
        val activeCallChannel = DeepRestrictionEvaluator.evaluateReport(
            capabilities = caps,
            isRestricted = true,
            silentAndHide = false,
            discoveredChannels = channels,
            snapshot = null,
            integrity = fullIntegrity,
            supportsPostNotificationsRuntime = true,
        )
        assertFalse(activeCallChannel.canApplyRestriction)

        // Once the call channel is blocked (message channel stays active for masked alerts), restriction can be applied
        val ready = DeepRestrictionEvaluator.evaluateReport(
            capabilities = caps,
            isRestricted = true,
            silentAndHide = false,
            discoveredChannels = listOf(
                channels[0].copy(isBlockedInSystem = true, importance = 0),
                channels[1],
            ),
            snapshot = null,
            integrity = fullIntegrity,
            supportsPostNotificationsRuntime = true,
        )
        assertTrue(ready.canApplyRestriction)
        assertTrue(ready.pendingSystemSteps.isEmpty())
    }
}
