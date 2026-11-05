package com.daglas.usblink;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Base64;
import com.getcapacitor.BridgeActivity;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.json.JSONObject;

public class MainActivity extends BridgeActivity {
    /** What another app shared to USB Link, waiting for the web screen to pick it up: {"text":..} or {"name":..,"data":base64} or {"error":..} */
    public static volatile String pendingShare = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(HandsFreePlugin.class);
        super.onCreate(savedInstanceState);
        grabShare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        grabShare(intent);
    }

    private void grabShare(Intent intent) {
        try {
            if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
            JSONObject o = new JSONObject();
            Uri uri = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) return;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                long total = 0;
                int n;
                boolean tooBig = false;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 8L * 1024 * 1024) { tooBig = true; break; }
                    out.write(buf, 0, n);
                }
                in.close();
                if (tooBig) {
                    o.put("error", "too big");
                } else {
                    String name = "shared";
                    Cursor c = getContentResolver().query(uri, null, null, null, null);
                    if (c != null) {
                        try {
                            if (c.moveToFirst()) {
                                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                                if (i >= 0 && c.getString(i) != null) name = c.getString(i);
                            }
                        } finally {
                            c.close();
                        }
                    }
                    o.put("name", name);
                    o.put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
                }
            } else {
                String t = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (t == null || t.isEmpty()) return;
                o.put("text", t);
            }
            pendingShare = o.toString();
        } catch (Exception ignored) {
        }
    }
}
