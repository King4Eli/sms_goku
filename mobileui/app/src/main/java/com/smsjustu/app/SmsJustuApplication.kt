package com.smsjustu.app

class SmsJustuApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                RestartScheduler.scheduleRestart(applicationContext)
            } catch (_: Exception) {
                // best-effort - don't let the restart hook mask the original crash
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        val settings = Settings(this)
        if (settings.pullEnabled && settings.adminToken.isNotBlank()) {
            androidx.core.content.ContextCompat.startForegroundService(
                this, android.content.Intent(this, SyncService::class.java)
            )
        }
    }
}
