package com.example

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.example.data.AppGuardRuntimeCache

class StealthSearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val query = intent?.getStringExtra(SearchManager.QUERY).orEmpty().trim()
        val matched = query.isEmpty() ||
            AppGuardRuntimeCache.matchesWakeWord(query) ||
            AppGuardRuntimeCache.matchesDialerSecret(query)

        if (matched) {
            val wakeIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_STEALTH_WAKE, true)
                putExtra(MainActivity.EXTRA_WAKE_SOURCE, "System Search")
            }
            startActivity(wakeIntent)
        }
        finish()
    }
}
