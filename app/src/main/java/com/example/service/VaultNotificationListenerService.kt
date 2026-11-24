package com.example.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.GatekeeperActivity
import com.example.R
import com.example.data.AppGuardRuntimeCache
import com.example.data.entity.NotificationPrivacyMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class VaultNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val HIDDEN_ALERTS_CHANNEL_ID = "vaultguard_hidden_alerts"
        private const val MASKED_TAG_PREFIX = "vg_masked_"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcherJob: Job? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        AppGuardRuntimeCache.setNotificationListenerConnected(true)
        ensureHiddenAlertChannel()
        sweepBlockedNotifications()

        watcherJob?.cancel()
        watcherJob = scope.launch {
            AppGuardRuntimeCache.blockingVersion.collectLatest {
                sweepBlockedNotifications()
            }
        }
    }

    override fun onListenerDisconnected() {
        AppGuardRuntimeCache.setNotificationListenerConnected(false)
        watcherJob?.cancel()
        CallSilenceHelper.restoreIfActive(this, this)
        super.onListenerDisconnected()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AppGuardRuntimeCache.setNotificationListenerConnected(false)
        watcherJob?.cancel()
        CallSilenceHelper.restoreIfActive(this, this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        CallSilenceHelper.restoreIfActive(this, this)
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        val pkg = notification.packageName ?: return
        if (pkg == packageName) return

        val config = AppGuardRuntimeCache.config
        if (!AppGuardRuntimeCache.isPackageCurrentlyBlocked(pkg)) {
            cancelMaskedNotification(pkg)
            return
        }

        val rule = AppGuardRuntimeCache.getRule(pkg)
        val privacyMode = rule?.notificationPrivacyEnum ?: NotificationPrivacyMode.SILENT_AND_HIDE
        when (privacyMode) {
            NotificationPrivacyMode.SILENT_AND_HIDE -> {
                if (config.notificationBlockEnabled) {
                    hideAndSilenceNotification(notification)
                }
                cancelMaskedNotification(pkg)
            }
            NotificationPrivacyMode.SHOW_HIDE_SENSITIVE -> {
                replaceWithHiddenSensitiveNotification(
                    sbn = notification,
                    appLabel = rule?.appName ?: pkg,
                )
            }
        }
    }

    private fun sweepBlockedNotifications() {
        val config = AppGuardRuntimeCache.config
        if (!config.guardActive) {
            cancelAllMaskedNotifications()
            return
        }

        val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        nm?.activeNotifications?.forEach { posted ->
            val tag = posted.tag
            if (tag != null && tag.startsWith(MASKED_TAG_PREFIX)) {
                val targetPkg = tag.removePrefix(MASKED_TAG_PREFIX)
                if (!AppGuardRuntimeCache.isPackageCurrentlyBlocked(targetPkg)) {
                    nm.cancel(tag, posted.id)
                }
            }
        }

        val active = runCatching { activeNotifications }.getOrNull() ?: return
        for (sbn in active) {
            val pkg = sbn.packageName ?: continue
            if (pkg == packageName) continue
            if (!AppGuardRuntimeCache.isPackageCurrentlyBlocked(pkg)) continue

            val rule = AppGuardRuntimeCache.getRule(pkg)
            val privacyMode = rule?.notificationPrivacyEnum ?: NotificationPrivacyMode.SILENT_AND_HIDE
            if (privacyMode == NotificationPrivacyMode.SILENT_AND_HIDE) {
                if (config.notificationBlockEnabled) {
                    hideAndSilenceNotification(sbn)
                }
            } else {
                replaceWithHiddenSensitiveNotification(
                    sbn = sbn,
                    appLabel = rule?.appName ?: pkg,
                )
            }
        }
    }

    private fun hideAndSilenceNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val isCall = CallSilenceHelper.isIncomingCallNotification(notification)

        // Silence ringtone/notification audio and vibration immediately.
        CallSilenceHelper.silenceRestrictedAlert(
            context = this,
            listener = this,
            isCall = isCall,
        )

        // If this is an incoming VoIP/app call (e.g., WhatsApp), decline it cleanly so ringing stops.
        if (isCall) {
            CallSilenceHelper.findDeclinePendingIntent(notification)?.let { declineIntent ->
                runCatching { declineIntent.send() }
            }
        }

        // Hide the notification and snooze ongoing/call notifications so nothing remains visible.
        runCatching { cancelNotification(sbn.key) }
        if ((isCall || !sbn.isClearable) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { snoozeNotification(sbn.key, 60 * 60_000L) }
        }
    }

    private fun replaceWithHiddenSensitiveNotification(
        sbn: StatusBarNotification,
        appLabel: String,
    ) {
        // Skip hiding summary-only empty group headers without cancelling user alerts
        val pkg = sbn.packageName ?: return
        runCatching { cancelNotification(sbn.key) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureHiddenAlertChannel()
        val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val unlockIntent = Intent(this, GatekeeperActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
            putExtra(GatekeeperActivity.EXTRA_MODE, GatekeeperActivity.MODE_REDIRECT_APP)
            putExtra(GatekeeperActivity.EXTRA_TARGET_PACKAGE, pkg)
        }
        val pendingUnlockIntent = PendingIntent.getActivity(
            this,
            pkg.hashCode(),
            unlockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val masked = NotificationCompat.Builder(this, HIDDEN_ALERTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_lock)
            .setContentTitle(appLabel)
            .setContentText("New notification • Content hidden until password unlock")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingUnlockIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        runCatching {
            nm.notify("$MASKED_TAG_PREFIX$pkg", pkg.hashCode(), masked)
        }
    }

    private fun cancelMaskedNotification(pkg: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { nm.cancel("$MASKED_TAG_PREFIX$pkg", pkg.hashCode()) }
    }

    private fun cancelAllMaskedNotifications() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.activeNotifications.forEach { posted ->
            val tag = posted.tag
            if (tag != null && tag.startsWith(MASKED_TAG_PREFIX)) {
                nm.cancel(tag, posted.id)
            }
        }
    }

    private fun ensureHiddenAlertChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(HIDDEN_ALERTS_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            HIDDEN_ALERTS_CHANNEL_ID,
            "Hidden Protected App Alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Generic notifications for protected apps hiding sensitive message details until unlocked."
        }
        nm.createNotificationChannel(channel)
    }
}

