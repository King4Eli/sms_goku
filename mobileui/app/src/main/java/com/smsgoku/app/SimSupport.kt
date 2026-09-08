package com.smsgoku.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/** One selectable SIM. [subId] is what [smsManagerFor] and Settings.subId use;
 *  [Settings.DEFAULT_SUB_ID] is represented by the synthetic "Default SIM" row
 *  the UI adds, not by an entry here. */
data class SimOption(val subId: Int, val slotIndex: Int, val label: String)

fun hasReadPhoneState(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED

/** Active SIMs on the device, or an empty list if READ_PHONE_STATE isn't
 *  granted or the platform can't report them. Best-effort labels: slot number,
 *  carrier, and the SIM's own number when the carrier actually provisioned it
 *  (usually it didn't - hence the manual worker<->SIM binding). */
fun listSims(context: Context): List<SimOption> {
    if (!hasReadPhoneState(context)) return emptyList()
    val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
    val subs = try {
        @Suppress("MissingPermission")
        sm.activeSubscriptionInfoList
    } catch (_: SecurityException) {
        null
    } ?: return emptyList()

    return subs.sortedBy { it.simSlotIndex }.map { info ->
        val carrier = info.carrierName?.toString()?.takeIf { it.isNotBlank() }
        // getNumber() is deprecated and usually empty (carrier didn't provision
        // it) but harmless as a best-effort label hint when it's there.
        @Suppress("DEPRECATION")
        val number = info.number?.takeIf { it.isNotBlank() }
        val label = buildString {
            append("SIM ${info.simSlotIndex + 1}")
            if (carrier != null) append(" · $carrier")
            if (number != null) append(" · $number")
        }
        SimOption(info.subscriptionId, info.simSlotIndex, label)
    }
}

/** Human label for a stored `sub_id` (nullable): "Default SIM" for null, the
 *  matching device SIM's label when [sims] has it, else "SIM #<subId>". */
fun simLabelFor(subId: Int?, sims: List<SimOption>): String = when {
    subId == null || subId < 0 -> "Default SIM"
    else -> sims.find { it.subId == subId }?.label ?: "SIM #$subId"
}

/** [SmsManager] bound to [subId], or the default-SIM manager when [subId] is
 *  [Settings.DEFAULT_SUB_ID] / not usable on this API level. */
@Suppress("DEPRECATION")
fun smsManagerFor(context: Context, subId: Int): SmsManager {
    val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(SmsManager::class.java)
    } else {
        SmsManager.getDefault()
    }
    return if (subId >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        base.createForSubscriptionId(subId)
    } else {
        base
    }
}
