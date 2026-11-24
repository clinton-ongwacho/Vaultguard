package com.example.ota

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

data class ApkInspectResult(
    val filePath: String,
    val fileName: String,
    val sizeBytes: Long,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val signingSha256Set: Set<String>,
    val primarySigningSha256: String,
    val matchesPackageId: Boolean,
    val matchesInstalledSigningKey: Boolean,
    val isValidForPublish: Boolean,
    val validationMessage: String,
)

sealed class ApkVerifyResult {
    data class Valid(
        val filePath: String,
        val versionCode: Long,
        val versionName: String,
        val signingSha256: String,
    ) : ApkVerifyResult()

    data class Invalid(val reason: String) : ApkVerifyResult()
}

object ApkSecurityVerifier {

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun isVersionNewer(candidateCode: Long, installedCode: Long): Boolean {
        return candidateCode > installedCode
    }

    fun doSigningHashesMatch(
        installedHashes: Set<String>,
        candidateHashes: Set<String>,
        expectedReleaseHash: String? = null,
    ): Boolean {
        val normalizedInstalled = installedHashes.map { it.trim().lowercase() }.filter { it.length == 64 }.toSet()
        val normalizedCandidate = candidateHashes.map { it.trim().lowercase() }.filter { it.length == 64 }.toSet()
        if (normalizedInstalled.isEmpty() || normalizedCandidate.isEmpty()) return false
        val commonInstalledAndCandidate = normalizedInstalled.intersect(normalizedCandidate)
        if (commonInstalledAndCandidate.isEmpty()) return false
        val expected = expectedReleaseHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return expected == null || expected in commonInstalledAndCandidate
    }

    fun getInstalledVersionCode(context: Context): Long {
        val info = getInstalledPackageInfo(context) ?: return 1L
        return PackageInfoCompat.getLongVersionCode(info)
    }

    fun getInstalledVersionName(context: Context): String {
        val info = getInstalledPackageInfo(context) ?: return "1.0"
        return info.versionName ?: "1.0"
    }

    fun getInstalledAppSigningSha256List(context: Context): Set<String> {
        val info = getInstalledPackageInfo(context) ?: return emptySet()
        return extractSigningSha256Set(info)
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        } else {
            true
        }
    }

    fun inspectApkFile(context: Context, apkFile: File, displayFileName: String = apkFile.name): ApkInspectResult {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            return ApkInspectResult(
                filePath = apkFile.absolutePath,
                fileName = displayFileName,
                sizeBytes = 0L,
                packageName = "",
                versionCode = 0L,
                versionName = "",
                signingSha256Set = emptySet(),
                primarySigningSha256 = "",
                matchesPackageId = false,
                matchesInstalledSigningKey = false,
                isValidForPublish = false,
                validationMessage = "APK file is empty or unreadable.",
            )
        }

        val archiveInfo = getArchivePackageInfo(context.packageManager, apkFile.absolutePath)
            ?: return ApkInspectResult(
                filePath = apkFile.absolutePath,
                fileName = displayFileName,
                sizeBytes = apkFile.length(),
                packageName = "",
                versionCode = 0L,
                versionName = "",
                signingSha256Set = emptySet(),
                primarySigningSha256 = "",
                matchesPackageId = false,
                matchesInstalledSigningKey = false,
                isValidForPublish = false,
                validationMessage = "Invalid or corrupted APK package.",
            )

        val candidatePkg = archiveInfo.packageName.orEmpty()
        val candidateVersionCode = PackageInfoCompat.getLongVersionCode(archiveInfo)
        val candidateVersionName = archiveInfo.versionName.orEmpty().ifBlank { "1.0" }
        val candidateHashes = extractSigningSha256Set(archiveInfo)
        val installedHashes = getInstalledAppSigningSha256List(context)

        val pkgMatches = candidatePkg == context.packageName
        val sigMatches = doSigningHashesMatch(installedHashes, candidateHashes)
        val primaryHash = candidateHashes.firstOrNull { it in installedHashes }
            ?: candidateHashes.firstOrNull().orEmpty()

        val message = when {
            !pkgMatches -> "Package ID mismatch: expected ${context.packageName}, found $candidatePkg."
            candidateHashes.isEmpty() -> "No signing certificate found inside APK."
            !sigMatches -> "Signing key mismatch: APK must be signed with the same key as this installed app."
            else -> "Verified APK signature matches this app's signing key."
        }

        return ApkInspectResult(
            filePath = apkFile.absolutePath,
            fileName = displayFileName,
            sizeBytes = apkFile.length(),
            packageName = candidatePkg,
            versionCode = candidateVersionCode,
            versionName = candidateVersionName,
            signingSha256Set = candidateHashes,
            primarySigningSha256 = primaryHash,
            matchesPackageId = pkgMatches,
            matchesInstalledSigningKey = sigMatches,
            isValidForPublish = pkgMatches && sigMatches && candidateVersionCode >= 1L,
            validationMessage = message,
        )
    }

    fun verifyDownloadedApk(
        context: Context,
        apkFile: File,
        expectedVersionCode: Long,
        expectedSigningSha256: String,
    ): ApkVerifyResult {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            return ApkVerifyResult.Invalid("Downloaded APK file is missing or empty.")
        }
        val archiveInfo = getArchivePackageInfo(context.packageManager, apkFile.absolutePath)
            ?: return ApkVerifyResult.Invalid("Downloaded file is not a valid Android APK.")

        val candidatePkg = archiveInfo.packageName.orEmpty()
        if (candidatePkg != context.packageName) {
            return ApkVerifyResult.Invalid("Security rejection: APK package ($candidatePkg) does not match ${context.packageName}.")
        }

        val installedCode = getInstalledVersionCode(context)
        val candidateCode = PackageInfoCompat.getLongVersionCode(archiveInfo)
        if (!isVersionNewer(candidateCode, installedCode)) {
            return ApkVerifyResult.Invalid("Version check failed: APK v$candidateCode is not newer than installed v$installedCode.")
        }
        if (candidateCode != expectedVersionCode) {
            return ApkVerifyResult.Invalid("Version mismatch: expected v$expectedVersionCode, APK contains v$candidateCode.")
        }

        val installedHashes = getInstalledAppSigningSha256List(context)
        val candidateHashes = extractSigningSha256Set(archiveInfo)
        if (!doSigningHashesMatch(installedHashes, candidateHashes, expectedSigningSha256)) {
            return ApkVerifyResult.Invalid("Security rejection: APK signing key does not match the installed VaultGuard certificate.")
        }

        val verifiedHash = candidateHashes.firstOrNull { it == expectedSigningSha256.lowercase() }
            ?: candidateHashes.first()
        return ApkVerifyResult.Valid(
            filePath = apkFile.absolutePath,
            versionCode = candidateCode,
            versionName = archiveInfo.versionName.orEmpty().ifBlank { "1.0" },
            signingSha256 = verifiedHash,
        )
    }

    @Suppress("DEPRECATION")
    private fun getInstalledPackageInfo(context: Context): PackageInfo? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun getArchivePackageInfo(pm: PackageManager, apkPath: String): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return runCatching { pm.getPackageArchiveInfo(apkPath, flags) }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun extractSigningSha256Set(packageInfo: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo
            if (signingInfo != null) {
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            } else {
                packageInfo.signatures
            }
        } else {
            packageInfo.signatures
        } ?: return emptySet()

        return signatures.mapNotNull { sig ->
            runCatching { sha256Hex(sig.toByteArray()) }.getOrNull()
        }.toSet()
    }
}
