package com.smsjustu.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class StartServiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = Settings(context)
        if (!settings.backgroundSyncEnabled || !settings.pullEnabled) return

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            RestartScheduler.ACTION_RESTART_SYNC -> {
                ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java))
            }
        }
    }
}
