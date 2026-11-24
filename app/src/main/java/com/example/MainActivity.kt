package com.example

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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
    }

    private val resumeTick = MutableStateFlow(0)
    private val backgroundLockTick = MutableStateFlow(0)
    private val pendingStealthWake = MutableStateFlow<String?>(null)

    @Volatile
    private var suppressNextBackgroundLock: Boolean = false

    private var activeViewModel: VaultViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleStealthIntent(intent)

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

                        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                        LaunchedEffect(uiState.config.isAppHidden, tick) {
                            val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
                            am?.appTasks?.forEach { task ->
                                runCatching { task.setExcludeFromRecents(uiState.config.isAppHidden) }
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
                                    onOpenNotificationPolicySettings = ::openNotificationPolicyAccessSettings,
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
        handleStealthIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        suppressNextBackgroundLock = false
        AppGuardRuntimeCache.onForegroundPackageChanged(packageName, System.currentTimeMillis())
        resumeTick.value += 1
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            activeViewModel?.lockVaultMedia()
            if (!suppressNextBackgroundLock) {
                activeViewModel?.lockSession()
                backgroundLockTick.value += 1
            }
        }
    }

    private fun handleStealthIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_STEALTH_WAKE, false) == true) {
            pendingStealthWake.value = intent.getStringExtra(EXTRA_WAKE_SOURCE) ?: "Stealth"
        }
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

    private fun openNotificationPolicyAccessSettings() {
        suppressNextBackgroundLock = true
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
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
