package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.example.data.AppGuardRuntimeCache
import com.example.data.InstalledAppInfo
import com.example.data.entity.RedirectDecoyMode
import com.example.data.entity.RedirectDisplayStyle
import com.example.security.IntruderPhotoCapturer
import com.example.ui.screens.RedirectGateScreen
import com.example.ui.theme.VaultGuardTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class GatekeeperActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"

        const val MODE_REDIRECT_APP = "redirect_app"
        const val MODE_UNINSTALL_GUARD = "uninstall_guard"
        const val MODE_INSTALL_GUARD = "install_guard"
    }

    private var mode by mutableStateOf(MODE_REDIRECT_APP)
    private var targetPackage by mutableStateOf("")
    private var targetAppInfo by mutableStateOf<InstalledAppInfo?>(null)
    private var errorMessage by mutableStateOf<String?>(null)
    private var failedAttempts by mutableIntStateOf(0)
    private var hasAuthorizedLaunch = false
    private var capturedPhotoPath: String? = null
    private var photoCaptureJob: Job? = null

    private val repository by lazy { (application as VaultGuardApp).repository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    goHomeAndFinish()
                }
            },
        )

        handleIntent(intent)

        setContent {
            VaultGuardTheme {
                RedirectGateScreen(
                    mode = mode,
                    targetPackage = targetPackage,
                    targetAppInfo = targetAppInfo,
                    redirectDisplayStyle = RedirectDisplayStyle.fromName(
                        AppGuardRuntimeCache.config.redirectDisplayName
                    ),
                    redirectDecoyMode = RedirectDecoyMode.fromName(
                        AppGuardRuntimeCache.config.redirectDecoyMode
                    ),
                    errorMessage = errorMessage,
                    failedAttempts = failedAttempts,
                    onGoHome = { goHomeAndFinish() },
                    onVerifyAndProceed = { password ->
                        verifyAndAuthorize(password)
                    },
                    onClearError = { errorMessage = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        if (!hasAuthorizedLaunch && !isChangingConfigurations && !isFinishing) {
            finishAndRemoveTask()
        }
    }

    private fun handleIntent(intent: Intent?) {
        hasAuthorizedLaunch = false
        capturedPhotoPath = null
        photoCaptureJob?.cancel()
        val requestedMode = intent?.getStringExtra(EXTRA_MODE)
        targetPackage = intent?.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()
        mode = requestedMode ?: MODE_UNINSTALL_GUARD
        errorMessage = null
        failedAttempts = 0

        val info = if (targetPackage.isNotBlank()) {
            repository.getInstalledAppInfo(targetPackage)
        } else {
            null
        }
        targetAppInfo = info

        val decoyMode = RedirectDecoyMode.fromName(AppGuardRuntimeCache.config.redirectDecoyMode)

        if (mode == MODE_REDIRECT_APP && targetPackage.isNotBlank()) {
            val label = info?.appName ?: targetPackage
            photoCaptureJob = lifecycleScope.launch {
                val eventId = repository.recordRedirectBlock(
                    packageName = targetPackage,
                    appName = label,
                    decoyMode = decoyMode,
                )
                if (AppGuardRuntimeCache.config.capturePhotoOnOpenEnabled) {
                    val photoPath = IntruderPhotoCapturer.captureFrontPhoto(
                        context = this@GatekeeperActivity,
                        lifecycleOwner = this@GatekeeperActivity,
                        targetPackage = targetPackage,
                    )
                    if (photoPath != null) {
                        capturedPhotoPath = photoPath
                        if (eventId != null) {
                            repository.attachPhotoToEvent(eventId, photoPath)
                        }
                    }
                }
            }
        }
    }

    private fun verifyAndAuthorize(secret: String) {
        if (secret.isBlank()) {
            errorMessage = "Enter password."
            return
        }

        lifecycleScope.launch {
            val isValid = repository.verifyAdminPassword(secret)
            if (!isValid) {
                failedAttempts += 1
                errorMessage = "Incorrect password."
                val label = targetAppInfo?.appName ?: targetPackage.ifBlank { "System" }
                repository.logEvent(
                    packageName = targetPackage.ifBlank { packageName },
                    appName = label,
                    eventType = "AUTH_FAILED",
                    detail = mode,
                    photoPath = capturedPhotoPath,
                )
                return@launch
            }

            hasAuthorizedLaunch = true
            photoCaptureJob?.cancel()
            when (mode) {
                MODE_REDIRECT_APP -> {
                    val label = targetAppInfo?.appName ?: targetPackage
                    repository.grantSessionUnlock(
                        packageName = targetPackage,
                        appName = label,
                        photoPath = capturedPhotoPath,
                    )
                    val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    }
                    if (launchIntent != null) {
                        startActivity(launchIntent)
                    }
                    finishAndRemoveTask()
                }

                MODE_UNINSTALL_GUARD -> {
                    AppGuardRuntimeCache.uninstallAuthorizedUntilMillis =
                        System.currentTimeMillis() + 120_000L
                    repository.logEvent(
                        packageName = packageName,
                        appName = "VaultGuard",
                        eventType = "UNINSTALL_AUTHORIZED",
                        detail = "Authorized",
                    )
                    finishAndRemoveTask()
                }

                MODE_INSTALL_GUARD -> {
                    AppGuardRuntimeCache.installAuthorizedUntilMillis =
                        System.currentTimeMillis() + 120_000L
                    repository.logEvent(
                        packageName = targetPackage.ifBlank { "packageinstaller" },
                        appName = "Package Installer",
                        eventType = "INSTALL_AUTHORIZED",
                        detail = "Authorized",
                    )
                    finishAndRemoveTask()
                }
            }
        }
    }

    private fun goHomeAndFinish() {
        hasAuthorizedLaunch = false
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finishAndRemoveTask()
    }
}
