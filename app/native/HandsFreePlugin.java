package com.daglas.usblink;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.JSONArray;

/** Lets the web screen start and stop the background hands-free service. */
@CapacitorPlugin(name = "HandsFree")
public class HandsFreePlugin extends Plugin {

    @PluginMethod
    public void start(PluginCall call) {
        String url = call.getString("url", "");
        String code = call.getString("code", "");
        String wake = call.getString("wake", "morales");
        boolean needWake = call.getBoolean("needWake", true);
        if (url == null || url.isEmpty() || code == null || code.length() < 8) {
            call.reject("Add the server address and pairing code in Settings first.");
            return;
        }
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            call.reject("mic-permission");
            return;
        }
        try {
            Intent i = new Intent(getContext(), HandsFreeService.class);
            i.putExtra("url", url);
            i.putExtra("code", code);
            i.putExtra("wake", wake);
            i.putExtra("lang", call.getString("lang", ""));
            i.putExtra("needWake", needWake);
            ContextCompat.startForegroundService(getContext(), i);
            call.resolve();
        } catch (Exception e) {
            call.reject("Couldn't start: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        try {
            getContext().stopService(new Intent(getContext(), HandsFreeService.class));
        } catch (Exception ignored) { }
        call.resolve();
    }

    /** Whatever another app shared to USB Link (handed over once). */
    @PluginMethod
    public void getShared(PluginCall call) {
        JSObject r = new JSObject();
        String s = MainActivity.pendingShare;
        MainActivity.pendingShare = null;
        r.put("share", s == null ? "" : s);
        call.resolve(r);
    }

    /** AI phone control: the web screen hands over the relay address, pairing code and the time the user allowed (0 = off). */
    @PluginMethod
    public void ctlConfig(PluginCall call) {
        String url = call.getString("url", "");
        String code = call.getString("code", "");
        Double u = call.getDouble("until", 0.0);
        long until = u == null ? 0L : u.longValue();
        SharedPreferences sp = getContext().getSharedPreferences(PhoneControlService.PREFS, Context.MODE_PRIVATE);
        sp.edit().putString("url", url == null ? "" : url).putString("code", code == null ? "" : code).putLong("until", until).apply();
        call.resolve();
    }

    /** Is the accessibility service switched on in Android settings, and how long is control allowed for. */
    @PluginMethod
    public void ctlState(PluginCall call) {
        SharedPreferences sp = getContext().getSharedPreferences(PhoneControlService.PREFS, Context.MODE_PRIVATE);
        JSObject r = new JSObject();
        r.put("service", PhoneControlService.instance != null);
        r.put("until", (double) sp.getLong("until", 0L));
        call.resolve(r);
    }

    @PluginMethod
    public void ctlSettings(PluginCall call) {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("Couldn't open the settings: " + e.getMessage());
        }
    }

    @PluginMethod
    public void isRunning(PluginCall call) {
        JSObject r = new JSObject();
        r.put("running", HandsFreeService.running);
        call.resolve(r);
    }

    /** Returns what was said and answered while the screen was away, then clears it. */
    @PluginMethod
    public void getLog(PluginCall call) {
        SharedPreferences sp = getContext().getSharedPreferences("hf", Context.MODE_PRIVATE);
        String raw = sp.getString("log", "[]");
        JSArray arr;
        try {
            arr = new JSArray(new JSONArray(raw).toString());
        } catch (Exception e) {
            arr = new JSArray();
        }
        sp.edit().putString("log", "[]").apply();
        JSObject r = new JSObject();
        r.put("log", arr);
        call.resolve(r);
    }
}
