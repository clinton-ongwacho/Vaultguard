package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.example.GatekeeperActivity
import com.example.MainActivity
import com.example.data.AppGuardRuntimeCache
import com.example.data.entity.NotificationPrivacyMode

class AppGuardAccessibilityService : AccessibilityService() {

    private var lastRedirectedPackage: String? = null
    private var lastRedirectTimeMillis: Long = 0L
    private var lastWakeTriggerMillis: Long = 0L
    private var lastVaultGuardSettingsAtMillis: Long = 0L
    private var lastForegroundProbeMillis: Long = 0L
    private var screenOffReceiver: BroadcastReceiver? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingTimerPkg: String? = null
    private val timerExpireRunnable = Runnable {
        pendingTimerPkg?.let { pkg ->
            AppGuardRuntimeCache.isPackageCurrentlyBlocked(pkg, System.currentTimeMillis())
        }
        pendingTimerPkg = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppGuardRuntimeCache.setAccessibilityConnected(true)

        if (screenOffReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                        AppGuardRuntimeCache.clearSessionUnlock()
                    }
                }
            }
            screenOffReceiver = receiver
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AppGuardRuntimeCache.setAccessibilityConnected(false)
        mainHandler.removeCallbacks(timerExpireRunnable)
        pendingTimerPkg = null
        screenOffReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenOffReceiver = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowChanged(pkg, event, isContentChange = false)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = System.currentTimeMillis()
                val hasActiveSession = AppGuardRuntimeCache.activeSessionPackage != null
                if (hasActiveSession && (now - lastForegroundProbeMillis) >= 250L &&
                    !isSystemOverlayOrIme(pkg) &&
                    !(pkg == packageName && event.className?.toString()?.contains("GatekeeperActivity") == true)
                ) {
                    lastForegroundProbeMillis = now
                    updateForegroundSessionAndTimer(pkg, now)
                }
                if (isSettingsOrAppDetail(pkg) || isPackageInstaller(pkg)) {
                    handleWindowChanged(pkg, event, isContentChange = true)
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleTextChanged(pkg, event)
        }
    }

    private fun updateForegroundSessionAndTimer(pkg: String, now: Long) {
        val beforeActivePkg = AppGuardRuntimeCache.activeSessionPackage
        AppGuardRuntimeCache.onForegroundPackageChanged(pkg, now)
        val afterActivePkg = AppGuardRuntimeCache.activeSessionPackage

        if (afterActivePkg == null) {
            if (pendingTimerPkg != null) {
                mainHandler.removeCallbacks(timerExpireRunnable)
                pendingTimerPkg = null
            }
            return
        }

        if (pkg == afterActivePkg) {
            if (pendingTimerPkg != null) {
                mainHandler.removeCallbacks(timerExpireRunnable)
                pendingTimerPkg = null
            }
        } else if (beforeActivePkg == afterActivePkg && pkg != beforeActivePkg) {
            val timeoutSec = AppGuardRuntimeCache.config.immediateLockTimeoutSeconds
            if (timeoutSec > 0 && pendingTimerPkg != afterActivePkg) {
                pendingTimerPkg = afterActivePkg
                mainHandler.removeCallbacks(timerExpireRunnable)
                mainHandler.postDelayed(timerExpireRunnable, timeoutSec * 1000L + 60L)
            }
        }
    }

    private fun handleWindowChanged(pkg: String, event: AccessibilityEvent, isContentChange: Boolean) {
        val className = event.className?.toString().orEmpty()
        if (pkg == packageName && className.contains("GatekeeperActivity")) return
        if (isSystemOverlayOrIme(pkg)) return

        val now = System.currentTimeMillis()
        if (!isContentChange) {
            lastForegroundProbeMillis = now
            updateForegroundSessionAndTimer(pkg, now)
        }

        if (pkg == packageName) return

        if (isLauncherPackage(pkg)) {
            lastVaultGuardSettingsAtMillis = 0L
            return
        }

        val config = AppGuardRuntimeCache.config
        if (!config.isInitialized) return

        // 1. Check Limited Apps Redirect
        if (!isContentChange && AppGuardRuntimeCache.isPackageCurrentlyBlocked(pkg)) {
            lastVaultGuardSettingsAtMillis = 0L
            val rule = AppGuardRuntimeCache.getRule(pkg)
            if (config.notificationBlockEnabled &&
                (rule?.notificationPrivacyEnum ?: NotificationPrivacyMode.SILENT_AND_HIDE) ==
                NotificationPrivacyMode.SILENT_AND_HIDE
            ) {
                CallSilenceHelper.silenceIfRinging(this)
            }
            if (shouldDebounceRedirect(pkg, now)) return
            lastRedirectedPackage = pkg
            lastRedirectTimeMillis = now

            val intent = Intent(this, GatekeeperActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK
                )
                putExtra(GatekeeperActivity.EXTRA_MODE, GatekeeperActivity.MODE_REDIRECT_APP)
                putExtra(GatekeeperActivity.EXTRA_TARGET_PACKAGE, pkg)
            }
            runCatching { startActivity(intent) }
            return
        }

        // 2. Check Cache/Storage Clear, Uninstall Protection & Package Install Guard in Settings / Package Installer
        val isInstaller = isPackageInstaller(pkg)
        val isSettings = isSettingsOrAppDetail(pkg)

        if (!isSettings && !isInstaller) {
            lastVaultGuardSettingsAtMillis = 0L
            return
        }

        if (config.uninstallProtectionEnabled &&
            now > AppGuardRuntimeCache.uninstallAuthorizedUntilMillis
        ) {
            val windowText = collectVisibleText(event)
            val mentionsVaultGuard = windowText.contains("VaultGuard", ignoreCase = true) ||
                windowText.contains(packageName, ignoreCase = true)

            if (mentionsVaultGuard) {
                lastVaultGuardSettingsAtMillis = now
            } else if (
                windowText.contains("All apps", ignoreCase = true) ||
                windowText.contains("Installed apps", ignoreCase = true) ||
                windowText.contains("App management", ignoreCase = true) ||
                windowText.contains("Search apps", ignoreCase = true)
            ) {
                lastVaultGuardSettingsAtMillis = 0L
            }

            val inRecentVaultGuardSettings = (now - lastVaultGuardSettingsAtMillis) in 0L..30_000L
            val isOurSettingsScreen = mentionsVaultGuard || (isSettings && inRecentVaultGuardSettings)

            val isSensitiveAction = windowText.contains("Uninstall", ignoreCase = true) ||
                windowText.contains("Deactivate", ignoreCase = true) ||
                windowText.contains("Remove active admin", ignoreCase = true) ||
                windowText.contains("Force stop", ignoreCase = true) ||
                windowText.contains("Clear cache", ignoreCase = true) ||
                windowText.contains("Clear data", ignoreCase = true) ||
                windowText.contains("Clear storage", ignoreCase = true) ||
                windowText.contains("Manage space", ignoreCase = true) ||
                windowText.contains("Storage & cache", ignoreCase = true) ||
                windowText.contains("Storage usage", ignoreCase = true)

            if (isOurSettingsScreen && isSensitiveAction && !shouldDebounceRedirect(pkg, now)) {
                lastRedirectedPackage = pkg
                lastRedirectTimeMillis = now
                val intent = Intent(this, GatekeeperActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                    putExtra(GatekeeperActivity.EXTRA_MODE, GatekeeperActivity.MODE_UNINSTALL_GUARD)
                    putExtra(GatekeeperActivity.EXTRA_TARGET_PACKAGE, pkg)
                }
                runCatching { startActivity(intent) }
                return
            }
        }

        if (config.installGuardEnabled &&
            isInstaller &&
            now > AppGuardRuntimeCache.installAuthorizedUntilMillis
        ) {
            val windowText = collectVisibleText(event)
            val isInstallPrompt = windowText.contains("Do you want to install", ignoreCase = true) ||
                (windowText.contains("Install", ignoreCase = true) &&
                    !windowText.contains("Uninstall", ignoreCase = true))

            if (isInstallPrompt && !shouldDebounceRedirect(pkg, now)) {
                lastRedirectedPackage = pkg
                lastRedirectTimeMillis = now
                val intent = Intent(this, GatekeeperActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                    putExtra(GatekeeperActivity.EXTRA_MODE, GatekeeperActivity.MODE_INSTALL_GUARD)
                    putExtra(GatekeeperActivity.EXTRA_TARGET_PACKAGE, pkg)
                }
                runCatching { startActivity(intent) }
            }
        }
    }

    private fun isSettingsOrAppDetail(pkg: String): Boolean {
        return pkg == "com.android.settings" ||
            pkg == "com.oplus.appdetail" ||
            pkg == "com.coloros.appmanager" ||
            pkg == "com.miui.securitycenter"
    }

    private fun isPackageInstaller(pkg: String): Boolean {
        return pkg.contains("packageinstaller", ignoreCase = true)
    }

    private fun handleTextChanged(pkg: String, event: AccessibilityEvent) {
        if (event.isPassword) return
        val config = AppGuardRuntimeCache.config
        if (!config.isInitialized) return

        val typed = event.text.joinToString(" ").trim()
        if (typed.isEmpty()) return

        val now = System.currentTimeMillis()
        if (now - lastWakeTriggerMillis < 2_500L) return

        val isDialer = pkg.contains("dialer", ignoreCase = true) ||
            pkg.contains("phone", ignoreCase = true) ||
            pkg.contains("contacts", ignoreCase = true)

        val matched = if (isDialer) {
            AppGuardRuntimeCache.matchesDialerSecret(typed)
        } else {
            val isOwnApp = pkg == packageName
            !isOwnApp && AppGuardRuntimeCache.matchesWakeWord(typed)
        }

        if (matched) {
            lastWakeTriggerMillis = now
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(MainActivity.EXTRA_STEALTH_WAKE, true)
                putExtra(MainActivity.EXTRA_WAKE_SOURCE, if (isDialer) "Dialer" else "Search Bar")
            }
            runCatching { startActivity(intent) }
        }
    }

    private fun shouldDebounceRedirect(pkg: String, now: Long): Boolean {
        return lastRedirectedPackage == pkg && (now - lastRedirectTimeMillis) < 850L
    }

    private fun isSystemOverlayOrIme(pkg: String): Boolean {
        return pkg == "com.android.systemui" ||
            pkg.contains("inputmethod", ignoreCase = true) ||
            pkg.contains("gboard", ignoreCase = true)
    }

    private fun isLauncherPackage(pkg: String): Boolean {
        return pkg.contains("launcher", ignoreCase = true) ||
            pkg == "com.google.android.apps.nexuslauncher" ||
            pkg == "com.android.launcher3"
    }

    private fun collectVisibleText(event: AccessibilityEvent): String {
        val sb = StringBuilder()
        event.text.forEach { sb.append(it).append(' ') }
        val root = runCatching { rootInActiveWindow }.getOrNull()
        if (root != null) {
            collectNodeText(root, sb, depth = 0)
        }
        return sb.toString()
    }

    private fun collectNodeText(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 5 || sb.length > 1500) return
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodeText(child, sb, depth + 1)
        }
    }

    override fun onInterrupt() {
        // No-op
    }
}
