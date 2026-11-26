package com.example.launcher

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.entity.DisguiseIdentity
import com.example.service.VaultDeviceAdminReceiver

class LauncherDisguiseManager(private val context: Context) {

    companion object {
        fun resolveEffectiveDisguise(
            disguise: DisguiseIdentity,
            hideFromLauncher: Boolean,
        ): DisguiseIdentity {
            return if (hideFromLauncher &&
                disguise == DisguiseIdentity.DEFAULT &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                DisguiseIdentity.CALCULATOR
            } else {
                disguise
            }
        }

        fun iconResFor(identity: DisguiseIdentity): Int = when (identity) {
            DisguiseIdentity.DEFAULT -> R.mipmap.ic_launcher
            DisguiseIdentity.CALCULATOR -> R.drawable.ic_disguise_calculator
            DisguiseIdentity.NOTES -> R.drawable.ic_disguise_notes
            DisguiseIdentity.WEATHER -> R.drawable.ic_disguise_weather
            DisguiseIdentity.CLOCK -> R.drawable.ic_disguise_clock
        }

        fun resolveRecentsLabel(
            context: Context,
            disguise: DisguiseIdentity,
            hideFromLauncher: Boolean,
        ): String {
            val effective = resolveEffectiveDisguise(disguise, hideFromLauncher)
            return if (effective == DisguiseIdentity.DEFAULT) {
                context.getString(R.string.app_name)
            } else {
                effective.displayLabel
            }
        }

        fun applyRecentsIdentity(
            activity: Activity,
            disguise: DisguiseIdentity,
            hideFromLauncher: Boolean,
        ) {
            val effective = resolveEffectiveDisguise(disguise, hideFromLauncher)
            val label = resolveRecentsLabel(activity, disguise, hideFromLauncher)
            val iconRes = iconResFor(effective)

            runCatching {
                val taskDescription = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ActivityManager.TaskDescription.Builder()
                        .setLabel(label)
                        .setIcon(iconRes)
                        .build()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    @Suppress("DEPRECATION")
                    ActivityManager.TaskDescription(label, iconRes)
                } else {
                    val bitmap = renderDrawableBitmap(activity, iconRes)
                    @Suppress("DEPRECATION")
                    ActivityManager.TaskDescription(label, bitmap)
                }
                activity.setTaskDescription(taskDescription)
            }
        }

        private fun renderDrawableBitmap(context: Context, resId: Int): Bitmap? {
            val drawable = ContextCompat.getDrawable(context, resId) ?: return null
            val size = 128
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        }
    }

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
                    setComponentStateIfChanged(
                        pm = pm,
                        component = ComponentName(packageName, identity.aliasClassName),
                        desiredState = PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    )
                }
            } else {
                // On Android 10+, disabling all LAUNCHER aliases causes the OS to synthesize a fallback
                // icon labeled with <application> ("VaultGuard"). Keep a non-VaultGuard disguise alias
                // enabled instead so "VaultGuard" is never visible in the app drawer or Recents.
                val stealthMask = resolveEffectiveDisguise(disguise, hideFromLauncher = true)
                enableSingleAlias(pm, packageName, stealthMask)
            }
        } else {
            if (isDeviceOwner && dpm != null) {
                runCatching { dpm.setApplicationHidden(adminComponent, packageName, false) }
            }
            enableSingleAlias(pm, packageName, disguise)
        }
    }

    fun isAliasEnabled(identity: DisguiseIdentity): Boolean {
        val pm = context.packageManager
        val component = ComponentName(context.packageName, identity.aliasClassName)
        val state = runCatching { pm.getComponentEnabledSetting(component) }
            .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && identity == DisguiseIdentity.DEFAULT)
    }

    private fun enableSingleAlias(
        pm: PackageManager,
        packageName: String,
        target: DisguiseIdentity,
    ) {
        setComponentStateIfChanged(
            pm = pm,
            component = ComponentName(packageName, target.aliasClassName),
            desiredState = PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        )
        for (identity in DisguiseIdentity.entries) {
            if (identity != target) {
                setComponentStateIfChanged(
                    pm = pm,
                    component = ComponentName(packageName, identity.aliasClassName),
                    desiredState = PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                )
            }
        }
    }

    private fun setComponentStateIfChanged(
        pm: PackageManager,
        component: ComponentName,
        desiredState: Int,
    ) {
        runCatching {
            val current = pm.getComponentEnabledSetting(component)
            if (current != desiredState) {
                pm.setComponentEnabledSetting(
                    component,
                    desiredState,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}
