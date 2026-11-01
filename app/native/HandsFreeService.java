package com.daglas.usblink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.os.Bundle;
import androidx.core.app.NotificationCompat;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Background hands-free mode: a foreground service (with a notification) that keeps listening, sends what you say to the USB Desktop AI
 * through the relay, and speaks the answer, even when the app is closed or the screen is off.
 */
public class HandsFreeService extends Service {
    static volatile boolean running = false;
    static final String CHANNEL = "usblink_handsfree";
    static final int NOTE_ID = 4711;
    static final long IDLE_STOP_MS = 30L * 60L * 1000L;

    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private Handler main;
    private SpeechRecognizer rec;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private AudioManager am;
    private PowerManager.WakeLock wl;
    private String url = "", code = "", wake = "morales";
    private boolean needWake = true, stopped = false;
    private long lastCmd = 0, followUntil = 0;
    private int errStreak = 0;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        url = intent.getStringExtra("url") == null ? "" : intent.getStringExtra("url");
        code = intent.getStringExtra("code") == null ? "" : intent.getStringExtra("code");
        String w = intent.getStringExtra("wake");
        wake = (w == null || w.trim().isEmpty()) ? "morales" : w.trim();
        needWake = intent.getBooleanExtra("needWake", true);
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);

        startForegroundNow("Listening… say \"" + wake + "\" first");
        running = true; stopped = false; lastCmd = System.currentTimeMillis();
        main = new Handler(Looper.getMainLooper());
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "usblink:handsfree");
            wl.acquire(4L * 60L * 60L * 1000L);
        } catch (Exception ignored) { }
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                try { tts.setLanguage(Locale.US); } catch (Exception ignored) { }
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { main.post(() -> listen(300)); }
                    @Override public void onError(String id) { main.post(() -> listen(300)); }
                });
            }
            main.post(() -> listen(200));
        });
        return START_NOT_STICKY;
    }

    // ---------- notification ----------
    private Notification buildNote(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Hands-free", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        int imm = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        Intent stop = new Intent(this, HandsFreeService.class); stop.setAction("STOP");
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, imm | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent openPi = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), imm | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("USB Link hands-free")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(0, "Stop", stopPi)
                .build();
    }

    private void startForegroundNow(String text) {
        Notification n = buildNote(text);
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(NOTE_ID, n);
    }

    private void setNote(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTE_ID, buildNote(text));
        } catch (Exception ignored) { }
    }

    // ---------- listening ----------
    private void listen(long delayMs) {
        if (stopped) return;
        main.postDelayed(this::listenNow, delayMs);
    }

    private void listenNow() {
        if (stopped) return;
        if (System.currentTimeMillis() - lastCmd > IDLE_STOP_MS) { fail("Stopped: nothing heard for 30 minutes."); return; }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { fail("Speech recognition isn't available on this phone."); return; }
        try {
            if (rec != null) { try { rec.destroy(); } catch (Exception ignored) { } }
            rec = SpeechRecognizer.createSpeechRecognizer(this);
            rec.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onPartialResults(Bundle partialResults) { }
                @Override public void onEvent(int eventType, Bundle params) { }
                @Override public void onError(int error) { handleError(error); }
                @Override public void onResults(Bundle results) {
                    errStreak = 0; muteBeeps(false);
                    ArrayList<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    handleHeard(r != null && !r.isEmpty() ? r.get(0) : "");
                }
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            muteBeeps(true);
            rec.startListening(i);
        } catch (Exception e) {
            errStreak++;
            if (errStreak > 8) fail("Couldn't start listening: " + e.getMessage()); else listen(1500);
        }
    }

    private void handleError(int error) {
        muteBeeps(false);
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) { listen(250); return; }
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { fail("Microphone permission is missing. Open USB Link and allow the microphone."); return; }
        errStreak++;
        if (errStreak > 10) { fail("Listening keeps failing. Stopped."); return; }
        listen(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1500 : 1000);
    }

    private void handleHeard(String heard) {
        String t = heard == null ? "" : heard.trim();
        if (t.isEmpty()) { listen(250); return; }
        long now = System.currentTimeMillis();
        if (needWake && now > followUntil) {
            String low = t.toLowerCase(Locale.ROOT), w = wake.toLowerCase(Locale.ROOT);
            int idx = low.indexOf(w);
            if (idx < 0 || idx > 12) { listen(250); return; }
            t = t.substring(idx + w.length()).replaceFirst("^[\\s,.:!?]+", "").trim();
            if (t.isEmpty()) { followUntil = now + 8000; lastCmd = now; speak("Yes?"); return; }
        }
        lastCmd = now;
        final String cmd = t;
        log("me", cmd);
        setNote("Thinking…");
        net.execute(() -> {
            final String reply = ask(cmd);
            main.post(() -> {
                if (stopped) return;
                log("ai", reply);
                setNote("Listening… say \"" + wake + "\" first");
                followUntil = System.currentTimeMillis() + 8000;
                speak(reply);
            });
        });
    }

    private void speak(String text) {
        if (stopped) return;
        if (!ttsReady) { listen(300); return; }
        String s = text == null ? "" : text.replaceAll("[\\p{So}\\p{Cn}]", "").trim();
        if (s.length() > 600) s = s.substring(0, 600);
        if (s.isEmpty()) { listen(300); return; }
        try { tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "u" + System.currentTimeMillis()); }
        catch (Exception e) { listen(300); }
    }

    // ---------- talking to the relay (runs off the main thread) ----------
    private String ask(String text) {
        try {
            JSONObject body = new JSONObject(); body.put("text", text);
            JSONObject sent = new JSONObject(post("/api/send", body.toString()));
            String id = sent.optString("id", "");
            if (id.isEmpty()) return sent.optString("error", "The server didn't accept that.");
            long end = System.currentTimeMillis() + 3 * 60 * 1000L;
            while (System.currentTimeMillis() < end && !stopped) {
                JSONObject r = new JSONObject(get("/api/result?id=" + URLEncoder.encode(id, "UTF-8") + "&seen="));
                if (r.optBoolean("done", false)) {
                    String reply = r.optString("text", "Done.");
                    return reply.isEmpty() ? "Done." : reply;
                }
                if (!r.optBoolean("usbOnline", true)) return "Your USB Desktop isn't connected.";
                JSONArray asks = r.optJSONArray("asks");
                if (asks != null && asks.length() > 0) return "The PC is waiting for your approval. Check the PC or the app.";
            }
            return "I didn't get an answer in time.";
        } catch (Exception e) {
            return "I couldn't reach your PC: " + e.getMessage();
        }
    }

    private String post(String path, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url + path).openConnection();
        c.setRequestMethod("POST"); c.setConnectTimeout(20000); c.setReadTimeout(70000); c.setDoOutput(true);
        c.setRequestProperty("x-pair", code); c.setRequestProperty("content-type", "application/json");
        try (OutputStream os = c.getOutputStream()) { os.write(json.getBytes("UTF-8")); }
        return readAll(c);
    }

    private String get(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url + path).openConnection();
        c.setRequestMethod("GET"); c.setConnectTimeout(20000); c.setReadTimeout(40000);
        c.setRequestProperty("x-pair", code);
        return readAll(c);
    }

    private String readAll(HttpURLConnection c) throws Exception {
        int status = c.getResponseCode();
        BufferedReader br = new BufferedReader(new InputStreamReader(status >= 400 ? c.getErrorStream() : c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close(); c.disconnect();
        return sb.toString();
    }

    // ---------- helpers ----------
    private void muteBeeps(boolean mute) {
        try {
            int d = mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, d, 0);
            am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, d, 0);
        } catch (Exception ignored) { }
    }

    private void log(String role, String text) {
        try {
            SharedPreferences sp = getSharedPreferences("hf", Context.MODE_PRIVATE);
            JSONArray a = new JSONArray(sp.getString("log", "[]"));
            JSONObject o = new JSONObject(); o.put("role", role); o.put("text", text);
            a.put(o);
            JSONArray trimmed = new JSONArray();
            for (int i = Math.max(0, a.length() - 40); i < a.length(); i++) trimmed.put(a.get(i));
            sp.edit().putString("log", trimmed.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void fail(String msg) {
        log("sys", msg);
        setNote(msg);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopped = true; running = false;
        try { if (rec != null) rec.destroy(); } catch (Exception ignored) { }
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Exception ignored) { }
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) { }
        try { muteBeeps(false); } catch (Exception ignored) { }
        net.shutdownNow();
        try { stopForeground(true); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
