package com.example.data.repository

import android.Manifest
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.example.data.AppGuardRuntimeCache
import java.io.File
import com.example.data.InstalledAppInfo
import com.example.data.InstalledAppsProvider
import com.example.data.VaultImportResult
import com.example.data.VaultMediaManager
import com.example.data.db.VaultDao
import com.example.data.entity.AccessEventEntity
import com.example.data.entity.AdminConfigEntity
import com.example.data.entity.AppGuardMode
import com.example.data.entity.DisguiseIdentity
import com.example.data.entity.LimitedAppEntity
import com.example.data.entity.NotificationPrivacyMode
import com.example.data.entity.RedirectDecoyMode
import com.example.data.entity.RedirectDisplayStyle
import com.example.data.entity.SecurityQuestionEntity
import com.example.data.entity.VaultMediaEntity
import com.example.launcher.LauncherDisguiseManager
import com.example.security.PasswordHasher
import com.example.security.SecretCipher
import com.example.service.AppGuardAccessibilityService
import com.example.service.VaultDeviceAdminReceiver
import com.example.service.VaultNotificationListenerService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class VaultRepository(
    private val context: Context,
    private val dao: VaultDao,
    private val appsProvider: InstalledAppsProvider,
    private val launcherManager: LauncherDisguiseManager,
    private val vaultMediaManager: VaultMediaManager = VaultMediaManager(context),
) {
    val configFlow: Flow<AdminConfigEntity> = dao.observeConfig().map { it ?: AdminConfigEntity() }
    val limitedAppsFlow: Flow<List<LimitedAppEntity>> = dao.observeLimitedApps()
    val recentEventsFlow: Flow<List<AccessEventEntity>> = dao.observeRecentEvents()
    val vaultMediaFlow: Flow<List<VaultMediaEntity>> = vaultMediaManager.vaultMediaFlow
    val securityQuestionsFlow: Flow<List<SecurityQuestionEntity>> = dao.observeSecurityQuestions()

    suspend fun syncRuntimeCache() {
        val config = dao.getConfigOnce() ?: AdminConfigEntity().also { dao.upsertConfig(it) }
        val rules = dao.getLimitedAppsOnce()
        AppGuardRuntimeCache.updateConfig(config)
        AppGuardRuntimeCache.updateLimitedApps(rules)
    }

    suspend fun initializeAdmin(
        password: String,
        wakeWord: String,
        dialerPin: String,
        securityQuestions: List<Pair<String, String>> = emptyList(),
    ) {
        val cleanWake = wakeWord.trim().lowercase().ifBlank { "vault" }
        val cleanPin = dialerPin.filter { it.isDigit() }.ifBlank {
            PasswordHasher.wakeWordToT9Digits(cleanWake).ifBlank { "82858" }
        }
        val config = AdminConfigEntity(
            id = 1,
            isInitialized = true,
            passwordHash = PasswordHasher.hashPassword(password),
            wakeWord = cleanWake,
            dialerPin = cleanPin,
            disguiseName = DisguiseIdentity.DEFAULT.name,
            isAppHidden = false,
            uninstallProtectionEnabled = true,
            installGuardEnabled = false,
            notificationBlockEnabled = true,
            guardActive = true,
        )
        dao.upsertConfig(config)
        AppGuardRuntimeCache.updateConfig(config)
        if (securityQuestions.size >= 3) {
            saveSecurityQuestions(securityQuestions)
        }
        logEvent(
            packageName = context.packageName,
            appName = "VaultGuard",
            eventType = "VAULT_INITIALIZED",
            detail = "Admin password, wake word & ${securityQuestions.size} reset questions configured",
        )
    }

    suspend fun saveSecurityQuestions(questionsAndAnswers: List<Pair<String, String>>) {
        val now = System.currentTimeMillis()
        val entities = questionsAndAnswers
            .map { (q, a) -> q.trim() to a.trim() }
            .filter { (q, a) -> q.isNotBlank() && a.isNotBlank() }
            .map { (q, a) ->
                SecurityQuestionEntity(
                    question = q,
                    answerHash = PasswordHasher.hashSecurityAnswer(a),
                    encryptedAnswer = SecretCipher.encryptAnswer(a),
                    createdAtMillis = now,
                )
            }
        require(entities.size >= 3) { "At least 3 security questions with answers are required." }
        dao.replaceSecurityQuestions(entities)
        logEvent(
            packageName = context.packageName,
            appName = "VaultGuard",
            eventType = "RESET_QUESTIONS_UPDATED",
            detail = "Saved ${entities.size} encrypted backup password reset answers",
        )
    }

    fun decryptStoredAnswer(entity: SecurityQuestionEntity): String {
        val decrypted = SecretCipher.decryptAnswer(entity.encryptedAnswer)
        return decrypted.ifBlank { "(Saved securely)" }
    }

    suspend fun verifySecurityAnswersForReset(answersByQuestionId: Map<Long, String>): Boolean {
        val saved = dao.getSecurityQuestionsOnce()
        val hashMap = saved.associate { it.id to it.answerHash }
        return PasswordHasher.qualifiesForPasswordReset(
            storedHashesByQuestionId = hashMap,
            providedAnswersByQuestionId = answersByQuestionId,
            minQuestionsRequired = 3,
            minMatchesRequired = 2,
        )
    }

    suspend fun resetPasswordViaSecurityQuestions(
        answersByQuestionId: Map<Long, String>,
        newPassword: String,
    ): Boolean {
        if (!verifySecurityAnswersForReset(answersByQuestionId)) return false
        val current = dao.getConfigOnce() ?: return false
        val updated = current.copy(passwordHash = PasswordHasher.hashPassword(newPassword))
        dao.upsertConfig(updated)
        AppGuardRuntimeCache.updateConfig(updated)
        logEvent(
            packageName = context.packageName,
            appName = "VaultGuard",
            eventType = "PASSWORD_RESET",
            detail = "Admin password reset after verifying backup security questions",
        )
        return true
    }

    suspend fun importMediaToVault(uris: List<Uri>): VaultImportResult {
        val result = vaultMediaManager.copyUrisToPrivateVault(uris)
        if (result.importedItems.isEmpty()) return result
        logEvent(
            packageName = context.packageName,
            appName = "Media Vault",
            eventType = "VAULT_MEDIA_HIDDEN",
            detail = "Encrypted & hid ${result.importedItems.size} item(s) in private Vault storage",
        )
        return result
    }

    suspend fun markVaultMediaHiddenFromGallery(ids: List<String>) {
        vaultMediaManager.markItemsRemovedFromGallery(ids)
    }

    suspend fun deleteVaultMediaReturningToGallery(item: VaultMediaEntity): Result<String> {
        val res = vaultMediaManager.deleteFromVaultReturningToGallery(item)
        res.onSuccess {
            logEvent(
                packageName = context.packageName,
                appName = "Media Vault",
                eventType = "VAULT_MEDIA_RETURNED_TO_GALLERY",
                detail = "Removed ${item.displayName} from Vault & returned to ${item.originalRelativePath.ifBlank { "phone gallery" }}",
            )
        }
        return res
    }

    suspend fun decryptVaultPhotoBytes(item: VaultMediaEntity): ByteArray? =
        vaultMediaManager.decryptPhotoBytes(item)

    suspend fun decryptVaultStreamFile(item: VaultMediaEntity): File? =
        vaultMediaManager.decryptToInAppStreamFile(item)

    suspend fun decryptVaultDocPreviewFile(item: VaultMediaEntity): File? =
        vaultMediaManager.decryptToDocPreviewFile(item)

    fun clearUnlockedVaultTempFiles() {
        vaultMediaManager.clearUnlockedTempPreviews()
    }

    suspend fun verifyAdminPassword(password: String): Boolean {
        val config = dao.getConfigOnce() ?: return false
        return PasswordHasher.verifyPassword(password, config.passwordHash)
    }

    suspend fun updatePassword(newPassword: String) {
        val current = dao.getConfigOnce() ?: AdminConfigEntity()
        val updated = current.copy(passwordHash = PasswordHasher.hashPassword(newPassword))
        dao.upsertConfig(updated)
        AppGuardRuntimeCache.updateConfig(updated)
        logEvent(context.packageName, "VaultGuard", "PASSWORD_CHANGED", "Admin password rotated")
    }

    suspend fun setGuardActive(active: Boolean) {
        val current = dao.getConfigOnce() ?: AdminConfigEntity()
        val updated = current.copy(guardActive = active)
        dao.upsertConfig(updated)
        AppGuardRuntimeCache.updateConfig(updated)
        logEvent(
            context.packageName,
            "VaultGuard",
            if (active) "GUARD_ENABLED" else "GUARD_PAUSED",
            if (active) "App limiting enforcement active" else "App limiting enforcement paused",
        )
    }

    suspend fun setUninstallProtection(enabled: Boolean) {
        val current = dao.getConfigOnce() ?: AdminConfigEntity()
        val updated = current.copy(uninstallProtectionEnabled = enabled)
        dao.upsertConfig(updated)
        AppGuardRuntimeCache.updateConfig(updated)
    }

    suspend fun setInstallGuard(enabled: Boolean) {
        val current = dao.getConfigOnce() ?: AdminConfigEntity()
        val updated = current.copy(installGuardEnabled = enabled)
        dao.upsertConfig(updated)
        AppGuardRuntimeCache.updateConfig(updated)
        logEvent(
            context.packageName,
            "VaultGuard",
            if (enabled) "INSTALL_GUARD_ON" else "INSTALL_GUARD_OFF",
            if (enabled) "New app installations require admin password" else "Install guard disabled",
        )
    }

    suspend fun setNotificationBlockEnabled(enabled: Boolean) {
        val current = dao.getConfigOnce() ?: AdminConfigEntity()
        val updated = current.copy(notificationBlockEnabled = enabled)
        dao.upsertConfig(updated)
        AppGuardRuntimeCache.updateConfig(updated)
    }

    suspend fun setCapturePhotoOnOpenEnabled(enabled: Boolean) {
        val current = dao.getConfigOnce() ?: AdminConfigEntity()
        val updated = current.copy(capturePhotoOnOpenEnabled = enabled)
        dao.upsertConfig(updated)
        AppGuardRuntimeCache.updateConfig(updated)
    }

    suspend fun updateStealthTriggers(wakeWord: String, dialerPin: String) {
        val current = dao.getConfigOnce() ?: AdminConfigEntity()
        val cleanWake = wakeWord.trim().lowercase().ifBlank { current.wakeWord }
        val cleanPin = dialerPin.filter { it.isDigit() }.ifBlank {
            PasswordHasher.wakeWordToT9Digits(cleanWake).ifBlank { "82858" }
        }
        val updated = current.copy(wakeWord = cleanWake, dialerPin = cleanPin)
        dao.upsertConfig(updated)
        AppGuardRuntimeCache.updateConfig(updated)
        logEvent(
            context.packageName,
            "VaultGuard",
            "STEALTH_UPDATED",
            "Wake word & dialer trigger updated",
        )
    }

    suspend fun setDisguiseAndVisibility(
        disguise: DisguiseIdentity,
        hideFromLauncher: Boolean,
    ) {
        val current = dao.getConfigOnce() ?: AdminConfigEntity()
        val updated = current.copy(
            disguiseName = disguise.name,
            isAppHidden = hideFromLauncher,
        )
        dao.upsertConfig(updated)
        AppGuardRuntimeCache.updateConfig(updated)
        launcherManager.applyLauncherState(disguise, hideFromLauncher)
        logEvent(
            context.packageName,
            disguise.displayLabel,
            if (hideFromLauncher) "HIDDEN_MODE_ON" else "DISGUISE_APPLIED",
            if (hideFromLauncher) {
                "App hidden from launcher; wake via search or dialer"
            } else {
                "Launcher identity changed to ${disguise.displayLabel}"
            },
        )
    }

    suspend fun setRedirectDisplayStyle(style: RedirectDisplayStyle) {
        val current = dao.getConfigOnce() ?: AdminConfigEntity()
        val updated = current.copy(redirectDisplayName = style.name)
        dao.upsertConfig(updated)
        AppGuardRuntimeCache.updateConfig(updated)
        logEvent(
            context.packageName,
            "VaultGuard",
            "ICON_DISPLAY_UPDATED",
            "Redirect decoy display set to ${style.displayLabel}",
        )
    }

    suspend fun setRedirectDecoyMode(mode: RedirectDecoyMode) {
        val current = dao.getConfigOnce() ?: AdminConfigEntity()
        val updated = current.copy(redirectDecoyMode = mode.name)
        dao.upsertConfig(updated)
        AppGuardRuntimeCache.updateConfig(updated)
        val label = if (mode == RedirectDecoyMode.FAKE_CRASH) "Fake Crash" else "Icon Display"
        logEvent(
            context.packageName,
            "VaultGuard",
            "REDIRECT_DECOY_MODE",
            "Redirect gate switched exclusively to $label",
        )
    }

    suspend fun setImmediateLockTimeoutSeconds(seconds: Int) {
        val clamped = when (seconds) {
            5, 15, 60 -> seconds
            else -> 0
        }
        val current = dao.getConfigOnce() ?: AdminConfigEntity()
        val updated = current.copy(immediateLockTimeoutSeconds = clamped)
        dao.upsertConfig(updated)
        AppGuardRuntimeCache.updateConfig(updated)
        val label = when (clamped) {
            0 -> "Immediate"
            60 -> "1 minute"
            else -> "$clamped seconds"
        }
        logEvent(
            context.packageName,
            "VaultGuard",
            "RE_LOCK_TIMER_UPDATED",
            "App switch re-lock delay set to $label",
        )
    }

    suspend fun setAppGuardRule(
        packageName: String,
        appName: String,
        guardMode: AppGuardMode,
        privacyMode: NotificationPrivacyMode = NotificationPrivacyMode.SILENT_AND_HIDE,
    ): Result<Unit> {
        val config = dao.getConfigOnce()
        if (guardMode != AppGuardMode.NONE && config?.isInitialized != true) {
            return Result.failure(IllegalStateException("Set Admin password first."))
        }
        val existing = dao.getLimitedApp(packageName)
        val isLimited = guardMode != AppGuardMode.NONE
        val effectivePrivacy = if (isLimited) {
            privacyMode
        } else {
            existing?.notificationPrivacyEnum ?: NotificationPrivacyMode.SILENT_AND_HIDE
        }
        val updated = (existing ?: LimitedAppEntity(packageName = packageName, appName = appName))
            .copy(
                appName = appName,
                isLimited = isLimited,
                guardMode = AppGuardMode.RESTRICTED.name,
                notificationPrivacyMode = effectivePrivacy.name,
                tempUnlockUntilMillis = 0L,
            )
        dao.upsertLimitedApp(updated)
        syncRuntimeCache()
        val detail = if (isLimited) {
            if (effectivePrivacy == NotificationPrivacyMode.SILENT_AND_HIDE) {
                "Restricted • Silent & Hidden"
            } else {
                "Restricted • Show, Hide Content"
            }
        } else {
            "Unrestricted"
        }
        logEvent(
            packageName = packageName,
            appName = appName,
            eventType = if (isLimited) "APP_RESTRICTED" else "APP_UNRESTRICTED",
            detail = detail,
        )
        return Result.success(Unit)
    }

    suspend fun setAppLimited(packageName: String, appName: String, limited: Boolean) {
        setAppGuardRule(
            packageName = packageName,
            appName = appName,
            guardMode = if (limited) AppGuardMode.RESTRICTED else AppGuardMode.NONE,
            privacyMode = NotificationPrivacyMode.SILENT_AND_HIDE,
        )
    }

    suspend fun grantSessionUnlock(
        packageName: String,
        appName: String,
        photoPath: String? = null,
    ) {
        AppGuardRuntimeCache.grantSessionUnlock(packageName)
        logEvent(
            packageName = packageName,
            appName = appName,
            eventType = "SESSION_UNLOCKED",
            detail = "Unlocked",
            photoPath = photoPath,
        )
    }

    suspend fun recordRedirectBlock(
        packageName: String,
        appName: String,
        decoyMode: RedirectDecoyMode = RedirectDecoyMode.ICON_DISPLAY,
    ): Long? {
        val existing = dao.getLimitedApp(packageName) ?: return null
        val now = System.currentTimeMillis()
        dao.upsertLimitedApp(
            existing.copy(
                blockCount = existing.blockCount + 1,
                lastBlockedAtMillis = now,
            )
        )
        syncRuntimeCache()
        val gateDesc = if (decoyMode == RedirectDecoyMode.FAKE_CRASH) "Fake Crash" else "Icon Display"
        return logEvent(
            packageName = packageName,
            appName = appName,
            eventType = "RESTRICTED_GATE",
            detail = gateDesc,
        )
    }

    suspend fun attachPhotoToEvent(eventId: Long, photoPath: String) {
        dao.updateEventPhoto(eventId, photoPath)
    }

    suspend fun logEvent(
        packageName: String,
        appName: String,
        eventType: String,
        detail: String,
        photoPath: String? = null,
    ): Long {
        return dao.insertEvent(
            AccessEventEntity(
                packageName = packageName,
                appName = appName,
                eventType = eventType,
                detail = detail,
                photoPath = photoPath,
                timestampMillis = System.currentTimeMillis(),
            )
        )
    }

    suspend fun clearEvents() {
        dao.clearEvents()
        runCatching {
            File(context.filesDir, "session_photos").deleteRecursively()
        }
    }

    suspend fun loadInstalledApps(): List<InstalledAppInfo> = appsProvider.loadLaunchableApps()

    fun getInstalledAppInfo(packageName: String): InstalledAppInfo? =
        appsProvider.getAppInfoForPackage(packageName)

    fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = ComponentName(context, AppGuardAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return AppGuardRuntimeCache.accessibilityConnected.value

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val componentStr = splitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentStr)
            if (enabledComponent == expectedComponent) {
                return true
            }
        }
        return AppGuardRuntimeCache.accessibilityConnected.value
    }

    fun isNotificationListenerEnabled(): Boolean {
        val expected = ComponentName(context, VaultNotificationListenerService::class.java)
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return AppGuardRuntimeCache.notificationListenerConnected.value

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledListeners)
        while (splitter.hasNext()) {
            val component = ComponentName.unflattenFromString(splitter.next())
            if (component == expected) {
                return true
            }
        }
        return AppGuardRuntimeCache.notificationListenerConnected.value
    }

    fun isNotificationPolicyAccessGranted(): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false
        return nm.isNotificationPolicyAccessGranted
    }

    fun isCameraPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isPostNotificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isDeviceAdminActive(): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        val adminComponent = ComponentName(context, VaultDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(adminComponent)
    }
}
