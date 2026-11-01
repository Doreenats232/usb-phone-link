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
