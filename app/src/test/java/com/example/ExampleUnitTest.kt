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

}

