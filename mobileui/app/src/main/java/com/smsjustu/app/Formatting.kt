package com.smsjustu.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Human-readable date/time formatting, always in the device's local time zone. */
object Formatting {
    private val isoUtcParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // e.g. "Aug 17, 2026, 9:15:03 PM PDT"
    private fun displayFormatter() =
        SimpleDateFormat("MMM d, yyyy, h:mm:ss a zzz", Locale.getDefault())

    /** Formats a server-provided UTC ISO-8601 timestamp (e.g. "2026-08-17T04:15:09.000Z"). */
    fun humanDate(isoUtc: String?): String {
        if (isoUtc.isNullOrBlank()) return "—"
        val parsed = try {
            isoUtcParser.parse(isoUtc)
        } catch (e: Exception) {
            null
        } ?: return isoUtc
        return displayFormatter().format(parsed)
    }

    /** Formats a local epoch-millis timestamp (e.g. System.currentTimeMillis()). */
    fun humanDate(epochMillis: Long): String =
        displayFormatter().format(Date(epochMillis))
}
