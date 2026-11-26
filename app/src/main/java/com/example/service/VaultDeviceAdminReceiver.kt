package com.example.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.example.data.AppGuardRuntimeCache

class VaultDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        AppGuardRuntimeCache.bumpRestrictionStateVersion()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        AppGuardRuntimeCache.bumpRestrictionStateVersion()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        AppGuardRuntimeCache.bumpRestrictionStateVersion()
        return "VaultGuard Uninstall Protection is active. Admin password authorization is required to deactivate protection or uninstall this app."
    }
}
