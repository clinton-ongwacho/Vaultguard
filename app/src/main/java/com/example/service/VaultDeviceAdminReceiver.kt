package com.example.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class VaultDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "VaultGuard Uninstall Protection is active. Admin password authorization is required to deactivate protection or uninstall this app."
    }
}
