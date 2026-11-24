package com.example.ota

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.data.cloud.AppRelease
import com.example.data.cloud.CloudSyncRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class OtaUiState(
    val installedVersionCode: Long = 1L,
    val installedVersionName: String = "1.0",
    val installedSigningSha256: String = "",
    val latestRelease: AppRelease? = null,
    val hasUpdate: Boolean = false,
    val updateBannerDismissed: Boolean = false,
    val isChecking: Boolean = false,
    val lastCheckedAtMillis: Long = 0L,
    val isDownloading: Boolean = false,
    val downloadProgress: Float? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val verifiedApkPath: String? = null,
    val canRequestPackageInstalls: Boolean = false,
    val needsUnknownSourcesPermission: Boolean = false,
    val updateError: String? = null,
    val updateStatusMessage: String? = null,
    val inspectedPublishApk: ApkInspectResult? = null,
    val isInspectingApk: Boolean = false,
    val isPublishing: Boolean = false,
    val uploadProgress: Float? = null,
    val publishError: String? = null,
    val publishSuccessMessage: String? = null,
)

class OtaUpdateManager(
    context: Context,
    private val cloudSyncRepository: CloudSyncRepository,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _state = MutableStateFlow(
        OtaUiState(
            installedVersionCode = ApkSecurityVerifier.getInstalledVersionCode(appContext),
            installedVersionName = ApkSecurityVerifier.getInstalledVersionName(appContext),
            installedSigningSha256 = ApkSecurityVerifier.getInstalledAppSigningSha256List(appContext)
                .firstOrNull()
                .orEmpty(),
            canRequestPackageInstalls = ApkSecurityVerifier.canRequestPackageInstalls(appContext),
        )
    )
    val state: StateFlow<OtaUiState> = _state.asStateFlow()

    private var downloadJob: Job? = null
    private var publishJob: Job? = null

    fun refreshInstallCapability() {
        val canInstall = ApkSecurityVerifier.canRequestPackageInstalls(appContext)
        _state.update { current ->
            current.copy(
                canRequestPackageInstalls = canInstall,
                needsUnknownSourcesPermission = current.needsUnknownSourcesPermission && !canInstall,
            )
        }
    }

    fun onLatestReleaseObserved(release: AppRelease?) {
        val installedCode = ApkSecurityVerifier.getInstalledVersionCode(appContext)
        val installedHashes = ApkSecurityVerifier.getInstalledAppSigningSha256List(appContext)
        val isNewer = release != null &&
            release.packageName == appContext.packageName &&
            ApkSecurityVerifier.isVersionNewer(release.versionCode, installedCode)

        val signingTrusted = release == null || ApkSecurityVerifier.doSigningHashesMatch(
            installedHashes = installedHashes,
            candidateHashes = setOf(release.signingSha256),
            expectedReleaseHash = release.signingSha256,
        )

        val previousVersion = _state.value.latestRelease?.versionCode
        val cleanBannerDismiss = if (release?.versionCode != previousVersion) {
            false
        } else {
            _state.value.updateBannerDismissed
        }

        val cleanVerifiedApk = _state.value.verifiedApkPath?.takeIf {
            release != null && File(it).exists() && previousVersion == release.versionCode
        }

        _state.update { current ->
            current.copy(
                installedVersionCode = installedCode,
                installedVersionName = ApkSecurityVerifier.getInstalledVersionName(appContext),
                installedSigningSha256 = installedHashes.firstOrNull().orEmpty(),
                latestRelease = release,
                hasUpdate = isNewer,
                updateBannerDismissed = cleanBannerDismiss,
                verifiedApkPath = cleanVerifiedApk,
                updateError = if (isNewer && !signingTrusted) {
                    "Latest release v${release?.versionName} has an untrusted signing key and cannot be installed."
                } else {
                    current.updateError
                },
            )
        }
    }

    fun checkForUpdates() {
        if (_state.value.isChecking) return
        scope.launch {
            _state.update {
                it.copy(
                    isChecking = true,
                    updateError = null,
                )
            }
            val result = cloudSyncRepository.fetchLatestAppRelease()
            result.fold(
                onSuccess = { release ->
                    onLatestReleaseObserved(release)
                    _state.update {
                        it.copy(
                            isChecking = false,
                            lastCheckedAtMillis = System.currentTimeMillis(),
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            isChecking = false,
                            lastCheckedAtMillis = System.currentTimeMillis(),
                            updateError = error.localizedMessage ?: "Failed to check Firebase for updates.",
                        )
                    }
                },
            )
        }
    }

    fun dismissUpdateBanner() {
        _state.update { it.copy(updateBannerDismissed = true) }
    }

    fun clearUpdateMessages() {
        _state.update {
            it.copy(
                updateError = null,
                updateStatusMessage = null,
                publishError = null,
                publishSuccessMessage = null,
            )
        }
    }

    fun downloadAndInstallUpdate() {
        val release = _state.value.latestRelease ?: return
        val existingVerified = _state.value.verifiedApkPath
        if (existingVerified != null && File(existingVerified).exists()) {
            launchVerifiedApkInstaller()
            return
        }
        if (_state.value.isDownloading) return

        val installedHashes = ApkSecurityVerifier.getInstalledAppSigningSha256List(appContext)
        if (!ApkSecurityVerifier.doSigningHashesMatch(
                installedHashes = installedHashes,
                candidateHashes = setOf(release.signingSha256),
                expectedReleaseHash = release.signingSha256,
            )
        ) {
            _state.update {
                it.copy(
                    updateError = "Security check failed: release signing key does not match the installed app.",
                )
            }
            return
        }

        if (!release.downloadUrl.startsWith("https://")) {
            _state.update { it.copy(updateError = "Refusing insecure non-HTTPS APK URL.") }
            return
        }

        downloadJob?.cancel()
        downloadJob = scope.launch {
            _state.update {
                it.copy(
                    isDownloading = true,
                    downloadProgress = 0f,
                    downloadedBytes = 0L,
                    totalBytes = release.apkSizeBytes,
                    updateError = null,
                    updateStatusMessage = "Downloading v${release.versionName} in background...",
                    needsUnknownSourcesPermission = false,
                )
            }

            val updatesDir = File(appContext.filesDir, "ota_updates").apply { mkdirs() }
            updatesDir.listFiles()?.forEach { old ->
                if (old.name != "VaultGuard_v${release.versionCode}.apk") {
                    runCatching { old.delete() }
                }
            }
            val partFile = File(updatesDir, "VaultGuard_v${release.versionCode}.apk.part")
            val finalFile = File(updatesDir, "VaultGuard_v${release.versionCode}.apk")

            try {
                val request = Request.Builder().url(release.downloadUrl).build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Download failed (HTTP ${response.code}).")
                    }
                    val body = response.body ?: throw IllegalStateException("Empty download response.")
                    val expectedLen = body.contentLength().takeIf { it > 0L } ?: release.apkSizeBytes

                    body.byteStream().use { input ->
                        FileOutputStream(partFile).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var totalRead = 0L
                            var lastProgressUpdate = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                totalRead += read
                                val now = System.currentTimeMillis()
                                if (now - lastProgressUpdate >= 120L || totalRead == expectedLen) {
                                    lastProgressUpdate = now
                                    val fraction = if (expectedLen > 0L) {
                                        (totalRead.toFloat() / expectedLen.toFloat()).coerceIn(0f, 1f)
                                    } else {
                                        null
                                    }
                                    _state.update {
                                        it.copy(
                                            downloadProgress = fraction,
                                            downloadedBytes = totalRead,
                                            totalBytes = expectedLen,
                                        )
                                    }
                                }
                            }
                            output.fd.sync()
                        }
                    }
                }

                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }

                _state.update {
                    it.copy(
                        downloadProgress = 1f,
                        updateStatusMessage = "Verifying APK signature & version...",
                    )
                }

                when (val verification = ApkSecurityVerifier.verifyDownloadedApk(
                    context = appContext,
                    apkFile = finalFile,
                    expectedVersionCode = release.versionCode,
                    expectedSigningSha256 = release.signingSha256,
                )) {
                    is ApkVerifyResult.Invalid -> {
                        runCatching { finalFile.delete() }
                        _state.update {
                            it.copy(
                                isDownloading = false,
                                downloadProgress = null,
                                verifiedApkPath = null,
                                updateError = verification.reason,
                                updateStatusMessage = null,
                            )
                        }
                    }

                    is ApkVerifyResult.Valid -> {
                        val canInstall = ApkSecurityVerifier.canRequestPackageInstalls(appContext)
                        _state.update {
                            it.copy(
                                isDownloading = false,
                                downloadProgress = 1f,
                                verifiedApkPath = verification.filePath,
                                canRequestPackageInstalls = canInstall,
                                needsUnknownSourcesPermission = !canInstall,
                                updateStatusMessage = if (canInstall) {
                                    "Signature verified. Launching package installer..."
                                } else {
                                    "Signature verified. Allow app installs from VaultGuard to proceed."
                                },
                            )
                        }
                        if (canInstall) {
                            withContext(Dispatchers.Main) {
                                launchPackageInstallerFile(File(verification.filePath))
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { partFile.delete() }
                _state.update {
                    it.copy(
                        isDownloading = false,
                        downloadProgress = null,
                        updateError = e.localizedMessage ?: "Failed to download APK update.",
                        updateStatusMessage = null,
                    )
                }
            }
        }
    }

    fun launchVerifiedApkInstaller() {
        val path = _state.value.verifiedApkPath ?: return
        val file = File(path)
        if (!file.exists()) {
            _state.update {
                it.copy(
                    verifiedApkPath = null,
                    updateError = "Verified APK is no longer cached. Tap Update Now to download again.",
                )
            }
            return
        }
        val canInstall = ApkSecurityVerifier.canRequestPackageInstalls(appContext)
        _state.update {
            it.copy(
                canRequestPackageInstalls = canInstall,
                needsUnknownSourcesPermission = !canInstall,
            )
        }
        if (!canInstall) {
            openUnknownSourcesSettings()
            return
        }
        launchPackageInstallerFile(file)
    }

    fun openUnknownSourcesSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${appContext.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { appContext.startActivity(intent) }
    }

    private fun launchPackageInstallerFile(apkFile: File) {
        try {
            val contentUri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                apkFile,
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            appContext.startActivity(installIntent)
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    updateError = "Could not open package installer: ${e.localizedMessage ?: "unknown error"}",
                )
            }
        }
    }

    fun inspectApkUriForPublish(uri: Uri) {
        scope.launch {
            _state.update {
                it.copy(
                    isInspectingApk = true,
                    publishError = null,
                    publishSuccessMessage = null,
                )
            }
            try {
                val stagingDir = File(appContext.cacheDir, "ota_publish_staging").apply { mkdirs() }
                stagingDir.listFiles()?.forEach { runCatching { it.delete() } }

                val displayName = resolveDisplayName(uri) ?: "VaultGuard-release.apk"
                val stagedFile = File(stagingDir, "staged_release.apk")

                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(stagedFile).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                } ?: throw IllegalStateException("Unable to read selected APK file.")

                val inspected = ApkSecurityVerifier.inspectApkFile(
                    context = appContext,
                    apkFile = stagedFile,
                    displayFileName = displayName,
                )
                _state.update {
                    it.copy(
                        isInspectingApk = false,
                        inspectedPublishApk = inspected,
                        publishError = if (inspected.isValidForPublish) null else inspected.validationMessage,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isInspectingApk = false,
                        inspectedPublishApk = null,
                        publishError = e.localizedMessage ?: "Failed to inspect selected APK.",
                    )
                }
            }
        }
    }

    fun publishUpdate(
        releaseNotes: String,
        overrideVersionCode: String,
        overrideVersionName: String,
    ) {
        val inspected = _state.value.inspectedPublishApk
        if (inspected == null) {
            _state.update { it.copy(publishError = "Select a signed APK file first.") }
            return
        }
        if (!inspected.isValidForPublish) {
            _state.update { it.copy(publishError = inspected.validationMessage) }
            return
        }
        val apkFile = File(inspected.filePath)
        if (!apkFile.exists()) {
            _state.update { it.copy(publishError = "Staged APK missing; please select the APK again.") }
            return
        }

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) {
            _state.update { it.copy(publishError = "Sign in with Google before publishing an update.") }
            return
        }

        val finalVersionCode = overrideVersionCode.trim().toLongOrNull() ?: inspected.versionCode
        if (finalVersionCode < 1L) {
            _state.update { it.copy(publishError = "Version code must be a positive integer.") }
            return
        }
        val finalVersionName = overrideVersionName.trim().ifBlank { inspected.versionName }

        if (_state.value.isPublishing) return
        publishJob?.cancel()
        publishJob = scope.launch {
            _state.update {
                it.copy(
                    isPublishing = true,
                    uploadProgress = 0f,
                    publishError = null,
                    publishSuccessMessage = null,
                )
            }
            try {
                val storage = FirebaseStorage.getInstance()
                val objectPath = "app_releases/$uid/VaultGuard_v${finalVersionCode}_${System.currentTimeMillis()}.apk"
                val ref = storage.reference.child(objectPath)

                val uploadTask = ref.putFile(Uri.fromFile(apkFile))
                uploadTask.addOnProgressListener { snap ->
                    val total = snap.totalByteCount
                    if (total > 0L) {
                        val fraction = (snap.bytesTransferred.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                        _state.update { it.copy(uploadProgress = fraction) }
                    }
                }
                uploadTask.await()

                val downloadUri = ref.downloadUrl.await()
                val downloadUrl = downloadUri.toString()

                val publishResult = cloudSyncRepository.publishAppRelease(
                    packageName = inspected.packageName,
                    versionCode = finalVersionCode,
                    versionName = finalVersionName,
                    releaseNotes = releaseNotes,
                    downloadUrl = downloadUrl,
                    signingSha256 = inspected.primarySigningSha256,
                    apkSizeBytes = inspected.sizeBytes,
                )

                publishResult.fold(
                    onSuccess = { published ->
                        runCatching { apkFile.delete() }
                        onLatestReleaseObserved(published)
                        _state.update {
                            it.copy(
                                isPublishing = false,
                                uploadProgress = 1f,
                                inspectedPublishApk = null,
                                publishError = null,
                                publishSuccessMessage = "Published v${published.versionName} (code ${published.versionCode}) to Firebase.",
                            )
                        }
                    },
                    onFailure = { err ->
                        _state.update {
                            it.copy(
                                isPublishing = false,
                                uploadProgress = null,
                                publishError = err.localizedMessage ?: "Failed to save release metadata to Firestore.",
                            )
                        }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val friendlyError = when (e) {
                    is StorageException -> {
                        if (e.errorCode == StorageException.ERROR_NOT_AUTHORIZED ||
                            e.httpResultCode == 403 || e.httpResultCode == 404
                        ) {
                            "Firebase Storage upload failed (${e.httpResultCode}). Enable Firebase Storage and allow authenticated writes under /app_releases/{uid}/ in Firebase Console."
                        } else {
                            e.localizedMessage ?: "Firebase Storage upload failed."
                        }
                    }
                    else -> e.localizedMessage ?: "Failed to upload APK."
                }
                _state.update {
                    it.copy(
                        isPublishing = false,
                        uploadProgress = null,
                        publishError = friendlyError,
                    )
                }
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        }.getOrNull()
    }
}
