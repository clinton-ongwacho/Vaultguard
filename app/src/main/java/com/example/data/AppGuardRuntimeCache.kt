package com.example.data

import com.example.data.entity.AdminConfigEntity
import com.example.data.entity.DisguiseIdentity
import com.example.data.entity.LimitedAppEntity
import com.example.security.PasswordHasher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory snapshot synchronized from Room so AccessibilityService,
 * NotificationListenerService, and VpnService can make instantaneous blocking decisions.
 *
 * Restricted apps remain restricted permanently in Room; entering the Admin password grants
 * only a single foreground session pass that clears automatically as soon as the user
 * exits that app or turns off the screen.
 */
object AppGuardRuntimeCache {
    @Volatile
    var config: AdminConfigEntity = AdminConfigEntity()
        private set

    private val limitedRules = ConcurrentHashMap<String, LimitedAppEntity>()

    @Volatile
    var activeSessionPackage: String? = null
        private set

    @Volatile
    private var activeSessionGrantedAtMillis: Long = 0L

    @Volatile
    private var activeSessionSeenForeground: Boolean = false

    @Volatile
    private var leftUnlockedAppAtMillis: Long = 0L

    @Volatile
    var installAuthorizedUntilMillis: Long = 0L

    @Volatile
    var uninstallAuthorizedUntilMillis: Long = 0L

    @Volatile
    var activeSetupPackage: String? = null

    private val _accessibilityConnected = MutableStateFlow(false)
    val accessibilityConnected: StateFlow<Boolean> = _accessibilityConnected.asStateFlow()

    private val _notificationListenerConnected = MutableStateFlow(false)
    val notificationListenerConnected: StateFlow<Boolean> = _notificationListenerConnected.asStateFlow()

    private val _blockingVersion = MutableStateFlow(0L)
    val blockingVersion: StateFlow<Long> = _blockingVersion.asStateFlow()

    private val _restrictionStateVersion = MutableStateFlow(0L)
    val restrictionStateVersion: StateFlow<Long> = _restrictionStateVersion.asStateFlow()

    private val _foregroundActive = MutableStateFlow(false)
    val foregroundActive: StateFlow<Boolean> = _foregroundActive.asStateFlow()

    fun setAccessibilityConnected(connected: Boolean) {
        _accessibilityConnected.value = connected
    }

    fun setNotificationListenerConnected(connected: Boolean) {
        _notificationListenerConnected.value = connected
    }

    fun setForegroundActive(active: Boolean) {
        _foregroundActive.value = active
    }

    fun bumpBlockingVersion() {
        _blockingVersion.value += 1
    }

    fun bumpRestrictionStateVersion() {
        _restrictionStateVersion.value += 1
    }

    fun updateConfig(newConfig: AdminConfigEntity) {
        config = newConfig
        _blockingVersion.value += 1
    }

    fun updateLimitedApps(apps: List<LimitedAppEntity>) {
        limitedRules.clear()
        for (app in apps) {
            limitedRules[app.packageName] = app
        }
        val active = activeSessionPackage
        if (active != null && limitedRules[active]?.isLimited != true) {
            activeSessionPackage = null
            leftUnlockedAppAtMillis = 0L
        }
        _blockingVersion.value += 1
    }

    /**
     * Grants a single-session pass for [packageName] while keeping the rule active in DB.
     */
    fun grantSessionUnlock(packageName: String, nowMillis: Long = System.currentTimeMillis()) {
        activeSessionPackage = packageName
        activeSessionGrantedAtMillis = nowMillis
        activeSessionSeenForeground = false
        leftUnlockedAppAtMillis = 0L
        _blockingVersion.value += 1
    }

    /**
     * Called on foreground window changes; re-arms the lock either immediately or after
     * [AdminConfigEntity.immediateLockTimeoutSeconds] (5s / 15s / 1m) once the user leaves the app.
     */
    fun onForegroundPackageChanged(packageName: String, nowMillis: Long = System.currentTimeMillis()) {
        val unlockedPkg = activeSessionPackage ?: return
        val timeoutMillis = config.immediateLockTimeoutSeconds.coerceAtLeast(0) * 1000L

        // Always evaluate pending timeout expiry FIRST — otherwise returning to unlockedPkg
        // after >= timeoutMillis would clear leftUnlockedAppAtMillis and skip re-locking!
        if (leftUnlockedAppAtMillis > 0L && timeoutMillis > 0L &&
            (nowMillis - leftUnlockedAppAtMillis) >= timeoutMillis
        ) {
            activeSessionPackage = null
            activeSessionSeenForeground = false
            leftUnlockedAppAtMillis = 0L
            _blockingVersion.value += 1
            return
        }

        if (packageName == unlockedPkg) {
            activeSessionSeenForeground = true
            leftUnlockedAppAtMillis = 0L
            return
        }

        val transitionElapsed = activeSessionSeenForeground || (nowMillis - activeSessionGrantedAtMillis) > 450L
        if (!transitionElapsed) return

        if (timeoutMillis <= 0L) {
            activeSessionPackage = null
            activeSessionSeenForeground = false
            leftUnlockedAppAtMillis = 0L
            _blockingVersion.value += 1
        } else if (leftUnlockedAppAtMillis == 0L) {
            leftUnlockedAppAtMillis = nowMillis
        }
    }

    fun clearSessionUnlock() {
        if (activeSessionPackage != null) {
            activeSessionPackage = null
            activeSessionSeenForeground = false
            leftUnlockedAppAtMillis = 0L
            _blockingVersion.value += 1
        }
    }

    fun isPackageCurrentlyBlocked(
        packageName: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!config.isInitialized || !config.guardActive) return false
        val rule = limitedRules[packageName] ?: return false
        if (!rule.isLimited) return false
        if (activeSessionPackage == packageName) {
            val leftAt = leftUnlockedAppAtMillis
            val timeoutMillis = config.immediateLockTimeoutSeconds.coerceAtLeast(0) * 1000L
            if (leftAt > 0L && timeoutMillis > 0L && (nowMillis - leftAt) >= timeoutMillis) {
                activeSessionPackage = null
                activeSessionSeenForeground = false
                leftUnlockedAppAtMillis = 0L
                _blockingVersion.value += 1
                return true
            }
            return false
        }
        return true
    }

    fun currentlyBlockedPackageNames(): List<String> {
        if (!config.isInitialized || !config.guardActive) return emptyList()
        return limitedRules.values
            .filter { it.isLimited && it.packageName != activeSessionPackage }
            .map { it.packageName }
    }

    fun allLimitedPackageNames(): List<String> =
        limitedRules.values.filter { it.isLimited }.map { it.packageName }

    fun getRule(packageName: String): LimitedAppEntity? = limitedRules[packageName]

    @Volatile
    private var pendingDisguiseSwitchUnlockToken: String? = null

    @Volatile
    private var pendingDisguiseSwitchUnlockExpiresAt: Long = 0L

    fun issueDisguiseSwitchUnlockToken(): String {
        val token = UUID.randomUUID().toString()
        pendingDisguiseSwitchUnlockToken = token
        pendingDisguiseSwitchUnlockExpiresAt = System.currentTimeMillis() + 8_000L
        return token
    }

    fun consumeDisguiseSwitchUnlockToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val valid = token == pendingDisguiseSwitchUnlockToken &&
            System.currentTimeMillis() <= pendingDisguiseSwitchUnlockExpiresAt
        pendingDisguiseSwitchUnlockToken = null
        pendingDisguiseSwitchUnlockExpiresAt = 0L
        return valid
    }

    fun activeDisguiseLabel(): String =
        DisguiseIdentity.fromName(config.disguiseName).displayLabel

    fun matchesWakeWord(input: String): Boolean {
        val wake = config.wakeWord.trim()
        if (wake.length < 2) return false
        val normalized = input.trim().lowercase()
        val target = wake.lowercase()
        return normalized == target || normalized.endsWith(" $target") || normalized.contains(target)
    }

    fun matchesDialerSecret(input: String): Boolean {
        val digits = input.trim()
        if (digits.isEmpty()) return false
        val customPin = config.dialerPin.trim()
        val t9 = PasswordHasher.wakeWordToT9Digits(config.wakeWord)

        val candidates = buildList {
            add("*#*#82858#*#*")
            add("#82858#")
            if (customPin.isNotEmpty()) {
                add("*#*#$customPin#*#*")
                add("#$customPin#")
                add("*#$customPin#")
            }
            if (t9.isNotEmpty()) {
                add("*#*#$t9#*#*")
                add("#$t9#")
            }
        }
        return candidates.any { code -> digits == code || digits.endsWith(code) }
    }
}
