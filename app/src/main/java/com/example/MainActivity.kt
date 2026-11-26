package com.example

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AppGuardRuntimeCache
import com.example.data.entity.DisguiseIdentity
import com.example.launcher.LauncherDisguiseManager
import com.example.security.GoogleAuthHelper
import com.example.security.GoogleAuthHelper.authStateFlow
import com.example.service.VaultDeviceAdminReceiver
import com.example.ui.VaultConsoleHost
import com.example.ui.VaultViewModel
import com.example.ui.VaultViewModelFactory
import com.example.ui.screens.AdminUnlockScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.DisguiseFacadeScreen
import com.example.ui.screens.SetupScreen
import com.example.ui.theme.VaultGuardTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_STEALTH_WAKE = "extra_stealth_wake"
        const val EXTRA_WAKE_SOURCE = "extra_wake_source"
        const val EXTRA_HANDOFF_TARGET_ALIAS = "extra_handoff_target_alias"
        const val EXTRA_PRESERVE_UNLOCK_TOKEN = "extra_preserve_unlock_token"
    }

    private val resumeTick = MutableStateFlow(0)
    private val backgroundLockTick = MutableStateFlow(0)
    private val pendingStealthWake = MutableStateFlow<String?>(null)
    private val pendingAliasHandoffUnlock = MutableStateFlow(false)

    @Volatile
    private var suppressNextBackgroundLock: Boolean = false

    private var activeViewModel: VaultViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enforceSecureScreen()
        enableEdgeToEdge()
        LauncherDisguiseManager.applyRecentsIdentity(
            activity = this,
            disguise = DisguiseIdentity.fromName(AppGuardRuntimeCache.config.disguiseName),
            hideFromLauncher = AppGuardRuntimeCache.config.isAppHidden,
        )
        handleIncomingIntent(intent)

        val app = application as VaultGuardApp

        setContent {
            VaultGuardTheme {
                val scope = rememberCoroutineScope()
                val auth = remember { Firebase.auth }
                val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)

                LaunchedEffect(Unit) {
                    if (auth.currentUser == null) {
                        GoogleAuthHelper.attemptAutoSignIn(this@MainActivity)
                    }
                }

                val user = currentUser
                if (user == null) {
                    AuthScreen(
                        onAuthSuccess = {
                            // authStateFlow updates automatically upon Firebase sign-in
                        },
                    )
                } else {
                    key(user.uid) {
                        val viewModel: VaultViewModel = viewModel(
                            key = user.uid,
                            factory = VaultViewModelFactory(
                                repository = app.repository,
                                cloudSyncRepository = app.cloudSyncRepository,
                                otaUpdateManager = app.otaUpdateManager,
                                currentUserId = user.uid,
                                userDisplayName = user.displayName.orEmpty(),
                                userEmail = user.email.orEmpty(),
                            ),
                        )
                        activeViewModel = viewModel

                        val tick by resumeTick.collectAsStateWithLifecycle()
                        LaunchedEffect(tick) {
                            viewModel.refreshSystemStatus()
                        }

                        val bgLockTick by backgroundLockTick.collectAsStateWithLifecycle()

                        val wakeSource by pendingStealthWake.collectAsStateWithLifecycle()
                        LaunchedEffect(wakeSource) {
                            if (wakeSource != null) {
                                viewModel.onStealthWakeReceived(wakeSource)
                                pendingStealthWake.value = null
                            }
                        }

                        val aliasHandoffUnlock by pendingAliasHandoffUnlock.collectAsStateWithLifecycle()
                        LaunchedEffect(aliasHandoffUnlock) {
                            if (aliasHandoffUnlock) {
                                viewModel.restoreSessionUnlockedFromAliasHandoff()
                                pendingAliasHandoffUnlock.value = false
                            }
                        }

                        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                        LaunchedEffect(
                            uiState.isConfigLoaded,
                            uiState.activeDisguise,
                            uiState.config.isAppHidden,
                            tick,
                        ) {
                            LauncherDisguiseManager.applyRecentsIdentity(
                                activity = this@MainActivity,
                                disguise = uiState.activeDisguise,
                                hideFromLauncher = uiState.config.isAppHidden,
                            )
                            val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
                            am?.appTasks?.forEach { task ->
                                runCatching { task.setExcludeFromRecents(uiState.config.isAppHidden) }
                            }
                            if (uiState.isConfigLoaded) {
                                handoffTaskToActiveDisguiseAliasIfNeeded(
                                    disguise = uiState.activeDisguise,
                                    isAppHidden = uiState.config.isAppHidden,
                                    preserveUnlock = uiState.isSessionUnlocked,
                                )
                            }
                        }
                        var showUnlockFromDisguise by remember(
                            uiState.isSessionUnlocked,
                            uiState.stealthWakeActive,
                            bgLockTick,
                        ) {
                            mutableStateOf(false)
                        }

                        when {
                            !uiState.isConfigLoaded -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.background),
                                )
                            }

                            !uiState.config.isInitialized -> {
                                SetupScreen(
                                    onActivateVault = { password, wakeWord, dialerPin, securityQuestions ->
                                        viewModel.initializeAdmin(
                                            password = password,
                                            wakeWord = wakeWord,
                                            dialerPin = dialerPin,
                                            securityQuestions = securityQuestions,
                                        )
                                    },
                                )
                            }

                            uiState.isSessionUnlocked -> {
                                VaultConsoleHost(
                                    state = uiState,
                                    viewModel = viewModel,
                                    onOpenAccessibilitySettings = ::openAccessibilitySettings,
                                    onRequestDeviceAdmin = ::requestOrManageDeviceAdmin,
                                    onOpenNotificationListenerSettings = ::openNotificationListenerSettings,
                                    onSuppressNextBackgroundLock = {
                                        suppressNextBackgroundLock = true
                                    },
                                    onSignOut = {
                                        scope.launch {
                                            GoogleAuthHelper.signOutWithCleanup(this@MainActivity)
                                        }
                                    },
                                )
                            }

                            uiState.activeDisguise != DisguiseIdentity.DEFAULT &&
                                !uiState.stealthWakeActive &&
                                !showUnlockFromDisguise -> {
                                DisguiseFacadeScreen(
                                    identity = uiState.activeDisguise,
                                    onCheckWakeTrigger = viewModel::checkWakeOrDialerInDisguise,
                                    onTriggerAdminUnlock = { showUnlockFromDisguise = true },
                                )
                            }

                            else -> {
                                if (showUnlockFromDisguise) {
                                    BackHandler { showUnlockFromDisguise = false }
                                }
                                AdminUnlockScreen(
                                    isHiddenMode = uiState.config.isAppHidden,
                                    stealthWakeSource = uiState.wakeSource,
                                    authError = uiState.authError,
                                    failedAttempts = uiState.failedUnlockAttempts,
                                    securityQuestions = uiState.securityQuestions,
                                    onUnlock = { password ->
                                        viewModel.unlockAdminSession(password)
                                    },
                                    onClearError = viewModel::clearAuthError,
                                    onVerifySecurityAnswers = viewModel::verifySecurityAnswersForReset,
                                    onResetPassword = viewModel::resetPasswordViaSecurityQuestions,
                                    onReturnToDisguise = if (uiState.activeDisguise != DisguiseIdentity.DEFAULT && !uiState.stealthWakeActive) {
                                        { showUnlockFromDisguise = false }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        enforceSecureScreen()
        suppressNextBackgroundLock = false
        AppGuardRuntimeCache.setForegroundActive(true)
        AppGuardRuntimeCache.onForegroundPackageChanged(packageName, System.currentTimeMillis())
        LauncherDisguiseManager.applyRecentsIdentity(
            activity = this,
            disguise = DisguiseIdentity.fromName(AppGuardRuntimeCache.config.disguiseName),
            hideFromLauncher = AppGuardRuntimeCache.config.isAppHidden,
        )
        activeViewModel?.refreshSystemStatus()
        resumeTick.value += 1
    }

    override fun onPause() {
        AppGuardRuntimeCache.setForegroundActive(false)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            activeViewModel?.refreshSystemStatus()
            resumeTick.value += 1
        }
    }

    override fun onStop() {
        super.onStop()
        AppGuardRuntimeCache.setForegroundActive(false)
        if (!isChangingConfigurations) {
            activeViewModel?.lockVaultMedia()
            if (!suppressNextBackgroundLock) {
                activeViewModel?.lockSession()
                backgroundLockTick.value += 1
            }
        }
    }

    private fun enforceSecureScreen() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_STEALTH_WAKE, false) == true) {
            pendingStealthWake.value = intent.getStringExtra(EXTRA_WAKE_SOURCE) ?: "Stealth"
        }
        val handoffToken = intent?.getStringExtra(EXTRA_PRESERVE_UNLOCK_TOKEN)
        if (AppGuardRuntimeCache.consumeDisguiseSwitchUnlockToken(handoffToken)) {
            pendingAliasHandoffUnlock.value = true
        }
    }

    private fun handoffTaskToActiveDisguiseAliasIfNeeded(
        disguise: DisguiseIdentity,
        isAppHidden: Boolean,
        preserveUnlock: Boolean,
    ) {
        if (isFinishing || isDestroyed) return
        val effective = LauncherDisguiseManager.resolveEffectiveDisguise(disguise, isAppHidden)
        val desiredAlias = effective.aliasClassName
        val currentComponentClass = intent?.component?.className
        if (currentComponentClass == desiredAlias) return
        if (intent?.getStringExtra(EXTRA_HANDOFF_TARGET_ALIAS) == desiredAlias) return

        val aliasManager = LauncherDisguiseManager(this)
        if (!aliasManager.isAliasEnabled(effective)) return

        val handoffIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(packageName, desiredAlias)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(EXTRA_HANDOFF_TARGET_ALIAS, desiredAlias)
            if (intent?.getBooleanExtra(EXTRA_STEALTH_WAKE, false) == true) {
                putExtra(EXTRA_STEALTH_WAKE, true)
                putExtra(EXTRA_WAKE_SOURCE, intent?.getStringExtra(EXTRA_WAKE_SOURCE))
            }
            if (preserveUnlock) {
                putExtra(
                    EXTRA_PRESERVE_UNLOCK_TOKEN,
                    AppGuardRuntimeCache.issueDisguiseSwitchUnlockToken(),
                )
            }
        }

        if (packageManager.resolveActivity(handoffIntent, 0) == null) return

        suppressNextBackgroundLock = true
        startActivity(handoffIntent)
        finishAndRemoveTask()
    }

    private fun openAccessibilitySettings() {
        suppressNextBackgroundLock = true
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
    }

    private fun openNotificationListenerSettings() {
        suppressNextBackgroundLock = true
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
    }

    private fun requestOrManageDeviceAdmin() {
        suppressNextBackgroundLock = true
        val adminComponent = ComponentName(this, VaultDeviceAdminReceiver::class.java)
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isAdminActive(adminComponent)) {
            runCatching { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
        } else {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.device_admin_description),
                )
            }
            runCatching { startActivity(intent) }
        }
    }
}
