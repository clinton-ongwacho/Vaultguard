package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.core.os.BundleCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pure universal classification helpers for detecting incoming VoIP/app calls and Decline actions.
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
 * Interrupts incoming call audio for restricted applications using per-call transient ringtone
 * audio focus and call-decline PendingIntents. Never enables global Do Not Disturb or mutes
 * unrelated applications.
 */
object CallSilenceHelper {

    private const val EXTRA_DECLINE_INTENT = "android.declineIntent"
    private const val FOCUS_HOLD_MS = 2_400L
    private const val MAX_CALL_FOCUS_MS = 12_000L

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var restoreJob: Job? = null
    private var isFocusActive = false
    private var focusStartMillis: Long = 0L
    private var focusUntilMillis: Long = 0L
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

    /**
     * Scopes transient ringtone audio focus strictly to active restricted call interruptions.
     * Does NOT alter global Do Not Disturb or system ringer mode.
     */
    fun interruptRestrictedCallAudio(context: Context) {
        val appContext = context.applicationContext
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val now = System.currentTimeMillis()

        synchronized(lock) {
            if (!isFocusActive) {
                isFocusActive = true
                focusStartMillis = now
                requestTransientRingFocus(audioManager)
            }
            focusUntilMillis = maxOf(focusUntilMillis, now + FOCUS_HOLD_MS)

            if (restoreJob?.isActive != true) {
                restoreJob = scope.launch {
                    monitorAndAbandon(audioManager)
                }
            }
        }
    }

    fun silenceIfRinging(context: Context) {
        val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return
        if (audioManager.mode == AudioManager.MODE_RINGTONE) {
            interruptRestrictedCallAudio(context)
        }
    }

    fun releaseIfActive(context: Context) {
        val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        synchronized(lock) {
            restoreJob?.cancel()
            restoreJob = null
            if (!isFocusActive) return
            abandonLocked(audioManager)
        }
    }

    private suspend fun monitorAndAbandon(audioManager: AudioManager) {
        while (true) {
            delay(400L)
            val now = System.currentTimeMillis()
            val keepGoing = synchronized(lock) {
                if (!isFocusActive) return@synchronized false
                val withinCap = (now - focusStartMillis) < MAX_CALL_FOCUS_MS
                val stillRinging = audioManager.mode == AudioManager.MODE_RINGTONE
                if (now < focusUntilMillis) {
                    true
                } else if (stillRinging && withinCap) {
                    focusUntilMillis = now + 900L
                    true
                } else {
                    abandonLocked(audioManager)
                    false
                }
            }
            if (!keepGoing) break
        }
    }

    private fun abandonLocked(audioManager: AudioManager?) {
        abandonTransientRingFocus(audioManager)
        isFocusActive = false
        focusStartMillis = 0L
        focusUntilMillis = 0L
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
