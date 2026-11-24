package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AppGuardMode(val badgeLetter: String, val label: String) {
    NONE("", "Off"),
    RESTRICTED("", "Restricted");

    companion object {
        fun fromName(raw: String): AppGuardMode =
            if (raw == NONE.name) NONE else RESTRICTED
    }
}

enum class NotificationPrivacyMode(val label: String) {
    SILENT_AND_HIDE("Silent & Hide"),
    SHOW_HIDE_SENSITIVE("Show, Hide Content");

    companion object {
        fun fromName(raw: String): NotificationPrivacyMode =
            entries.firstOrNull { it.name == raw } ?: SILENT_AND_HIDE
    }
}

@Entity(tableName = "limited_apps")
data class LimitedAppEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val isLimited: Boolean = true,
    val guardMode: String = AppGuardMode.RESTRICTED.name,
    val notificationPrivacyMode: String = NotificationPrivacyMode.SILENT_AND_HIDE.name,
    val tempUnlockUntilMillis: Long = 0L,
    val blockCount: Int = 0,
    val lastBlockedAtMillis: Long = 0L,
) {
    val guardModeEnum: AppGuardMode
        get() = if (!isLimited) AppGuardMode.NONE else AppGuardMode.RESTRICTED

    val notificationPrivacyEnum: NotificationPrivacyMode
        get() = NotificationPrivacyMode.fromName(notificationPrivacyMode)
}
