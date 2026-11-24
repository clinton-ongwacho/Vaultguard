package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppGuardRuntimeCache
import com.example.data.entity.AdminConfigEntity
import com.example.data.entity.LimitedAppEntity
import com.example.security.PasswordHasher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("VaultGuard", appName)
    }

    @Test
    fun `password hash and verification succeeds for matching password`() {
        val hash = PasswordHasher.hashPassword("admin1234")
        assertTrue(PasswordHasher.verifyPassword("admin1234", hash))
        assertFalse(PasswordHasher.verifyPassword("wrongpass", hash))
    }

    @Test
    fun `wake word T9 conversion and stealth matching works`() {
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
    fun `limited app cache session unlock rearms on exit`() {
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
                    packageName = "com.temp.app",
                    appName = "TempApp",
                    isLimited = true,
                ),
            )
        )

        assertTrue(AppGuardRuntimeCache.isPackageCurrentlyBlocked("com.social.app"))
        assertTrue(AppGuardRuntimeCache.isPackageCurrentlyBlocked("com.temp.app"))

        AppGuardRuntimeCache.grantSessionUnlock("com.temp.app", now)
        assertTrue(AppGuardRuntimeCache.isPackageCurrentlyBlocked("com.social.app"))
        assertFalse(AppGuardRuntimeCache.isPackageCurrentlyBlocked("com.temp.app"))

        AppGuardRuntimeCache.onForegroundPackageChanged("com.temp.app", now + 200L)
        AppGuardRuntimeCache.onForegroundPackageChanged("com.android.launcher3", now + 700L)
        assertTrue(AppGuardRuntimeCache.isPackageCurrentlyBlocked("com.temp.app"))
    }

    @Test
    fun `security questions require at least 3 backed up and 2 matches to reset`() {
        val h1 = PasswordHasher.hashSecurityAnswer("Lagos")
        val h2 = PasswordHasher.hashSecurityAnswer("Biscuit")
        val h3 = PasswordHasher.hashSecurityAnswer("Green Tea")

        // Fewer than 3 backed-up questions must fail even if 2 answers match
        assertFalse(
            PasswordHasher.qualifiesForPasswordReset(
                storedHashesByQuestionId = mapOf(1L to h1, 2L to h2),
                providedAnswersByQuestionId = mapOf(1L to "lagos", 2L to "biscuit"),
            )
        )

        val storedThree = mapOf(1L to h1, 2L to h2, 3L to h3)

        // Only 1 match out of 3 fails
        assertFalse(
            PasswordHasher.qualifiesForPasswordReset(
                storedHashesByQuestionId = storedThree,
                providedAnswersByQuestionId = mapOf(1L to "lagos", 2L to "wrong", 3L to ""),
            )
        )

        // 2 matches out of 3 succeeds (case-insensitive + trimmed whitespace)
        assertTrue(
            PasswordHasher.qualifiesForPasswordReset(
                storedHashesByQuestionId = storedThree,
                providedAnswersByQuestionId = mapOf(1L to "  LAGOS ", 2L to "wrong", 3L to "green   tea"),
            )
        )
    }
}
