package com.example.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.service.notification.NotificationListenerService
import androidx.core.os.BundleCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pure classification helpers for detecting incoming VoIP/app calls and their Decline actions.
 */
object CallNotificationClassifier {

    private val DECLINE_KEYWORDS = listOf(
        "decline",
        "reject",
        "hang up",
        "hangup",
        "end call",
        "ignore",
        "refuse",
        "dismiss",
    )

    private val ANSWER_KEYWORDS = listOf(
        "answer",
        "accept",
        "pick up",
        "pickup",
        "join call",
    )

    fun isDeclineActionTitle(title: String?): Boolean {
        if (title.isNullOrBlank()) return false
        val normalized = title.trim().lowercase()
        if (ANSWER_KEYWORDS.any { normalized.contains(it) }) return false
        return DECLINE_KEYWORDS.any { normalized.contains(it) } || normalized == "end"
    }

    fun isAnswerActionTitle(title: String?): Boolean {
        if (title.isNullOrBlank()) return false
        val normalized = title.trim().lowercase()
        return ANSWER_KEYWORDS.any { normalized.contains(it) }
    }

    fun isIncomingCallSignals(
        category: String?,
        template: String?,
        hasFullScreenIntent: Boolean,
        actionTitles: List<String>,
        titleAndText: String,
    ): Boolean {
        if (category == Notification.CATEGORY_CALL) return true
        if (!template.isNullOrBlank() && template.contains("CallStyle", ignoreCase = true)) return true
        if (hasFullScreenIntent) return true
        if (actionTitles.any { isDeclineActionTitle(it) } || actionTitles.any { isAnswerActionTitle(it) }) {
            return true
        }
        val lower = titleAndText.lowercase()
        return (lower.contains("incoming") &&
            (lower.contains("call") || lower.contains("video") || lower.contains("voice") || lower.contains("audio"))) ||
            lower.contains("is calling") ||
            lower.contains("calling you")
    }
}

/**
 * Silences ringtones, notification audio, and vibration when a restricted app receives an incoming
 * call or alert, and safely restores the user's previous audio / DND state afterward.
 */
object CallSilenceHelper {

    private const val EXTRA_DECLINE_INTENT = "android.declineIntent"
    private const val MAX_CALL_SILENCE_MS = 40_000L

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var restoreJob: Job? = null
    private var isSilencingActive = false
    private var savedInterruptionFilter: Int? = null
    private var savedRingerMode: Int? = null
    private var silenceStartMillis: Long = 0L
    private var silenceUntilMillis: Long = 0L
    private var activeAudioFocusRequest: AudioFocusRequest? = null

    fun isIncomingCallNotification(notification: Notification): Boolean {
        val extras = notification.extras
        val template = extras?.getString(Notification.EXTRA_TEMPLATE)
        val actionTitles = notification.actions?.mapNotNull { it.title?.toString() }.orEmpty()
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

        return CallNotificationClassifier.isIncomingCallSignals(
            category = notification.category,
            template = template,
            hasFullScreenIntent = notification.fullScreenIntent != null,
            actionTitles = actionTitles,
            titleAndText = "$title $text $bigText",
        )
    }

    fun findDeclinePendingIntent(notification: Notification): PendingIntent? {
        notification.extras?.let { extras ->
            val callStyleDecline = runCatching {
                BundleCompat.getParcelable(extras, EXTRA_DECLINE_INTENT, PendingIntent::class.java)
            }.getOrNull()
            if (callStyleDecline != null) return callStyleDecline
        }

        val actions = notification.actions ?: return null
        for (action in actions) {
            if (CallNotificationClassifier.isDeclineActionTitle(action.title?.toString())) {
                return action.actionIntent
            }
        }
        return null
    }

    fun silenceRestrictedAlert(
        context: Context,
        listener: NotificationListenerService?,
        isCall: Boolean,
    ) {
        val appContext = context.applicationContext
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val notificationManager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val now = System.currentTimeMillis()
        val durationMs = if (isCall) 5_000L else 1_500L

        synchronized(lock) {
            if (!isSilencingActive) {
                isSilencingActive = true
                silenceStartMillis = now
                savedInterruptionFilter = listener?.let { runCatching { it.currentInterruptionFilter }.getOrNull() }
                if (audioManager != null && notificationManager?.isNotificationPolicyAccessGranted == true) {
                    savedRingerMode = runCatching { audioManager.ringerMode }.getOrNull()
                } else {
                    savedRingerMode = null
                }
            }
            silenceUntilMillis = maxOf(silenceUntilMillis, now + durationMs)

            // 1. Apply Do Not Disturb (Alarms Only) via NotificationListenerService to silence
            // both ringtone audio and vibration immediately.
            listener?.let { nls ->
                val targetFilter = NotificationListenerService.INTERRUPTION_FILTER_ALARMS
                val currentFilter = runCatching { nls.currentInterruptionFilter }.getOrNull()
                if (currentFilter != NotificationListenerService.INTERRUPTION_FILTER_NONE &&
                    currentFilter != targetFilter
                ) {
                    runCatching { nls.requestInterruptionFilter(targetFilter) }
                }
            }

            // 2. Directly silence ring/notification streams when Notification Policy Access is granted.
            if (audioManager != null && notificationManager?.isNotificationPolicyAccessGranted == true) {
                runCatching {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                }
                runCatching {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
                }
                runCatching {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
                }
            }

            // 3. Request transient audio focus on calls to interrupt custom ringtone MediaPlayers.
            if (isCall && audioManager != null && activeAudioFocusRequest == null) {
                requestTransientRingFocus(audioManager)
            }

            if (restoreJob?.isActive != true) {
                restoreJob = scope.launch {
                    monitorAndRestore(appContext, listener, watchRingMode = isCall)
                }
            }
        }
    }

    fun silenceIfRinging(context: Context) {
        val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return
        if (audioManager.mode == AudioManager.MODE_RINGTONE) {
            silenceRestrictedAlert(context = context, listener = null, isCall = true)
        }
    }

    fun restoreIfActive(context: Context, listener: NotificationListenerService?) {
        val appContext = context.applicationContext
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val notificationManager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

        synchronized(lock) {
            restoreJob?.cancel()
            restoreJob = null
            if (!isSilencingActive) return
            restoreLocked(audioManager, notificationManager, listener)
        }
    }

    private suspend fun monitorAndRestore(
        appContext: Context,
        listener: NotificationListenerService?,
        watchRingMode: Boolean,
    ) {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val notificationManager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

        while (true) {
            delay(600L)
            val now = System.currentTimeMillis()
            val shouldContinue = synchronized(lock) {
                if (!isSilencingActive) return@synchronized false
                val withinHardCap = (now - silenceStartMillis) < MAX_CALL_SILENCE_MS
                val stillRinging = watchRingMode && audioManager?.mode == AudioManager.MODE_RINGTONE
                if (now < silenceUntilMillis) {
                    true
                } else if (stillRinging && withinHardCap) {
                    silenceUntilMillis = now + 1_200L
                    true
                } else {
                    restoreLocked(audioManager, notificationManager, listener)
                    false
                }
            }
            if (!shouldContinue) break
        }
    }

    private fun restoreLocked(
        audioManager: AudioManager?,
        notificationManager: NotificationManager?,
        listener: NotificationListenerService?,
    ) {
        abandonTransientRingFocus(audioManager)

        savedInterruptionFilter?.let { prevFilter ->
            listener?.let { nls ->
                runCatching { nls.requestInterruptionFilter(prevFilter) }
            }
        }

        if (audioManager != null && notificationManager?.isNotificationPolicyAccessGranted == true) {
            runCatching {
                audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
            }
            runCatching {
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
            }
            savedRingerMode?.let { prevMode ->
                runCatching { audioManager.ringerMode = prevMode }
            }
        }

        isSilencingActive = false
        savedInterruptionFilter = null
        savedRingerMode = null
        silenceStartMillis = 0L
        silenceUntilMillis = 0L
    }

    private fun requestTransientRingFocus(audioManager: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(true)
                .build()
            activeAudioFocusRequest = request
            runCatching { audioManager.requestAudioFocus(request) }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_RING,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                )
            }
        }
    }

    private fun abandonTransientRingFocus(audioManager: AudioManager?) {
        if (audioManager == null) {
            activeAudioFocusRequest = null
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activeAudioFocusRequest?.let { req ->
                runCatching { audioManager.abandonAudioFocusRequest(req) }
            }
            activeAudioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.abandonAudioFocus(null) }
        }
    }
}
