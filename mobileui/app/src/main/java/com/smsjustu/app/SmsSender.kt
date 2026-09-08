package com.smsjustu.app

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicInteger

/** Sends SMS on the device's default SIM - see multi-SIM discussion in
 *  worker-mobile.md for why picking a specific SIM isn't done (yet). */
object SmsSender {
    private const val ACTION_SMS_SENT = "com.smsjustu.app.action.SMS_SENT"

    /** Sends [message] to [to], splitting into multiple parts if it exceeds a
     *  single SMS segment. Returns null on success, or a description of the
     *  first part that failed. [id] only needs to be unique among sends
     *  in flight at once - it seeds the broadcast/PendingIntent identity. */
    suspend fun send(context: Context, id: Long, to: String, message: String): String? =
        suspendCancellableCoroutine { cont ->
            val appContext = context.applicationContext
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                appContext.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val parts = smsManager.divideMessage(message)
            val remaining = AtomicInteger(parts.size)
            var firstError: String? = null
            val action = "$ACTION_SMS_SENT.$id"

            lateinit var receiver: BroadcastReceiver
            receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (resultCode != Activity.RESULT_OK && firstError == null) {
                        firstError = describeResult(resultCode)
                    }
                    if (remaining.decrementAndGet() == 0) {
                        runCatching { appContext.unregisterReceiver(receiver) }
                        if (cont.isActive) cont.resumeWith(Result.success(firstError))
                    }
                }
            }

            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(receiver, filter)
            }
            cont.invokeOnCancellation { runCatching { appContext.unregisterReceiver(receiver) } }

            val sentIntents = ArrayList<PendingIntent>(parts.size)
            for (i in parts.indices) {
                val requestCode = (id % 100_000).toInt() * 32 + i
                sentIntents.add(
                    PendingIntent.getBroadcast(
                        appContext, requestCode,
                        Intent(action).setPackage(appContext.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }

            try {
                smsManager.sendMultipartTextMessage(to, null, parts, sentIntents, null)
            } catch (e: Exception) {
                runCatching { appContext.unregisterReceiver(receiver) }
                if (cont.isActive) cont.resumeWith(Result.success("Send failed: ${e.message}"))
            }
        }

    private fun describeResult(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
        SmsManager.RESULT_ERROR_NULL_PDU -> "null PDU"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "limit exceeded"
        SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "fixed dialing number check failed"
        else -> "error code $code"
    }
}
