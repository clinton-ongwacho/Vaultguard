package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DisguiseIdentity(
    val aliasClassName: String,
    val displayLabel: String,
    val subtitle: String,
) {
    DEFAULT(
        aliasClassName = "com.example.LauncherDefault",
        displayLabel = "VaultGuard",
        subtitle = "Standard security shield identity",
    ),
    CALCULATOR(
        aliasClassName = "com.example.LauncherCalculator",
        displayLabel = "Calculator",
        subtitle = "Disguised as a working calculator app",
    ),
    NOTES(
        aliasClassName = "com.example.LauncherNotes",
        displayLabel = "Quick Notes",
        subtitle = "Disguised as a private notes app",
    ),
    WEATHER(
        aliasClassName = "com.example.LauncherWeather",
        displayLabel = "Weather",
        subtitle = "Disguised as a minimalist forecast utility",
    ),
    CLOCK(
        aliasClassName = "com.example.LauncherClock",
        displayLabel = "Timer",
        subtitle = "Disguised as a countdown timer tool",
    );

    companion object {
        fun fromName(name: String): DisguiseIdentity =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

enum class RedirectDisplayStyle(
    val displayLabel: String,
    val description: String,
) {
    SUSPENDED(
        displayLabel = "Suspended",
        description = "Prohibition icon with \"Suspended\"",
    ),
    TEMPORARILY_PAUSED(
        displayLabel = "Temporarily Paused",
        description = "Pause symbol with \"Temporarily Paused\"",
    ),
    BLOCKED(
        displayLabel = "Blocked",
        description = "Block badge with \"Blocked\"",
    ),
    DISABLED(
        displayLabel = "Disabled",
        description = "Power-off symbol with \"Disabled\"",
    ),
    UNAVAILABLE(
        displayLabel = "Unavailable",
        description = "App unavailable symbol with \"Unavailable\"",
    ),
    CANNOT_BE_REACHED(
        displayLabel = "Cannot Be Reached",
        description = "Offline cloud symbol with \"Cannot Be Reached\"",
    ),
    FLAGGED(
        displayLabel = "Flagged",
        description = "Flag marker with \"Flagged\"",
    ),
    SUSPICIOUS(
        displayLabel = "Suspicious",
        description = "Warning alert symbol with \"Suspicious\"",
    ),
    CONTACT_SUPPORT(
        displayLabel = "Contact Support",
        description = "Support headset symbol with \"Contact Support\"",
    ),
    UNDER_REVIEW(
        displayLabel = "Under Review",
        description = "Hourglass symbol with \"Under Review\"",
    ),
    ACCESS_DENIED(
        displayLabel = "Access Denied",
        description = "Denied shield badge with \"Access Denied\"",
    ),
    NOT_ALLOWED(
        displayLabel = "Not Allowed",
        description = "Restriction hand symbol with \"Not Allowed\"",
    );

    companion object {
        fun fromName(name: String): RedirectDisplayStyle =
            entries.firstOrNull { it.name == name } ?: SUSPENDED
    }
}

enum class RedirectDecoyMode {
    ICON_DISPLAY,
    FAKE_CRASH;

    companion object {
        fun fromName(name: String): RedirectDecoyMode =
            entries.firstOrNull { it.name == name } ?: ICON_DISPLAY
    }
}

enum class AppLockType(val label: String) {
    NONE("Not Set"),
    PASSWORD("Password"),
    PIN("PIN"),
    PATTERN("Pattern");

    companion object {
        fun fromName(name: String): AppLockType =
            entries.firstOrNull { it.name == name } ?: NONE
    }
}

@Entity(tableName = "admin_config")
data class AdminConfigEntity(
    @PrimaryKey val id: Int = 1,
    val isInitialized: Boolean = false,
    val passwordHash: String = "",
    val wakeWord: String = "vault",
    val dialerPin: String = "82858",
    val disguiseName: String = DisguiseIdentity.DEFAULT.name,
    val redirectDisplayName: String = RedirectDisplayStyle.SUSPENDED.name,
    val redirectDecoyMode: String = RedirectDecoyMode.ICON_DISPLAY.name,
    val immediateLockTimeoutSeconds: Int = 0,
    val appLockType: String = AppLockType.NONE.name,
    val appLockSecretHash: String = "",
    val isAppHidden: Boolean = false,
    val uninstallProtectionEnabled: Boolean = true,
    val installGuardEnabled: Boolean = false,
    val notificationBlockEnabled: Boolean = true,
    val guardActive: Boolean = true,
    val capturePhotoOnOpenEnabled: Boolean = true,
) {
    val appLockTypeEnum: AppLockType
        get() = AppLockType.fromName(appLockType)

    val hasAppLockSecurity: Boolean
        get() = appLockTypeEnum != AppLockType.NONE && appLockSecretHash.isNotBlank()
}

