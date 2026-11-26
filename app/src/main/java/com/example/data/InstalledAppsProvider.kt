package com.example.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val isSystemApp: Boolean,
    val category: Int = ApplicationInfo.CATEGORY_UNDEFINED,
)

class InstalledAppsProvider(private val context: Context) {

    suspend fun loadLaunchableApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }

        val ownPackage = context.packageName
        val seen = HashSet<String>()
        val result = mutableListOf<InstalledAppInfo>()

        for (info in resolveInfos) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg == ownPackage || !seen.add(pkg)) continue

            val label = info.loadLabel(pm)?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: pkg
            val drawable = runCatching { info.loadIcon(pm) }.getOrNull()
            val appInfo = info.activityInfo?.applicationInfo
            val isSystem = appInfo != null &&
                ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) &&
                ((appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0)
            val category = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appInfo != null) {
                appInfo.category
            } else {
                ApplicationInfo.CATEGORY_UNDEFINED
            }

            result.add(
                InstalledAppInfo(
                    packageName = pkg,
                    appName = label,
                    icon = drawable,
                    isSystemApp = isSystem,
                    category = category,
                )
            )
        }

        result.sortedWith(compareBy<InstalledAppInfo> { it.isSystemApp }.thenBy { it.appName.lowercase() })
    }

    fun getAppInfoForPackage(packageName: String): InstalledAppInfo? {
        val pm = context.packageManager
        return try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            val label = pm.getApplicationLabel(appInfo).toString()
            val icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull()
            val category = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appInfo.category
            } else {
                ApplicationInfo.CATEGORY_UNDEFINED
            }
            InstalledAppInfo(
                packageName = packageName,
                appName = label,
                icon = icon,
                isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                category = category,
            )
        } catch (_: Exception) {
            null
        }
    }
}
