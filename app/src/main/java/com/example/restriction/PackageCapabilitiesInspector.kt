package com.example.restriction

import android.Manifest
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.example.service.VaultDeviceAdminReceiver

/**
 * Inspects live Android package capabilities, runtime grants, AppOps states, Device Owner
 * suspension support, and discovered notification/call channels universally for any packageName.
 */
class PackageCapabilitiesInspector(private val context: Context) {

    fun inspectRawCapabilities(packageName: String, fallbackAppName: String = packageName): RawPackageCapabilities {
        val pm = context.packageManager
        val pkgInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            }
        }.getOrNull()

        val appInfo = pkgInfo?.applicationInfo ?: runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
        }.getOrNull()

        val appName = appInfo?.let {
            runCatching { pm.getApplicationLabel(it).toString() }.getOrNull()
        }?.takeIf { it.isNotBlank() } ?: fallbackAppName

        val requested = pkgInfo?.requestedPermissions?.toList().orEmpty()
        val flags = pkgInfo?.requestedPermissionsFlags

        fun hasRequested(permission: String): Boolean = requested.contains(permission)
        fun isGrantedInPkgInfo(permission: String): Boolean? {
            val idx = requested.indexOf(permission)
            if (idx < 0 || flags == null || idx >= flags.size) return null
            return (flags[idx] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
        }

        val requestsPostNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasRequested(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            false
        }
        val postNotificationsGranted = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            requestsPostNotifications
        ) {
            isGrantedInPkgInfo(Manifest.permission.POST_NOTIFICATIONS)
                ?: (pm.checkPermission(Manifest.permission.POST_NOTIFICATIONS, packageName) == PackageManager.PERMISSION_GRANTED)
        } else {
            null
        }
        if (postNotificationsGranted == true) {
            UniversalChannelTracker.markPackageNotificationsBanned(packageName, false)
        }

        val requestsOverlay = hasRequested(Manifest.permission.SYSTEM_ALERT_WINDOW)
        val requestsFsi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hasRequested(Manifest.permission.USE_FULL_SCREEN_INTENT)
        } else {
            false
        }
        val requestsVibrate = hasRequested(Manifest.permission.VIBRATE)
        val requestsFgs = hasRequested(Manifest.permission.FOREGROUND_SERVICE)
        val requestsCall = hasRequested(Manifest.permission.CALL_PHONE) ||
            hasRequested(Manifest.permission.MANAGE_OWN_CALLS) ||
            hasRequested(Manifest.permission.READ_PHONE_STATE) ||
            hasRequested("android.permission.FOREGROUND_SERVICE_PHONE_CALL")

        val uid = appInfo?.uid
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager

        val overlayAllowed: Boolean? = if (requestsOverlay && uid != null && appOps != null) {
            checkAppOpState(appOps, AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, packageName)
        } else if (requestsOverlay) {
            null
        } else {
            false
        }

        val fullScreenIntentAllowed: Boolean? = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            requestsFsi && uid != null && appOps != null
        ) {
            checkAppOpState(appOps, "android:use_full_screen_intent", uid, packageName)
        } else if (requestsFsi) {
            if (postNotificationsGranted == false) false else null
        } else {
            false
        }

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val isDeviceOwner = dpm?.isDeviceOwnerApp(context.packageName) == true
        val adminComponent = ComponentName(context, VaultDeviceAdminReceiver::class.java)
        val isSuspended = if (isDeviceOwner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { dpm.isPackageSuspended(adminComponent, packageName) }.getOrDefault(false)
        } else {
            false
        }

        return RawPackageCapabilities(
            packageName = packageName,
            appName = appName,
            requestsPostNotifications = requestsPostNotifications,
            postNotificationsGranted = postNotificationsGranted,
            requestsSystemAlertWindow = requestsOverlay,
            overlayAllowed = overlayAllowed,
            requestsFullScreenIntent = requestsFsi,
            fullScreenIntentAllowed = fullScreenIntentAllowed,
            requestsVibrate = requestsVibrate,
            requestsForegroundService = requestsFgs,
            requestsCallCapabilities = requestsCall,
            isDeviceOwnerAvailable = isDeviceOwner,
            isSuspendedByDeviceOwner = isSuspended,
        )
    }

    private fun checkAppOpState(
        appOps: AppOpsManager,
        opStr: String,
        uid: Int,
        packageName: String,
    ): Boolean? {
        return try {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(opStr, uid, packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(opStr, uid, packageName)
            }
            when (mode) {
                AppOpsManager.MODE_ALLOWED -> true
                AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> false
                else -> null
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Throwable) {
            null
        }
    }
}
