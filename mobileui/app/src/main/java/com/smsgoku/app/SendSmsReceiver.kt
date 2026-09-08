package com.smsgoku.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * adb-triggerable one-shot SMS send. Independent of the app's normal
 * pull/report sync loop (SyncService) - it just sends whatever it's handed,
 * so a host script can drive the device over adb (see _script/main.py).
 *
 *   adb shell "am broadcast -f 0x00000020 \
 *     -n com.smsgoku.app/.SendSmsReceiver \
 *     --es number '16505551234' \
 *     --es message 'hello from adb'"
 *
 * Note the OUTER double quotes: adb runs a second shell on the device, so the
 * message must stay quoted for that shell or it splits on spaces.
 *
 * The SIM is not passed here - it's whatever "SIM to send from" is set to in
 * the app's Settings (Settings.subId), the single place the SIM is chosen.
 *
 * One-time permission grant after install:
 *   adb shell pm grant com.smsgoku.app android.permission.SEND_SMS
 *
 * The receiver is exported but gated by android:permission SEND_SMS in the
 * manifest, so only callers holding SEND_SMS (the adb `shell` user, system)
 * can trigger it.
 */
class SendSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val number = intent.getStringExtra("number")
        val message = intent.getStringExtra("message")

        if (number.isNullOrBlank() || message.isNullOrBlank()) {
            report(Activity.RESULT_CANCELED, "error: missing 'number' or 'message' extra")
            return
        }

        val subId = Settings(context).subId
        try {
            val sms = smsManagerFor(context, subId)
            val parts = sms.divideMessage(message)
            if (parts.size == 1) {
                sms.sendTextMessage(number, null, message, null, null)
            } else {
                sms.sendMultipartTextMessage(number, null, parts, null, null)
            }
            Log.i(TAG, "sent ${parts.size} part(s) to $number (subId=$subId)")
            report(Activity.RESULT_OK, "sent ${parts.size} part(s)")
        } catch (e: Exception) {
            Log.e(TAG, "send failed", e)
            report(Activity.RESULT_CANCELED, "error: ${e.message}")
        }
    }

    private fun report(code: Int, data: String) {
        // Works when the broadcast is ordered (am broadcast prints "result=.. data=..").
        if (isOrderedBroadcast) {
            resultCode = code
            resultData = data
        }
    }

    companion object {
        private const val TAG = "smsGoku"
    }
}
