package com.smsgoku.gateway;

import android.Manifest;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.util.Base64;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * adb-driven SMS sender; blocks until the radio reports. Methods: ping, send (base64 JSON
 * {id,to,message}). Returns r="OK ..." | "ERR ...". Binder thread, so no broadcast deadlock.
 */
public class SmsProvider extends ContentProvider {

    private static final String TAG = "SmsGateway";
    private static final int SHELL_UID = 2000;

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        if (uid != SHELL_UID && uid != Process.ROOT_UID) {
            Log.w(TAG, "rejected call from uid " + uid);
            return result(false, "caller not allowed");
        }
        // act as this app, not the shell
        long ident = Binder.clearCallingIdentity();
        try {
            switch (method) {
                case "ping": return ping();
                case "send": return send(arg);
                default: return result(false, "unknown method " + method);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "call " + method + " failed", e);
            return result(false, "internal error: " + e);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private Bundle ping() {
        if (!hasSmsPermission()) return result(false, "SEND_SMS permission not granted");
        return result(true, "ready");
    }

    private Bundle send(String arg) {
        if (!hasSmsPermission()) return result(false, "SEND_SMS permission not granted");

        String id, to, message;
        try {
            JSONObject req = new JSONObject(new String(Base64.decode(arg, Base64.DEFAULT), StandardCharsets.UTF_8));
            id = req.optString("id", "");
            to = req.optString("to", "").trim();
            message = req.optString("message", "");
        } catch (IllegalArgumentException | JSONException | NullPointerException e) {
            return result(false, "arg must be base64 JSON {id,to,message}");
        }
        if (to.isEmpty() || message.isEmpty()) return result(false, "'to' and 'message' are required");

        SmsSender.Result r = new SmsSender(getContext()).send(to, message);
        if (r.ok) {
            Log.i(TAG, "sent #" + id + " to " + to + " (" + r.parts + " parts)");
            return result(true, "parts=" + r.parts);
        }
        Log.w(TAG, "FAILED #" + id + " to " + to + ": " + r.error);
        return result(false, r.error);
    }

    private boolean hasSmsPermission() {
        return getContext().checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    /** One key, one line: easy to parse. */
    private static Bundle result(boolean ok, String detail) {
        Bundle b = new Bundle();
        String d = detail == null ? "unknown error" : detail.replace('\n', ' ');
        b.putString("r", (ok ? "OK " : "ERR ") + d);
        return b;
    }

    // ---------- unused ----------

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
