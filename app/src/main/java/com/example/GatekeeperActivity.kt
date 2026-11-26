package com.example

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.example.data.AppGuardRuntimeCache
import com.example.data.InstalledAppInfo
import com.example.data.entity.DisguiseIdentity
import com.example.data.entity.RedirectDecoyMode
import com.example.data.entity.RedirectDisplayStyle
import com.example.launcher.LauncherDisguiseManager
import com.example.security.IntruderPhotoCapturer
import com.example.ui.screens.RedirectGateScreen
import com.example.ui.theme.VaultGuardTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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
    private var isActionBusy by mutableStateOf(false)

    private var hasAuthorizedLaunch = false
    private var suppressAutoCloseOnStop = false
    private var capturedPhotoPath: String? = null
    private var photoCaptureJob: Job? = null
    private var sessionGateEventId: Long? = null
    private val sessionEventIds = mutableListOf<Long>()
    private val sessionRecordMutex = Mutex()
    private var activeSessionPkg: String = ""
    private var activeSessionOpenedAtMillis: Long = 0L
    private var pendingSecretAfterCameraGrant: String? = null

    private val repository by lazy { (application as VaultGuardApp).repository }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        suppressAutoCloseOnStop = false
        val pendingSecret = pendingSecretAfterCameraGrant
        pendingSecretAfterCameraGrant = null
        if (granted) {
            errorMessage = null
            if (mode == MODE_REDIRECT_APP && targetPackage.isNotBlank()) {
                val label = targetAppInfo?.appName ?: targetPackage
                val decoyMode = RedirectDecoyMode.fromName(AppGuardRuntimeCache.config.redirectDecoyMode)
                startSessionCaptureIfNeeded(targetPackage, label, decoyMode)
                if (!pendingSecret.isNullOrBlank()) {
                    verifyAndAuthorize(pendingSecret)
                }
            }
        } else {
            errorMessage = "Camera permission is required to capture session photo."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        enableEdgeToEdge()
        LauncherDisguiseManager.applyRecentsIdentity(
            activity = this,
            disguise = DisguiseIdentity.fromName(AppGuardRuntimeCache.config.disguiseName),
            hideFromLauncher = AppGuardRuntimeCache.config.isAppHidden,
        )

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
                    isBusy = isActionBusy,
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

    override fun onResume() {
        super.onResume()
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        LauncherDisguiseManager.applyRecentsIdentity(
            activity = this,
            disguise = DisguiseIdentity.fromName(AppGuardRuntimeCache.config.disguiseName),
            hideFromLauncher = AppGuardRuntimeCache.config.isAppHidden,
        )
    }

    override fun onStop() {
        super.onStop()
        if (suppressAutoCloseOnStop || hasAuthorizedLaunch || isChangingConfigurations || isFinishing) {
            return
        }
        val activeJob = photoCaptureJob
        if (activeJob != null && activeJob.isActive) {
            activeJob.invokeOnCompletion {
                if (!hasAuthorizedLaunch && !isChangingConfigurations && !isFinishing && !isDestroyed) {
                    runOnUiThread {
                        if (!hasAuthorizedLaunch && !isFinishing && !isDestroyed) {
                            finishAndRemoveTask()
                        }
                    }
                }
            }
        } else {
            finishAndRemoveTask()
        }
    }

    private fun handleIntent(intent: Intent?) {
        val requestedMode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_UNINSTALL_GUARD
        val requestedPackage = intent?.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()
        val now = System.currentTimeMillis()

        val sameActiveSession = requestedMode == MODE_REDIRECT_APP &&
            mode == MODE_REDIRECT_APP &&
            requestedPackage.isNotBlank() &&
            requestedPackage == activeSessionPkg &&
            (
                photoCaptureJob?.isActive == true ||
                    (repository.hasValidPhotoFile(capturedPhotoPath) && now - activeSessionOpenedAtMillis < 3_000L)
                )

        if (sameActiveSession) {
            if (targetAppInfo == null) {
                targetAppInfo = repository.getInstalledAppInfo(requestedPackage)
            }
            return
        }

        hasAuthorizedLaunch = false
        suppressAutoCloseOnStop = false
        isActionBusy = false
        pendingSecretAfterCameraGrant = null
        capturedPhotoPath = null
        sessionGateEventId = null
        sessionEventIds.clear()
        photoCaptureJob?.cancel()
        photoCaptureJob = null

        targetPackage = requestedPackage
        mode = requestedMode
        activeSessionPkg = requestedPackage
        activeSessionOpenedAtMillis = now
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
            startSessionCaptureIfNeeded(targetPackage, label, decoyMode)
        }
    }

    private fun startSessionCaptureIfNeeded(
        pkg: String,
        label: String,
        decoyMode: RedirectDecoyMode,
    ) {
        if (photoCaptureJob?.isActive == true) return
        photoCaptureJob = lifecycleScope.launch {
            captureAndPersistGateSessionLocked(
                pkg = pkg,
                label = label,
                decoyMode = decoyMode,
                allowRecordWithoutPhotoIfNoCamera = !repository.hasAnyCameraHardware(),
            )
        }
    }

    private suspend fun ensureSessionCapturedAndRecorded(): String? {
        photoCaptureJob?.join()
        val pkg = targetPackage
        if (mode != MODE_REDIRECT_APP || pkg.isBlank()) return capturedPhotoPath
        val label = targetAppInfo?.appName ?: pkg
        val decoyMode = RedirectDecoyMode.fromName(AppGuardRuntimeCache.config.redirectDecoyMode)
        return captureAndPersistGateSessionLocked(
            pkg = pkg,
            label = label,
            decoyMode = decoyMode,
            allowRecordWithoutPhotoIfNoCamera = !repository.hasAnyCameraHardware(),
        )
    }

    private suspend fun captureAndPersistGateSessionLocked(
        pkg: String,
        label: String,
        decoyMode: RedirectDecoyMode,
        allowRecordWithoutPhotoIfNoCamera: Boolean,
    ): String? = sessionRecordMutex.withLock {
        var validPath = capturedPhotoPath?.takeIf { repository.hasValidPhotoFile(it) }
        if (validPath == null && repository.isCameraPermissionGranted()) {
            val captured = IntruderPhotoCapturer.captureFrontPhoto(
                context = this@GatekeeperActivity,
                lifecycleOwner = this@GatekeeperActivity,
                targetPackage = pkg,
            )
            if (repository.hasValidPhotoFile(captured)) {
                validPath = captured
                capturedPhotoPath = captured
            }
        }

        if (validPath != null) {
            if (sessionEventIds.isNotEmpty()) {
                repository.attachPhotoToEvents(sessionEventIds.toList(), validPath)
            }
            val existingGateId = sessionGateEventId
            if (existingGateId == null) {
                val createdId = repository.recordRedirectBlock(
                    packageName = pkg,
                    appName = label,
                    decoyMode = decoyMode,
                    photoPath = validPath,
                )
                if (createdId != null) {
                    sessionGateEventId = createdId
                    sessionEventIds.add(createdId)
                }
            } else {
                repository.attachPhotoToEvent(existingGateId, validPath)
            }
        } else if (allowRecordWithoutPhotoIfNoCamera && sessionGateEventId == null) {
            val createdId = repository.recordRedirectBlock(
                packageName = pkg,
                appName = label,
                decoyMode = decoyMode,
                photoPath = null,
            )
            if (createdId != null) {
                sessionGateEventId = createdId
                sessionEventIds.add(createdId)
            }
        }

        validPath
    }

    private fun verifyAndAuthorize(secret: String) {
        if (isActionBusy) return
        if (secret.isBlank()) {
            errorMessage = "Enter password."
            return
        }

        isActionBusy = true
        lifecycleScope.launch {
            try {
                val isValid = repository.verifyAdminPassword(secret)
                if (!isValid) {
                    failedAttempts += 1
                    val lockoutSec = repository.getRemainingAuthLockoutSeconds()
                    errorMessage = if (lockoutSec > 0) {
                        "Too many failed attempts. Try again in ${lockoutSec}s."
                    } else {
                        "Incorrect password."
                    }
                    val label = targetAppInfo?.appName ?: targetPackage.ifBlank { "System" }
                    val validPhoto = capturedPhotoPath?.takeIf { repository.hasValidPhotoFile(it) }
                    val failedId = repository.logEvent(
                        packageName = targetPackage.ifBlank { packageName },
                        appName = label,
                        eventType = "AUTH_FAILED",
                        detail = mode,
                        photoPath = validPhoto,
                    )
                    sessionEventIds.add(failedId)
                    return@launch
                }

                when (mode) {
                    MODE_REDIRECT_APP -> {
                        if (!repository.isCameraPermissionGranted()) {
                            pendingSecretAfterCameraGrant = secret
                            suppressAutoCloseOnStop = true
                            errorMessage = "Camera permission required to capture session photo."
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            return@launch
                        }

                        val photoPath = ensureSessionCapturedAndRecorded()
                        if (photoPath == null && repository.hasAnyCameraHardware()) {
                            errorMessage = "Session photo required. Tap Open again."
                            return@launch
                        }

                        hasAuthorizedLaunch = true
                        val label = targetAppInfo?.appName ?: targetPackage
                        repository.grantSessionUnlock(
                            packageName = targetPackage,
                            appName = label,
                            photoPath = photoPath,
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
                        hasAuthorizedLaunch = true
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
                        hasAuthorizedLaunch = true
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
            } finally {
                if (!hasAuthorizedLaunch) {
                    isActionBusy = false
                }
            }
        }
    }

    private fun goHomeAndFinish() {
        if (isActionBusy || hasAuthorizedLaunch) return
        hasAuthorizedLaunch = false
        if (mode != MODE_REDIRECT_APP || targetPackage.isBlank()) {
            navigateHomeAndFinish()
            return
        }
        isActionBusy = true
        lifecycleScope.launch {
            withTimeoutOrNull(4_000L) {
                ensureSessionCapturedAndRecorded()
            }
            navigateHomeAndFinish()
        }
    }

    private fun navigateHomeAndFinish() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finishAndRemoveTask()
    }
}
