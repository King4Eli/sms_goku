package com.smsjustu.app

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject

enum class EventType { CREATE, REVOKE, ERROR, SENT, UNDELIVERED }

data class LogEvent(
    val timestamp: Long,
    val type: EventType,
    val message: String,
    val workerId: Long? = null
)

data class SessionStats(val sent: Int, val undelivered: Int)

/**
 * Process-wide event log shared by the activity and the background service
 * (same process - no IPC needed). Persisted to SharedPreferences so it
 * survives the activity being killed/recreated; capped at MAX_EVENTS.
 *
 * Routine pulls are NOT logged as discrete events here - at one per sync
 * cycle they'd flood the log. Instead startedAt/lastPullCount track just
 * "when did this run begin" and "how many came back last time", both
 * reset by markStarted() whenever a fresh START happens (app launch or
 * service (re)start).
 */
object EventLog {
    private const val PREFS = "event_log"
    private const val KEY_EVENTS = "events"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_LAST_PULL_COUNT = "last_pull_count"
    private const val MAX_EVENTS = 200
    private val lock = Any()

    val state = mutableStateOf<List<LogEvent>>(emptyList())
    val startedAt = mutableStateOf<Long?>(null)
    val lastPullCount = mutableStateOf<Int?>(null)
    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            initialized = true
            val prefs = prefs(context)
            val raw = prefs.getString(KEY_EVENTS, null)
            if (raw != null) {
                state.value = runCatching { parse(raw) }.getOrDefault(emptyList())
            }
            startedAt.value = prefs.getLong(KEY_STARTED_AT, -1L).takeIf { it >= 0 }
            lastPullCount.value = prefs.getInt(KEY_LAST_PULL_COUNT, -1).takeIf { it >= 0 }
        }
    }

    /** Resets the started-at/last-pull-count markers for a fresh run (app launch or service (re)start). */
    fun markStarted(context: Context) {
        init(context)
        synchronized(lock) {
            val now = System.currentTimeMillis()
            startedAt.value = now
            lastPullCount.value = null
            prefs(context).edit()
                .putLong(KEY_STARTED_AT, now)
                .remove(KEY_LAST_PULL_COUNT)
                .apply()
        }
    }

    fun recordPull(context: Context, count: Int) {
        init(context)
        synchronized(lock) {
            lastPullCount.value = count
            prefs(context).edit().putInt(KEY_LAST_PULL_COUNT, count).apply()
        }
    }

    fun add(context: Context, type: EventType, message: String, workerId: Long? = null) {
        init(context)
        synchronized(lock) {
            val updated = (listOf(LogEvent(System.currentTimeMillis(), type, message, workerId)) + state.value)
                .take(MAX_EVENTS)
            state.value = updated
            persistEvents(context, updated)
        }
    }

    /** Events tied to one specific worker (create/revoke and any errors reported against it). */
    fun eventsFor(workerId: Long): List<LogEvent> =
        state.value.filter { it.workerId == workerId }

    fun clear(context: Context) {
        synchronized(lock) {
            state.value = emptyList()
            persistEvents(context, emptyList())
        }
    }

    /**
     * Counts since the most recent markStarted() call (app launch or
     * service (re)start) - whichever happened last. Reading state.value
     * and startedAt.value here makes this Compose-observable: callers
     * that read it during composition recompose automatically as new
     * events come in, and the moment markStarted() moves the boundary
     * forward, counts drop back to 0.
     */
    fun sessionStats(): SessionStats {
        val boundary = startedAt.value ?: 0L
        val session = state.value.filter { it.timestamp >= boundary }
        return SessionStats(
            sent = session.count { it.type == EventType.SENT },
            undelivered = session.count { it.type == EventType.UNDELIVERED }
        )
    }

    private fun parse(raw: String): List<LogEvent> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            LogEvent(
                o.getLong("t"),
                EventType.valueOf(o.getString("type")),
                o.getString("msg"),
                if (o.has("wid") && !o.isNull("wid")) o.getLong("wid") else null
            )
        }
    }

    private fun persistEvents(context: Context, events: List<LogEvent>) {
        val arr = JSONArray()
        events.forEach {
            arr.put(JSONObject().apply {
                put("t", it.timestamp)
                put("type", it.type.name)
                put("msg", it.message)
                if (it.workerId != null) put("wid", it.workerId)
            })
        }
        prefs(context).edit().putString(KEY_EVENTS, arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
