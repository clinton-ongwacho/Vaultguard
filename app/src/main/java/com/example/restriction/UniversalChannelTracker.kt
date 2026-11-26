package com.example.restriction

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import com.example.service.CallSilenceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Universally inspects and tracks notification channels for any package from live Android
 * NotificationListenerService callbacks and active rankings without app-specific rules.
 */
object UniversalChannelTracker {

    private val channelsByPackage = ConcurrentHashMap<String, ConcurrentHashMap<String, DetectedChannelInfo>>()
    private val bannedPackages = ConcurrentHashMap<String, Boolean>()

    private val _channelVersion = MutableStateFlow(0L)
    val channelVersion: StateFlow<Long> = _channelVersion.asStateFlow()

    fun markPackageNotificationsBanned(packageName: String, banned: Boolean) {
        val prev = bannedPackages[packageName]
        bannedPackages[packageName] = banned
        if (prev != banned) {
            _channelVersion.value += 1
        }
    }

    fun isPackageNotificationsBanned(packageName: String): Boolean =
        bannedPackages[packageName] == true

    fun seedFromPersisted(packageName: String, persisted: List<DetectedChannelInfo>) {
        if (persisted.isEmpty()) return
        val pkgMap = channelsByPackage.getOrPut(packageName) { ConcurrentHashMap() }
        var changed = false
        for (item in persisted) {
            val prev = pkgMap[item.channelId]
            if (prev == null || item.discoveredAtMillis > prev.discoveredAtMillis) {
                pkgMap[item.channelId] = item
                changed = true
            }
        }
        if (changed) _channelVersion.value += 1
    }

    fun getChannelsForPackage(packageName: String): List<DetectedChannelInfo> {
        val map = channelsByPackage[packageName] ?: return emptyList()
        return map.values.sortedWith(
            compareByDescending<DetectedChannelInfo> { it.isCallChannel }
                .thenByDescending { isMessageRelatedChannelMetadata(it.channelId, it.channelName) }
                .thenByDescending { it.importance }
                .thenBy { it.channelName.lowercase() }
        )
    }

    fun clearPackage(packageName: String) {
        bannedPackages.remove(packageName)
        if (channelsByPackage.remove(packageName) != null) {
            _channelVersion.value += 1
        }
    }

    /**
     * Records or updates channel telemetry from an active notification and its Ranking.
     * Returns `true` if a newly discovered channel or changed importance was detected.
     */
    fun recordFromNotification(
        sbn: StatusBarNotification,
        rankingMap: RankingMap?,
    ): Boolean {
        val pkg = sbn.packageName ?: return false
        val notification = sbn.notification ?: return false
        val isCallSignal = CallSilenceHelper.isIncomingCallNotification(notification)

        val detected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ranking = Ranking()
            val hasRanking = rankingMap?.getRanking(sbn.key, ranking) == true
            val channel: NotificationChannel? = if (hasRanking) ranking.channel else null
            val channelId = channel?.id ?: notification.channelId ?: return false
            val channelName = channel?.name?.toString()?.trim().orEmpty().ifBlank { channelId }
            val importance = when {
                channel != null -> channel.importance
                hasRanking -> ranking.importance
                else -> NotificationManager.IMPORTANCE_DEFAULT
            }
            val isCallByChannel = isCallSignal || isCallRelatedChannelMetadata(channelId, channelName)
            val hasSound = if (channel != null) {
                channel.sound != null
            } else {
                @Suppress("DEPRECATION")
                (notification.sound != null || (notification.defaults and Notification.DEFAULT_SOUND) != 0)
            }
            val hasVibrate = if (channel != null) {
                channel.shouldVibrate()
            } else {
                @Suppress("DEPRECATION")
                ((notification.vibrate?.isNotEmpty() == true) ||
                    (notification.defaults and Notification.DEFAULT_VIBRATE) != 0)
            }
            val bypassDnd = channel?.canBypassDnd() == true
            DetectedChannelInfo(
                channelId = channelId,
                channelName = channelName,
                importance = importance,
                isCallChannel = isCallByChannel,
                hasSound = hasSound,
                hasVibration = hasVibrate,
                canBypassDnd = bypassDnd,
                isBlockedInSystem = importance == NotificationManager.IMPORTANCE_NONE,
                discoveredAtMillis = System.currentTimeMillis(),
            )
        } else {
            val channelId = if (isCallSignal) "legacy_call" else "legacy_default"
            DetectedChannelInfo(
                channelId = channelId,
                channelName = if (isCallSignal) "Calls" else "Alerts",
                importance = NotificationManager.IMPORTANCE_DEFAULT,
                isCallChannel = isCallSignal,
                hasSound = true,
                hasVibration = true,
                canBypassDnd = false,
                isBlockedInSystem = false,
                discoveredAtMillis = System.currentTimeMillis(),
            )
        }

        return recordChannelInfo(pkg, detected)
    }

    fun recordChannelInfo(packageName: String, info: DetectedChannelInfo): Boolean {
        val pkgMap = channelsByPackage.getOrPut(packageName) { ConcurrentHashMap() }
        val existing = pkgMap[info.channelId]
        val changed = existing == null ||
            existing.importance != info.importance ||
            existing.isBlockedInSystem != info.isBlockedInSystem ||
            existing.isCallChannel != info.isCallChannel ||
            existing.hasSound != info.hasSound ||
            existing.hasVibration != info.hasVibration
        pkgMap[info.channelId] = info.copy(
            isCallChannel = info.isCallChannel || (existing?.isCallChannel == true),
        )
        if (changed) {
            _channelVersion.value += 1
        }
        return changed
    }

    fun isCallRelatedChannelMetadata(channelId: String, channelName: String): Boolean {
        val combined = "$channelId $channelName".lowercase()
        val keywords = listOf("call", "ring", "voip", "voice call", "video call", "incoming")
        return keywords.any { combined.contains(it) }
    }

    fun isMessageRelatedChannelMetadata(channelId: String, channelName: String): Boolean {
        val combined = "$channelId $channelName".lowercase()
        val keywords = listOf("message", "msg", "chat", "dm", "direct", "sms", "mms", "conversation", "group", "inbox")
        return keywords.any { combined.contains(it) }
    }
}
