package com.smsjustu.app

import android.content.Context

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("admin_settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    var adminToken: String
        get() = prefs.getString(KEY_ADMIN_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ADMIN_TOKEN, value).apply()

    var backgroundSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_SYNC, false)
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND_SYNC, value).apply()

    var pullEnabled: Boolean
        get() = prefs.getBoolean(KEY_PULL_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_PULL_ENABLED, value).apply()

    /** The worker id this device sends as, or null if not configured. This
     *  device's own SIM must actually be that worker's phone number - there's
     *  no way to fake the sender on a real SMS, so this is a manual binding. */
    var workerId: Long?
        get() = prefs.getLong(KEY_WORKER_ID, -1L).takeIf { it > 0 }
        set(value) {
            if (value == null) prefs.edit().remove(KEY_WORKER_ID).apply()
            else prefs.edit().putLong(KEY_WORKER_ID, value).apply()
        }

    companion object {
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_ADMIN_TOKEN = "admin_token"
        private const val KEY_BACKGROUND_SYNC = "background_sync_enabled"
        private const val KEY_PULL_ENABLED = "pull_enabled"
        private const val KEY_WORKER_ID = "worker_id"
        const val DEFAULT_BASE_URL = "https://sms-gateway.q1-site.site"
    }
}
