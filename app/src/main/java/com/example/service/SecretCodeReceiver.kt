package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.MainActivity

class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == "android.provider.Telephony.SECRET_CODE") {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(MainActivity.EXTRA_STEALTH_WAKE, true)
                putExtra(MainActivity.EXTRA_WAKE_SOURCE, "Phone Dialer")
            }
            context.startActivity(launchIntent)
        }
    }
}
