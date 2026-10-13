package com.chai.earthwall;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Tap-to-update for friends who got the app as a file (no Play Store).
 * The newest build and a small version.json sit on the public earth-live repo's `dist` branch. Every few hours
 * (and whenever Settings opens) the app reads version.json; if it's newer than what's installed, it posts one
 * "Update ready" notification. Tapping it opens UpdateActivity, which downloads, checks the file's SHA-256 and
 * hands it to Android's installer. Android also checks it's signed with the same key as the installed app.
 * Only the golden app ("Earth") does this; Earth Next is updated from the laptop.
 */
final class Updater {
    static final String GOLDEN = "com.chai.earthwall";
    static final String FEED = "https://raw.githubusercontent.com/kckBytes/earth-live/dist/version.json";
    private static final String CH = "updates", TAG = "EarthUpdate";
    private static final long EVERY = 6 * 3_600_000L;

    static final class Info {
        long code; String name, apk, sha256, notes;
    }

    private Updater() {}

    static boolean enabled(Context c) { return GOLDEN.equals(c.getPackageName()); }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("updates", Context.MODE_PRIVATE); }

    static long installed(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode(); }
        catch (PackageManager.NameNotFoundException e) { return 0; }
    }

    static String installedName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException e) { return "?"; }
    }

    /** The newest release we know of, if it's newer than this install; else null. */
    static Info pending(Context c) {
        SharedPreferences p = prefs(c);
        if (!enabled(c) || p.getLong("code", 0) <= installed(c)) return null;
        Info i = new Info();
        i.code = p.getLong("code", 0); i.name = p.getString("name", ""); i.apk = p.getString("apk", "");
        i.sha256 = p.getString("sha256", ""); i.notes = p.getString("notes", "");
        return i;
    }

    /** Network: call off the main thread. */
    static Info fetch() throws Exception {
        JSONObject o = new JSONObject(new String(get(FEED + "?t=" + System.currentTimeMillis() / 60_000L, 64 * 1024), "UTF-8"));
        Info i = new Info();
        i.code = o.getLong("versionCode");
        i.name = o.optString("versionName", String.valueOf(i.code));
        i.apk = o.getString("apk");
        i.sha256 = o.optString("sha256", "");
        i.notes = o.optString("notes", "");
        return i;
    }

    /** Checks at most every 6 h (force: now). Remembers the result and notifies once per new version. */
    static void check(Context ctx, boolean force) {
        final Context c = ctx.getApplicationContext();
        if (!enabled(c)) return;
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        if (!force && now - p.getLong("checked", 0) < EVERY) return;
        p.edit().putLong("checked", now).apply();
        new Thread(() -> {
            try {
                Info i = fetch();
                p.edit().putLong("code", i.code).putString("name", i.name).putString("apk", i.apk)
                        .putString("sha256", i.sha256).putString("notes", i.notes).apply();
                Log.i(TAG, "feed: " + i.code + " (" + i.name + "), installed " + installed(c));
                if (i.code > installed(c) && p.getLong("notified", 0) < i.code) {
                    notifyReady(c, i);
                    p.edit().putLong("notified", i.code).apply();
                }
            } catch (Exception e) { Log.i(TAG, "check failed: " + e); }
        }, "earth-update-check").start();
    }

    private static void notifyReady(Context c, Info i) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return;                // Settings shows it instead
        NotificationChannel ch = new NotificationChannel(CH, "App updates", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("A newer version of Earth is ready to install");
        nm.createNotificationChannel(ch);
        PendingIntent pi = PendingIntent.getActivity(c, 0, new Intent(c, UpdateActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("Earth " + i.name + " is ready")
                .setContentText(i.notes.isEmpty() ? "Tap to update" : i.notes)
                .setStyle(new Notification.BigTextStyle().bigText((i.notes.isEmpty() ? "" : i.notes + "\n") + "Tap to update"))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(4401, n);
    }

    static byte[] get(String url, int max) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(15_000);
        h.setReadTimeout(30_000);
        h.setUseCaches(false);
        try (InputStream in = h.getInputStream()) {
            if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[16 * 1024];
            int n;
            while ((n = in.read(b)) > 0) { out.write(b, 0, n); if (out.size() > max) throw new Exception("too big"); }
            return out.toByteArray();
        } finally { h.disconnect(); }
    }
}
