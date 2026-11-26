package com.example.ota

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
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

    fun isValidOtaDownloadUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return false
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (!uri.rawUserInfo.isNullOrEmpty()) return false
        val port = uri.port
        if (port != -1 && port != 443) return false
        val rawHost = uri.host?.trim()?.trim('[', ']')?.lowercase() ?: return false
        if (rawHost.isEmpty()) return false

        val blockedHosts = setOf("localhost", "ip6-localhost", "ip6-loopback")
        if (rawHost in blockedHosts ||
            rawHost.endsWith(".localhost") ||
            rawHost.endsWith(".local") ||
            rawHost.endsWith(".internal") ||
            rawHost.endsWith(".lan") ||
            rawHost.endsWith(".home")
        ) {
            return false
        }

        val isIpv4Literal = rawHost.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))
        val isIpv6Literal = rawHost.contains(':')
        if (isIpv4Literal || isIpv6Literal) {
            val addr = runCatching { InetAddress.getByName(rawHost) }.getOrNull() ?: return false
            if (addr.isAnyLocalAddress ||
                addr.isLoopbackAddress ||
                addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress ||
                addr.isMulticastAddress
            ) {
                return false
            }
            if (addr is Inet4Address) {
                val b = addr.address
                val o0 = b[0].toInt() and 0xFF
                val o1 = b[1].toInt() and 0xFF
                // Block 0.0.0.0/8, 100.64.0.0/10 (CGNAT), 127.0.0.0/8, 169.254.0.0/16, 172.16.0.0/12, 192.168.0.0/16, >=224.0.0.0
                if (o0 == 0 || o0 == 10 || o0 == 127 || o0 >= 224) return false
                if (o0 == 100 && o1 in 64..127) return false
                if (o0 == 169 && o1 == 254) return false
                if (o0 == 172 && o1 in 16..31) return false
                if (o0 == 192 && o1 == 168) return false
            } else if (addr is Inet6Address) {
                val firstByte = addr.address[0].toInt() and 0xFF
                // Block fc00::/7 (Unique Local Addresses)
                if ((firstByte and 0xFE) == 0xFC) return false
            }
        }
        return true
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
