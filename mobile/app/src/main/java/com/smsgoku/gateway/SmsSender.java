package com.smsgoku.gateway;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.telephony.SmsManager;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Send SMS, block until every part reports. */
public class SmsSender {

    public static class Result {
        public final boolean ok;
        public final int parts;
        public final String error;

        Result(boolean ok, int parts, String error) {
            this.ok = ok;
            this.parts = parts;
            this.error = error;
        }
    }

    private static final String ACTION_SENT = "com.smsgoku.gateway.SMS_SENT";
    private static final long SEND_TIMEOUT_SEC = 30; // < 60s broadcast limit
    private static final AtomicInteger requestCodes = new AtomicInteger(1);

    private final Context context;

    public SmsSender(Context context) {
        this.context = context.getApplicationContext();
    }

    @SuppressWarnings("deprecation")
    private SmsManager smsManager() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.getSystemService(SmsManager.class);
        }
        return SmsManager.getDefault();
    }

    public Result send(String to, String message) {
        SmsManager sms = smsManager();
        ArrayList<String> parts = sms.divideMessage(message);
        int n = parts.size();

        // unique action per send
        String action = ACTION_SENT + "." + requestCodes.getAndIncrement();
        CountDownLatch latch = new CountDownLatch(n);
        AtomicReference<String> firstError = new AtomicReference<>();

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                int code = getResultCode();
                if (code != Activity.RESULT_OK) firstError.compareAndSet(null, describe(code));
                latch.countDown();
            }
        };
        IntentFilter filter = new IntentFilter(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }

        try {
            ArrayList<PendingIntent> sentIntents = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                Intent intent = new Intent(action).setPackage(context.getPackageName());
                sentIntents.add(PendingIntent.getBroadcast(context, requestCodes.getAndIncrement(), intent,
                        PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE));
            }
            if (n == 1) {
                sms.sendTextMessage(to, null, message, sentIntents.get(0), null);
            } else {
                sms.sendMultipartTextMessage(to, null, parts, sentIntents, null);
            }
            if (!latch.await(SEND_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                return new Result(false, n, "timed out waiting for radio");
            }
            String err = firstError.get();
            return new Result(err == null, n, err);
        } catch (SecurityException e) {
            return new Result(false, n, "SEND_SMS permission not granted");
        } catch (IllegalArgumentException e) {
            return new Result(false, n, "invalid destination or message: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, n, "interrupted");
        } finally {
            context.unregisterReceiver(receiver);
        }
    }

    private static String describe(int code) {
        switch (code) {
            case SmsManager.RESULT_ERROR_GENERIC_FAILURE: return "generic failure";
            case SmsManager.RESULT_ERROR_NO_SERVICE: return "no service";
            case SmsManager.RESULT_ERROR_NULL_PDU: return "null PDU";
            case SmsManager.RESULT_ERROR_RADIO_OFF: return "radio off";
            case SmsManager.RESULT_ERROR_LIMIT_EXCEEDED: return "sending limit exceeded";
            case SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED: return "short code not allowed";
            case SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED: return "short code never allowed";
            default: return "error code " + code;
        }
    }
}
