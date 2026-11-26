package com.example

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppGuardRuntimeCache
import com.example.data.db.VaultDatabase
import com.example.data.entity.AdminConfigEntity
import com.example.data.entity.LimitedAppEntity
import com.example.restriction.AppRestrictionSnapshot
import com.example.restriction.DeepRestrictionEngine
import com.example.restriction.DetectedChannelInfo
import com.example.restriction.RestrictionStateCodec
import com.example.restriction.SystemRestrictionStep
import com.example.restriction.SystemStepKind
import com.example.security.PasswordHasher
import kotlinx.coroutines.flow.first
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

    @Test
    fun `restriction state codec serializes and restores snapshots and channels`() {
        val snapshot = AppRestrictionSnapshot(
            packageName = "org.demo.chat",
            capturedAtMillis = 987654L,
            hadPostNotificationsGranted = true,
            hadOverlayAllowed = true,
            hadFullScreenIntentAllowed = false,
            wasSuspendedByDeviceOwner = false,
            deviceOwnerDeniedPostNotifications = false,
            deviceOwnerSuspendedPackage = false,
            userGuidedSystemSteps = listOf("APP_NOTIFICATIONS", "OVERLAY"),
        )
        val encodedSnapshot = RestrictionStateCodec.encodeSnapshot(snapshot)
        val decodedSnapshot = RestrictionStateCodec.decodeSnapshot(encodedSnapshot)
        assertEquals(snapshot, decodedSnapshot)

        val channels = listOf(
            DetectedChannelInfo(
                channelId = "ring_calls",
                channelName = "Voice Calls",
                importance = 5,
                isCallChannel = true,
                hasSound = true,
                hasVibration = true,
                canBypassDnd = true,
                isBlockedInSystem = false,
                discoveredAtMillis = 12345L,
            ),
            DetectedChannelInfo(
                channelId = "general_chat",
                channelName = "General",
                importance = 3,
                isCallChannel = false,
                hasSound = true,
                hasVibration = false,
                canBypassDnd = false,
                isBlockedInSystem = true,
                discoveredAtMillis = 12346L,
            ),
        )
        val encodedChannels = RestrictionStateCodec.encodeChannels(channels)
        val decodedChannels = RestrictionStateCodec.decodeChannels(encodedChannels)
        assertEquals(channels, decodedChannels)
    }

    @Test
    fun `deep restriction engine builds exact system setting intent chains`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val engine = DeepRestrictionEngine(context, db.vaultDao())
            val pkg = "org.example.target"

            val notifChain = engine.buildSystemSettingsIntentChain(
                SystemRestrictionStep(
                    kind = SystemStepKind.APP_NOTIFICATIONS,
                    title = "Notifications",
                    targetPackage = pkg,
                )
            )
            assertTrue(notifChain.isNotEmpty())
            assertTrue(notifChain.all { (it.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK) != 0 })
            assertEquals(pkg, notifChain.first().getStringExtra(Settings.EXTRA_APP_PACKAGE) ?: pkg)

            val channelChain = engine.buildSystemSettingsIntentChain(
                SystemRestrictionStep(
                    kind = SystemStepKind.CHANNEL_ALERTS,
                    title = "Incoming Calls",
                    targetPackage = pkg,
                    channelId = "call_ring_01",
                )
            )
            assertTrue(channelChain.isNotEmpty())

            val overlayChain = engine.buildSystemSettingsIntentChain(
                SystemRestrictionStep(
                    kind = SystemStepKind.OVERLAY,
                    title = "Overlay Display",
                    targetPackage = pkg,
                )
            )
            assertTrue(overlayChain.isNotEmpty())

            val fullScreenChain = engine.buildSystemSettingsIntentChain(
                SystemRestrictionStep(
                    kind = SystemStepKind.FULL_SCREEN_INTENT,
                    title = "Full-Screen Alerts",
                    targetPackage = pkg,
                )
            )
            assertTrue(fullScreenChain.isNotEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `deep restriction engine blocks ending restriction until turned off channel is restored`() =
        kotlinx.coroutines.runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = androidx.room.Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            try {
                val engine = DeepRestrictionEngine(context, db.vaultDao())
                val pkg = "org.example.chat.restore"
                com.example.restriction.UniversalChannelTracker.clearPackage(pkg)

                // App starts with an active message channel
                val activeMsgChannel = DetectedChannelInfo(
                    channelId = "msg_alerts",
                    channelName = "Messages",
                    importance = 4,
                    isCallChannel = false,
                    hasSound = true,
                    hasVibration = true,
                    canBypassDnd = false,
                    isBlockedInSystem = false,
                    discoveredAtMillis = 1000L,
                )
                com.example.restriction.UniversalChannelTracker.recordChannelInfo(pkg, activeMsgChannel)

                val integrity = com.example.restriction.SystemIntegrityStatus(
                    isAccessibilityEnabled = true,
                    isNotificationListenerEnabled = true,
                    guardActive = true,
                    notificationBlockEnabled = true,
                )
                engine.applyRestrictionWithSnapshot(
                    packageName = pkg,
                    appName = "ChatApp",
                    privacyMode = com.example.data.entity.NotificationPrivacyMode.SILENT_AND_HIDE,
                    integrity = integrity,
                )

                // User turns off Messages channel while restricted
                val turnedOffMsgChannel = activeMsgChannel.copy(
                    importance = 0,
                    isBlockedInSystem = true,
                    discoveredAtMillis = 2000L,
                )
                com.example.restriction.UniversalChannelTracker.recordChannelInfo(pkg, turnedOffMsgChannel)
                engine.recordChannelModifiedWhileRestricted(pkg, turnedOffMsgChannel)

                // Attempting to end restriction must FAIL until Messages channel is restored
                val (endedBeforeRestore, reportBeforeRestore) = engine.tryEndRestrictionAfterRestore(
                    packageName = pkg,
                    appName = "ChatApp",
                    silentAndHide = true,
                    integrity = integrity,
                )
                assertFalse(endedBeforeRestore)
                assertTrue(reportBeforeRestore.isRestricted)
                assertEquals(1, reportBeforeRestore.restoreSteps.size)
                assertEquals("msg_alerts", reportBeforeRestore.restoreSteps.first().channelId)

                // User restores Messages channel back on
                val restoredMsgChannel = activeMsgChannel.copy(
                    importance = 4,
                    isBlockedInSystem = false,
                    discoveredAtMillis = 3000L,
                )
                com.example.restriction.UniversalChannelTracker.recordChannelInfo(pkg, restoredMsgChannel)
                engine.recordChannelModifiedWhileRestricted(pkg, restoredMsgChannel)

                // Now ending restriction succeeds cleanly
                val (endedAfterRestore, reportAfterRestore) = engine.tryEndRestrictionAfterRestore(
                    packageName = pkg,
                    appName = "ChatApp",
                    silentAndHide = true,
                    integrity = integrity,
                )
                assertTrue(endedAfterRestore)
                assertFalse(reportAfterRestore.isRestricted)
                assertTrue(reportAfterRestore.restoreSteps.isEmpty())
            } finally {
                db.close()
            }
        }

    @Test
    fun `channel modified during setup before save is monitored and requires restore`() =
        kotlinx.coroutines.runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = androidx.room.Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            try {
                val engine = DeepRestrictionEngine(context, db.vaultDao())
                val pkg = "org.example.setup.channel"
                com.example.restriction.UniversalChannelTracker.clearPackage(pkg)
                AppGuardRuntimeCache.activeSetupPackage = pkg

                val turnedOffDuringSetup = DetectedChannelInfo(
                    channelId = "call_ring",
                    channelName = "Incoming Calls",
                    importance = 0,
                    isCallChannel = true,
                    hasSound = false,
                    hasVibration = false,
                    canBypassDnd = false,
                    isBlockedInSystem = true,
                    discoveredAtMillis = 1500L,
                )
                com.example.restriction.UniversalChannelTracker.recordChannelInfo(pkg, turnedOffDuringSetup)
                engine.recordChannelModifiedWhileRestricted(pkg, turnedOffDuringSetup)

                val integrity = com.example.restriction.SystemIntegrityStatus(
                    isAccessibilityEnabled = true,
                    isNotificationListenerEnabled = true,
                    guardActive = true,
                    notificationBlockEnabled = true,
                )
                // User clicks Save afterward
                val savedReport = engine.applyRestrictionWithSnapshot(
                    packageName = pkg,
                    appName = "CallApp",
                    privacyMode = com.example.data.entity.NotificationPrivacyMode.SILENT_AND_HIDE,
                    integrity = integrity,
                )
                AppGuardRuntimeCache.activeSetupPackage = null

                assertEquals(1, savedReport.restoreSteps.size)
                assertEquals("call_ring", savedReport.restoreSteps.first().channelId)

                // Trying to end restriction blocks until Call channel is restored
                val (ended, _) = engine.tryEndRestrictionAfterRestore(
                    packageName = pkg,
                    appName = "CallApp",
                    silentAndHide = true,
                    integrity = integrity,
                )
                assertFalse(ended)
            } finally {
                AppGuardRuntimeCache.activeSetupPackage = null
                db.close()
            }
        }

    @Test
    fun `repository prevents restricting without required permissions and prevents unrestricting until restored`() =
        kotlinx.coroutines.runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = androidx.room.Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val pkg = "org.example.repo.gate"
            try {
                com.example.restriction.UniversalChannelTracker.clearPackage(pkg)
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    "",
                )
                Settings.Secure.putString(
                    context.contentResolver,
                    "enabled_notification_listeners",
                    "",
                )

                val repo = com.example.data.repository.VaultRepository(
                    context = context,
                    dao = db.vaultDao(),
                    appsProvider = com.example.data.InstalledAppsProvider(context),
                    launcherManager = com.example.launcher.LauncherDisguiseManager(context),
                )
                repo.initializeAdmin(
                    password = "password123",
                    wakeWord = "vault",
                    dialerPin = "82858",
                )

                val activeChannel = DetectedChannelInfo(
                    channelId = "msg_channel",
                    channelName = "Messages",
                    importance = 4,
                    isCallChannel = false,
                    hasSound = true,
                    hasVibration = true,
                    canBypassDnd = false,
                    isBlockedInSystem = false,
                    discoveredAtMillis = 1000L,
                )
                com.example.restriction.UniversalChannelTracker.recordChannelInfo(pkg, activeChannel)

                // 1. Core permissions missing -> cannot restrict
                val blockedMissingCore = repo.setAppGuardRule(
                    packageName = pkg,
                    appName = "RepoGateApp",
                    guardMode = com.example.data.entity.AppGuardMode.RESTRICTED,
                ).getOrThrow()
                assertFalse(blockedMissingCore.canApplyRestriction)
                assertFalse(blockedMissingCore.isRestricted)
                assertFalse(db.vaultDao().getLimitedApp(pkg)?.isLimited == true)

                // 2. Grant core permissions, but app still has an active unmuted channel -> still cannot restrict
                val a11yComponent = android.content.ComponentName(
                    context,
                    com.example.service.AppGuardAccessibilityService::class.java,
                ).flattenToString()
                val nlsComponent = android.content.ComponentName(
                    context,
                    com.example.service.VaultNotificationListenerService::class.java,
                ).flattenToString()
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    a11yComponent,
                )
                Settings.Secure.putString(
                    context.contentResolver,
                    "enabled_notification_listeners",
                    nlsComponent,
                )
                org.robolectric.Shadows.shadowOf(context as android.app.Application)
                    .grantPermissions(android.Manifest.permission.CAMERA)

                val blockedPendingSteps = repo.setAppGuardRule(
                    packageName = pkg,
                    appName = "RepoGateApp",
                    guardMode = com.example.data.entity.AppGuardMode.RESTRICTED,
                ).getOrThrow()
                assertFalse(blockedPendingSteps.canApplyRestriction)
                assertFalse(blockedPendingSteps.isRestricted)
                assertFalse(db.vaultDao().getLimitedApp(pkg)?.isLimited == true)

                // 3. Turn off the channel during setup -> now restricting succeeds
                val mutedChannel = activeChannel.copy(importance = 0, isBlockedInSystem = true, discoveredAtMillis = 2000L)
                com.example.restriction.UniversalChannelTracker.recordChannelInfo(pkg, mutedChannel)
                repo.recordChannelModifiedWhileRestricted(pkg, mutedChannel)

                val applied = repo.setAppGuardRule(
                    packageName = pkg,
                    appName = "RepoGateApp",
                    guardMode = com.example.data.entity.AppGuardMode.RESTRICTED,
                ).getOrThrow()
                assertTrue(applied.canApplyRestriction)
                assertTrue(applied.isRestricted)
                assertTrue(db.vaultDao().getLimitedApp(pkg)?.isLimited == true)

                // 4. Attempting to unrestrict while channel is still off fails
                val blockedEnd = repo.setAppGuardRule(
                    packageName = pkg,
                    appName = "RepoGateApp",
                    guardMode = com.example.data.entity.AppGuardMode.NONE,
                ).getOrThrow()
                assertTrue(blockedEnd.isRestricted)
                assertEquals(1, blockedEnd.restoreSteps.size)
                assertTrue(db.vaultDao().getLimitedApp(pkg)?.isLimited == true)

                // 5. Restoring the channel allows unrestricting cleanly
                val restoredChannel = activeChannel.copy(importance = 4, isBlockedInSystem = false, discoveredAtMillis = 3000L)
                com.example.restriction.UniversalChannelTracker.recordChannelInfo(pkg, restoredChannel)
                repo.recordChannelModifiedWhileRestricted(pkg, restoredChannel)

                val ended = repo.setAppGuardRule(
                    packageName = pkg,
                    appName = "RepoGateApp",
                    guardMode = com.example.data.entity.AppGuardMode.NONE,
                ).getOrThrow()
                assertFalse(ended.isRestricted)
                assertTrue(ended.restoreSteps.isEmpty())
                assertFalse(db.vaultDao().getLimitedApp(pkg)?.isLimited == true)
            } finally {
                com.example.restriction.UniversalChannelTracker.clearPackage(pkg)
                db.close()
            }
        }

    @Test
    fun `accessibility detection reacts immediately to master toggle and service entry`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = com.example.data.repository.VaultRepository(
                context = context,
                dao = db.vaultDao(),
                appsProvider = com.example.data.InstalledAppsProvider(context),
                launcherManager = com.example.launcher.LauncherDisguiseManager(context),
            )
            val a11yComponent = android.content.ComponentName(
                context,
                com.example.service.AppGuardAccessibilityService::class.java,
            ).flattenToString()

            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                a11yComponent,
            )
            assertTrue(repo.isAccessibilityServiceEnabled())

            // Turning master accessibility toggle off must report disabled immediately
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            assertFalse(repo.isAccessibilityServiceEnabled())

            // Turning master toggle back on restores enabled immediately
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            assertTrue(repo.isAccessibilityServiceEnabled())

            // Removing the service component reports disabled immediately
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                "",
            )
            assertFalse(repo.isAccessibilityServiceEnabled())
        } finally {
            db.close()
        }
    }

    @Test
    fun `repository aggregates frequent restricted apps in descending order`() =
        kotlinx.coroutines.runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = androidx.room.Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            try {
                val repo = com.example.data.repository.VaultRepository(
                    context = context,
                    dao = db.vaultDao(),
                    appsProvider = com.example.data.InstalledAppsProvider(context),
                    launcherManager = com.example.launcher.LauncherDisguiseManager(context),
                )
                repo.logEvent("org.alpha.app", "Alpha", "APP_RESTRICTED", "Restricted")
                repo.logEvent("org.beta.app", "Beta", "APP_RESTRICTED", "Restricted")
                repo.logEvent("org.beta.app", "Beta", "RESTRICTED_GATE", "Gate")
                repo.logEvent("org.beta.app", "Beta", "SESSION_UNLOCKED", "Unlocked")
                repo.logEvent("org.gamma.app", "Gamma", "PASSWORD_CHANGED", "Ignore non-restriction")

                val freqList = repo.appRestrictionFrequenciesFlow.first()
                assertEquals(2, freqList.size)
                assertEquals("org.beta.app", freqList[0].packageName)
                assertEquals(3, freqList[0].eventCount)
                assertEquals("org.alpha.app", freqList[1].packageName)
                assertEquals(1, freqList[1].eventCount)
            } finally {
                db.close()
            }
        }

    @Test
    fun `capabilities inspector does not falsely report fullScreenIntentAllowed when target app is absent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inspector = com.example.restriction.PackageCapabilitiesInspector(context)
        val raw = inspector.inspectRawCapabilities("org.missing.external.app", "External")
        assertFalse(raw.fullScreenIntentAllowed == true)
    }

    @Test
    fun `redirect block and session events persist valid photo paths`() =
        kotlinx.coroutines.runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = androidx.room.Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val photoFile = java.io.File(context.filesDir, "test_session_photo.jpg")
            try {
                photoFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
                val repo = com.example.data.repository.VaultRepository(
                    context = context,
                    dao = db.vaultDao(),
                    appsProvider = com.example.data.InstalledAppsProvider(context),
                    launcherManager = com.example.launcher.LauncherDisguiseManager(context),
                )
                val pkg = "org.session.photo.test"
                db.vaultDao().upsertLimitedApp(
                    LimitedAppEntity(
                        packageName = pkg,
                        appName = "PhotoTarget",
                        isLimited = true,
                    )
                )

                val gateId = repo.recordRedirectBlock(
                    packageName = pkg,
                    appName = "PhotoTarget",
                    decoyMode = com.example.data.entity.RedirectDecoyMode.FAKE_CRASH,
                    photoPath = photoFile.absolutePath,
                )!!
                val authId = repo.logEvent(pkg, "PhotoTarget", "AUTH_FAILED", "redirect_app")
                repo.attachPhotoToEvents(listOf(gateId, authId), photoFile.absolutePath)

                val events = repo.recentEventsFlow.first()
                val gateEvent = events.first { it.id == gateId }
                val authEvent = events.first { it.id == authId }
                assertEquals(photoFile.absolutePath, gateEvent.photoPath)
                assertEquals(photoFile.absolutePath, authEvent.photoPath)
                assertTrue(repo.hasValidPhotoFile(gateEvent.photoPath))
            } finally {
                photoFile.delete()
                db.close()
            }
        }

    @Test
    fun `disguise recents helpers resolve respective disguise icon and label never vaultguard`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = com.example.launcher.LauncherDisguiseManager(context)

        val cases = listOf(
            com.example.data.entity.DisguiseIdentity.CALCULATOR to Pair("Calculator", R.drawable.ic_disguise_calculator),
            com.example.data.entity.DisguiseIdentity.NOTES to Pair("Quick Notes", R.drawable.ic_disguise_notes),
            com.example.data.entity.DisguiseIdentity.WEATHER to Pair("Weather", R.drawable.ic_disguise_weather),
            com.example.data.entity.DisguiseIdentity.CLOCK to Pair("Timer", R.drawable.ic_disguise_clock),
        )

        for ((identity, expected) in cases) {
            val (expectedLabel, expectedIcon) = expected
            assertEquals(
                expectedLabel,
                com.example.launcher.LauncherDisguiseManager.resolveRecentsLabel(
                    context = context,
                    disguise = identity,
                    hideFromLauncher = false,
                ),
            )
            assertEquals(
                expectedIcon,
                com.example.launcher.LauncherDisguiseManager.iconResFor(identity),
            )
            manager.applyLauncherState(identity, hideFromLauncher = false)
            assertTrue(manager.isAliasEnabled(identity))
            assertFalse(manager.isAliasEnabled(com.example.data.entity.DisguiseIdentity.DEFAULT))
        }

        // Hidden mode with DEFAULT disguise resolves to CALCULATOR on API 29+ so VaultGuard is masked
        val hiddenEffective = com.example.launcher.LauncherDisguiseManager.resolveEffectiveDisguise(
            disguise = com.example.data.entity.DisguiseIdentity.DEFAULT,
            hideFromLauncher = true,
        )
        assertEquals(com.example.data.entity.DisguiseIdentity.CALCULATOR, hiddenEffective)
        assertEquals(
            "Calculator",
            com.example.launcher.LauncherDisguiseManager.resolveRecentsLabel(
                context = context,
                disguise = com.example.data.entity.DisguiseIdentity.DEFAULT,
                hideFromLauncher = true,
            ),
        )
    }

    @Test
    fun `gatekeeper activity blocks screenshots with FLAG_SECURE and applies active disguise identity cleanly`() {
        AppGuardRuntimeCache.updateConfig(
            AdminConfigEntity(
                isInitialized = true,
                disguiseName = com.example.data.entity.DisguiseIdentity.CALCULATOR.name,
                isAppHidden = false,
            )
        )
        val controller = org.robolectric.Robolectric.buildActivity(GatekeeperActivity::class.java).setup()
        try {
            val activity = controller.get()
            val flags = activity.window.attributes.flags
            assertTrue((flags and android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0)
            for (identity in com.example.data.entity.DisguiseIdentity.entries) {
                com.example.launcher.LauncherDisguiseManager.applyRecentsIdentity(
                    activity = activity,
                    disguise = identity,
                    hideFromLauncher = false,
                )
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
