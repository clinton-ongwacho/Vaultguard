package com.example.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.GatekeeperActivity
import com.example.R
import com.example.VaultGuardApp
import com.example.data.AppGuardRuntimeCache
import com.example.data.entity.NotificationPrivacyMode
import com.example.restriction.DetectedChannelInfo
import com.example.restriction.RestrictionStateCodec
import com.example.restriction.UniversalChannelTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

import android.os.Process
import java.lang.ref.WeakReference

class VaultNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val HIDDEN_ALERTS_CHANNEL_ID = "vaultguard_hidden_alerts"
        private const val MASKED_TAG_PREFIX = "vg_masked_"

        @Volatile
        private var activeServiceRef: WeakReference<VaultNotificationListenerService>? = null

        fun refreshChannelsForPackage(packageName: String): Boolean {
            val service = activeServiceRef?.get() ?: return false
            return service.inspectLiveChannelsForPackage(packageName)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcherJob: Job? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeServiceRef = WeakReference(this)
        AppGuardRuntimeCache.setNotificationListenerConnected(true)
        ensureHiddenAlertChannel()
        harvestAllActiveNotificationChannels()
        val tracked = buildSet {
            AppGuardRuntimeCache.activeSetupPackage?.let(::add)
            addAll(AppGuardRuntimeCache.allLimitedPackageNames())
        }
        tracked.forEach { pkg ->
            if (inspectLiveChannelsForPackage(pkg)) {
                persistDiscoveredChannels(pkg)
            }
        }
        AppGuardRuntimeCache.bumpRestrictionStateVersion()
        sweepBlockedNotifications()

        watcherJob?.cancel()
        watcherJob = scope.launch {
            AppGuardRuntimeCache.blockingVersion.collectLatest {
                sweepBlockedNotifications()
            }
        }
    }

    override fun onListenerDisconnected() {
        if (activeServiceRef?.get() === this) {
            activeServiceRef = null
        }
        AppGuardRuntimeCache.setNotificationListenerConnected(false)
        AppGuardRuntimeCache.bumpRestrictionStateVersion()
        watcherJob?.cancel()
        CallSilenceHelper.releaseIfActive(this)
        super.onListenerDisconnected()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (activeServiceRef?.get() === this) {
            activeServiceRef = null
        }
        AppGuardRuntimeCache.setNotificationListenerConnected(false)
        AppGuardRuntimeCache.bumpRestrictionStateVersion()
        watcherJob?.cancel()
        CallSilenceHelper.releaseIfActive(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (activeServiceRef?.get() === this) {
            activeServiceRef = null
        }
        AppGuardRuntimeCache.setNotificationListenerConnected(false)
        AppGuardRuntimeCache.bumpRestrictionStateVersion()
        CallSilenceHelper.releaseIfActive(this)
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {
        super.onNotificationRankingUpdate(rankingMap)
        if (rankingMap == null) return
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        var anyChannelUpdated = false
        for (sbn in active) {
            val pkg = sbn.packageName ?: continue
            if (pkg == packageName) continue
            if (UniversalChannelTracker.recordFromNotification(sbn, rankingMap)) {
                if (shouldTrackPackageChannelsInDb(pkg)) {
                    persistDiscoveredChannels(pkg)
                }
                anyChannelUpdated = true
            }
        }
        if (anyChannelUpdated) {
            AppGuardRuntimeCache.bumpBlockingVersion()
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int,
    ) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        val pkg = sbn?.packageName ?: return
        if (pkg == packageName) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (reason == REASON_PACKAGE_BANNED) {
                UniversalChannelTracker.markPackageNotificationsBanned(pkg, true)
                AppGuardRuntimeCache.bumpRestrictionStateVersion()
            } else if (reason == REASON_CHANNEL_BANNED) {
                val ranking = Ranking()
                val hasRanking = rankingMap?.getRanking(sbn.key, ranking) == true
                val ch = if (hasRanking) ranking.channel else null
                if (ch != null) {
                    val info = toDetectedChannelInfo(ch)
                    UniversalChannelTracker.recordChannelInfo(pkg, info)
                    if (shouldTrackPackageChannelsInDb(pkg)) {
                        val app = applicationContext as? VaultGuardApp
                        if (app != null) {
                            scope.launch(Dispatchers.IO) {
                                app.repository.recordChannelModifiedWhileRestricted(pkg, info)
                                AppGuardRuntimeCache.bumpRestrictionStateVersion()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNotificationChannelModified(
        pkg: String?,
        user: UserHandle?,
        channel: NotificationChannel?,
        modificationType: Int,
    ) {
        super.onNotificationChannelModified(pkg, user, channel, modificationType)
        if (pkg.isNullOrBlank() || pkg == packageName || channel == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (modificationType == NOTIFICATION_CHANNEL_OR_GROUP_DELETED) return

        val info = toDetectedChannelInfo(channel)
        val changed = UniversalChannelTracker.recordChannelInfo(pkg, info)
        val app = applicationContext as? VaultGuardApp
        if (app != null) {
            scope.launch(Dispatchers.IO) {
                val shouldRecordState = shouldTrackPackageChannelsInDb(pkg) ||
                    app.repository.hasRestrictedAppState(pkg)
                if (shouldRecordState) {
                    app.repository.recordChannelModifiedWhileRestricted(pkg, info)
                    AppGuardRuntimeCache.bumpRestrictionStateVersion()
                    AppGuardRuntimeCache.bumpBlockingVersion()
                } else if (changed) {
                    AppGuardRuntimeCache.bumpRestrictionStateVersion()
                }
            }
        } else if (changed && shouldTrackPackageChannelsInDb(pkg)) {
            persistDiscoveredChannels(pkg)
            AppGuardRuntimeCache.bumpRestrictionStateVersion()
            AppGuardRuntimeCache.bumpBlockingVersion()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        val pkg = notification.packageName ?: return
        if (pkg == packageName) return

        UniversalChannelTracker.markPackageNotificationsBanned(pkg, false)
        val channelChanged = UniversalChannelTracker.recordFromNotification(
            notification,
            runCatching { currentRanking }.getOrNull(),
        )
        if (channelChanged && shouldTrackPackageChannelsInDb(pkg)) {
            persistDiscoveredChannels(pkg)
        }

        val rule = AppGuardRuntimeCache.getRule(pkg)
        val config = AppGuardRuntimeCache.config
        if (!AppGuardRuntimeCache.isPackageCurrentlyBlocked(pkg)) {
            cancelMaskedNotification(pkg)
            return
        }

        val privacyMode = rule?.notificationPrivacyEnum ?: NotificationPrivacyMode.SILENT_AND_HIDE
        when (privacyMode) {
            NotificationPrivacyMode.SILENT_AND_HIDE -> {
                if (config.notificationBlockEnabled) {
                    hideAndSilencePackageNotification(notification)
                }
                cancelMaskedNotification(pkg)
            }
            NotificationPrivacyMode.SHOW_HIDE_SENSITIVE -> {
                if (CallSilenceHelper.isIncomingCallNotification(notification.notification)) {
                    CallSilenceHelper.findDeclinePendingIntent(notification.notification)?.let {
                        runCatching { it.send() }
                    }
                    CallSilenceHelper.interruptRestrictedCallAudio(this)
                }
                replaceWithHiddenSensitiveNotification(
                    sbn = notification,
                    appLabel = rule?.appName ?: pkg,
                )
            }
        }
    }

    private fun shouldTrackPackageChannelsInDb(pkg: String): Boolean {
        return AppGuardRuntimeCache.getRule(pkg)?.isLimited == true ||
            AppGuardRuntimeCache.activeSetupPackage == pkg
    }

    private fun toDetectedChannelInfo(channel: NotificationChannel): DetectedChannelInfo {
        val chId = channel.id.orEmpty()
        val chName = channel.name?.toString()?.trim().orEmpty().ifBlank { chId }
        return DetectedChannelInfo(
            channelId = chId,
            channelName = chName,
            importance = channel.importance,
            isCallChannel = UniversalChannelTracker.isCallRelatedChannelMetadata(chId, chName),
            hasSound = channel.sound != null,
            hasVibration = channel.shouldVibrate(),
            canBypassDnd = channel.canBypassDnd(),
            isBlockedInSystem = channel.importance == NotificationManager.IMPORTANCE_NONE,
            discoveredAtMillis = System.currentTimeMillis(),
        )
    }

    private fun harvestAllActiveNotificationChannels() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val ranking = runCatching { currentRanking }.getOrNull()
        for (sbn in active) {
            val pkg = sbn.packageName ?: continue
            if (pkg == packageName) continue
            UniversalChannelTracker.markPackageNotificationsBanned(pkg, false)
            UniversalChannelTracker.recordFromNotification(sbn, ranking)
        }
    }

    private fun inspectLiveChannelsForPackage(pkg: String): Boolean {
        var anyChanged = false
        val active = runCatching { activeNotifications }.getOrNull()
        val ranking = runCatching { currentRanking }.getOrNull()
        active?.forEach { sbn ->
            if (sbn.packageName == pkg) {
                UniversalChannelTracker.markPackageNotificationsBanned(pkg, false)
                if (UniversalChannelTracker.recordFromNotification(sbn, ranking)) {
                    anyChanged = true
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channels = runCatching {
                @Suppress("DEPRECATION")
                getNotificationChannels(pkg, Process.myUserHandle())
            }.getOrNull().orEmpty()
            for (channel in channels) {
                val info = toDetectedChannelInfo(channel)
                if (info.channelId.isNotBlank() && UniversalChannelTracker.recordChannelInfo(pkg, info)) {
                    anyChanged = true
                }
            }
        }
        return anyChanged
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
        val ranking = runCatching { currentRanking }.getOrNull()
        for (sbn in active) {
            val pkg = sbn.packageName ?: continue
            if (pkg == packageName) continue
            if (!AppGuardRuntimeCache.isPackageCurrentlyBlocked(pkg)) continue

            if (UniversalChannelTracker.recordFromNotification(sbn, ranking)) {
                persistDiscoveredChannels(pkg)
            }

            val rule = AppGuardRuntimeCache.getRule(pkg)
            val privacyMode = rule?.notificationPrivacyEnum ?: NotificationPrivacyMode.SILENT_AND_HIDE
            if (privacyMode == NotificationPrivacyMode.SILENT_AND_HIDE) {
                if (config.notificationBlockEnabled) {
                    hideAndSilencePackageNotification(sbn)
                }
            } else {
                replaceWithHiddenSensitiveNotification(
                    sbn = sbn,
                    appLabel = rule?.appName ?: pkg,
                )
            }
        }
    }

    private fun persistDiscoveredChannels(packageName: String) {
        val app = applicationContext as? VaultGuardApp ?: return
        val channels = UniversalChannelTracker.getChannelsForPackage(packageName)
        if (channels.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val dao = app.database.vaultDao()
            val existing = dao.getRestrictedAppState(packageName)
            if (existing != null) {
                val encoded = RestrictionStateCodec.encodeChannels(channels)
                if (existing.discoveredChannelsJson != encoded) {
                    dao.upsertRestrictedAppState(
                        existing.copy(
                            discoveredChannelsJson = encoded,
                            lastInspectedAtMillis = System.currentTimeMillis(),
                        )
                    )
                }
            }
        }
    }

    private fun hideAndSilencePackageNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val isCall = CallSilenceHelper.isIncomingCallNotification(notification)

        if (isCall) {
            CallSilenceHelper.interruptRestrictedCallAudio(this)
            CallSilenceHelper.findDeclinePendingIntent(notification)?.let { declineIntent ->
                runCatching { declineIntent.send() }
            }
        }

        runCatching { cancelNotification(sbn.key) }
        if ((isCall || !sbn.isClearable) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { snoozeNotification(sbn.key, 60 * 60_000L) }
        }
    }

    private fun replaceWithHiddenSensitiveNotification(
        sbn: StatusBarNotification,
        appLabel: String,
    ) {
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
            .setContentText("New notification • Unlock to view")
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
            description = "Generic notifications for protected apps hiding details until unlocked."
        }
        nm.createNotificationChannel(channel)
    }
}
