package com.example

import android.app.Application
import com.example.data.AppGuardRuntimeCache
import com.example.data.InstalledAppsProvider
import com.example.data.cloud.CloudSyncRepository
import com.example.data.db.VaultDatabase
import com.example.data.repository.VaultRepository
import com.example.launcher.LauncherDisguiseManager
import com.example.ota.OtaUpdateManager
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class VaultGuardApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: VaultDatabase by lazy { VaultDatabase.getInstance(this) }
    val repository: VaultRepository by lazy {
        VaultRepository(
            context = this,
            dao = database.vaultDao(),
            appsProvider = InstalledAppsProvider(this),
            launcherManager = LauncherDisguiseManager(this),
        )
    }

    val cloudSyncRepository: CloudSyncRepository by lazy {
        val db = FirebaseFirestore.getInstance(getString(R.string.firestore_database_id))
        CloudSyncRepository(db)
    }

    val otaUpdateManager: OtaUpdateManager by lazy {
        OtaUpdateManager(
            context = this,
            cloudSyncRepository = cloudSyncRepository,
        )
    }

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            runCatching { repository.syncRuntimeCache() }
        }
        appScope.launch {
            repository.configFlow.collectLatest { config ->
                AppGuardRuntimeCache.updateConfig(config)
            }
        }
        appScope.launch {
            repository.limitedAppsFlow.collectLatest { rules ->
                AppGuardRuntimeCache.updateLimitedApps(rules)
            }
        }
    }
}
