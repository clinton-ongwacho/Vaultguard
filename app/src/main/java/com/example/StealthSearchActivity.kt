package com.example

import android.app.SearchManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.example.data.AppGuardRuntimeCache
import com.example.data.entity.DisguiseIdentity
import com.example.launcher.LauncherDisguiseManager

class StealthSearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        val query = intent?.getStringExtra(SearchManager.QUERY).orEmpty().trim()
        val matched = query.isEmpty() ||
            AppGuardRuntimeCache.matchesWakeWord(query) ||
            AppGuardRuntimeCache.matchesDialerSecret(query)

        if (matched) {
            val disguise = LauncherDisguiseManager.resolveEffectiveDisguise(
                disguise = DisguiseIdentity.fromName(AppGuardRuntimeCache.config.disguiseName),
                hideFromLauncher = AppGuardRuntimeCache.config.isAppHidden,
            )
            val manager = LauncherDisguiseManager(this)
            val targetComponent = if (manager.isAliasEnabled(disguise)) {
                ComponentName(packageName, disguise.aliasClassName)
            } else {
                ComponentName(this, MainActivity::class.java)
            }
            val wakeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = targetComponent
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_STEALTH_WAKE, true)
                putExtra(MainActivity.EXTRA_WAKE_SOURCE, "System Search")
            }
            startActivity(wakeIntent)
        }
        finish()
    }
}
