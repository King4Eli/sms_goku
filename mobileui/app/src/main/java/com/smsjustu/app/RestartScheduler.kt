package com.smsjustu.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

object RestartScheduler {
    const val ACTION_RESTART_SYNC = "com.smsjustu.app.action.RESTART_SYNC"

    fun scheduleRestart(context: Context, delayMs: Long = 2_000L) {
        val settings = Settings(context)
        if (!settings.backgroundSyncEnabled || !settings.pullEnabled) return

        val intent = Intent(context, StartServiceReceiver::class.java).apply {
            action = ACTION_RESTART_SYNC
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMs,
            pendingIntent
        )
    }
}
