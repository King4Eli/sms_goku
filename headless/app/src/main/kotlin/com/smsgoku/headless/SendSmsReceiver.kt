package com.smsgoku.headless

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import android.util.Log

/**
 * Sends an SMS when triggered by a broadcast. Nothing else.
 *
 *   adb shell "am broadcast -f 0x00000020 \
 *     -n com.smsgoku.headless/.SendSmsReceiver \
 *     --es number '16505551234' \
 *     --es message 'hello from adb'"
 *
 * Note the OUTER double quotes: adb runs a second shell on the device, so the
 * message must stay quoted for that shell or it splits on spaces.
 *
 * Optional: --ei subId <n>   (SIM subscription id for dual-SIM devices)
 *
 * One-time permission grant after install:
 *   adb shell pm grant com.smsgoku.headless android.permission.SEND_SMS
 */
class SendSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val number = intent.getStringExtra("number")
        val message = intent.getStringExtra("message")
        val subId = intent.getIntExtra("subId", -1)

        if (number.isNullOrBlank() || message.isNullOrBlank()) {
            report(Activity.RESULT_CANCELED, "error: missing 'number' or 'message' extra")
            return
        }

        try {
            val sms = resolveSmsManager(context, subId)
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

    @Suppress("DEPRECATION")
    private fun resolveSmsManager(context: Context, subId: Int): SmsManager {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        return if (subId >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            base.createForSubscriptionId(subId) else base
    }

    companion object {
        private const val TAG = "headless"
    }
}
