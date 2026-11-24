package com.example.launcher

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.data.entity.DisguiseIdentity
import com.example.service.VaultDeviceAdminReceiver

class LauncherDisguiseManager(private val context: Context) {

    fun applyLauncherState(disguise: DisguiseIdentity, hideFromLauncher: Boolean) {
        val pm = context.packageManager
        val packageName = context.packageName
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val adminComponent = ComponentName(context, VaultDeviceAdminReceiver::class.java)
        val isDeviceOwner = dpm?.isDeviceOwnerApp(packageName) == true

        if (hideFromLauncher) {
            if (isDeviceOwner && dpm != null) {
                runCatching { dpm.setApplicationHidden(adminComponent, packageName, true) }
                return
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // On Android 9 and below, disabling all launcher aliases completely hides the icon
                for (identity in DisguiseIdentity.entries) {
                    runCatching {
                        pm.setComponentEnabledSetting(
                            ComponentName(packageName, identity.aliasClassName),
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP,
                        )
                    }
                }
            } else {
                // On Android 10+, disabling all LAUNCHER aliases causes the OS to synthesize a fallback
                // icon labeled with <application> ("VaultGuard"). Keep a non-VaultGuard disguise alias
                // enabled instead so "VaultGuard" is never visible in the app drawer.
                val stealthMask = if (disguise == DisguiseIdentity.DEFAULT) {
                    DisguiseIdentity.CALCULATOR
                } else {
                    disguise
                }
                enableSingleAlias(pm, packageName, stealthMask)
            }
        } else {
            if (isDeviceOwner && dpm != null) {
                runCatching { dpm.setApplicationHidden(adminComponent, packageName, false) }
            }
            enableSingleAlias(pm, packageName, disguise)
        }
    }

    private fun enableSingleAlias(
        pm: PackageManager,
        packageName: String,
        target: DisguiseIdentity,
    ) {
        runCatching {
            pm.setComponentEnabledSetting(
                ComponentName(packageName, target.aliasClassName),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        for (identity in DisguiseIdentity.entries) {
            if (identity != target) {
                runCatching {
                    pm.setComponentEnabledSetting(
                        ComponentName(packageName, identity.aliasClassName),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            }
        }
    }
}
