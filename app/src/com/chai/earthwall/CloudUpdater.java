package com.chai.earthwall;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Global cloud map (EUMETSAT data composited by clouds.matteason.co.uk; a new map every 3 h).
 * Keeps the previous map so the motion between the last two maps can be estimated: between updates
 * the clouds keep drifting along that motion, and a new map crossfades in as a correction.
 * Checks every 30 min while the wallpaper is visible; an unchanged map costs one ~1 KB request (ETag).
 */
final class CloudUpdater {
    private CloudUpdater() {}

    static final class CloudFrame {
        final Images.Single img;
        final ByteBuffer flow;       // FlowEstimator RG8 grid
        final long timeMs;           // when the map was produced (advection starts here)
        CloudFrame(Images.Single img, ByteBuffer flow, long timeMs) { this.img = img; this.flow = flow; this.timeMs = timeMs; }
    }

    interface Sink { void onClouds(CloudFrame frame); }

    private static final String TAG = "EarthWall";
    private static final String URL_FMT = "https://clouds.matteason.co.uk/images/%s/clouds.jpg";
    static final long CHECK_INTERVAL = 30 * 60_000L;
    private static final long METERED_INTERVAL = 3 * 3600_000L;

    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final CopyOnWriteArraySet<Sink> sinks = new CopyOnWriteArraySet<>();
    private static volatile boolean busy;

    static void register(Sink s) { sinks.add(s); }
    static void unregister(Sink s) { sinks.remove(s); }

    private static File cur(Context c) { return new File(c.getFilesDir(), "clouds.jpg"); }
    private static File prev(Context c) { return new File(c.getFilesDir(), "clouds_prev.jpg"); }

    /** Latest map with its motion field (cache, else the bundled snapshot). Blocking; off the GL thread. */
    static CloudFrame loadInitial(Context c) throws Exception {
        SharedPreferences p = Prefs.get(c);
        File f = cur(c);
        if (f.length() > 10_000) {
            try {
                Images.Single now = Images.decodeSingle(new FileInputStream(f));
                long t = p.getLong(Prefs.CLOUDS_TIME, System.currentTimeMillis());
                ByteBuffer flow = FlowEstimator.still();
                File pf = prev(c);
                long pt = p.getLong(Prefs.CLOUDS_PREV_TIME, 0);
                if (pf.length() > 10_000 && pt > 0) {
                    Images.Single before = Images.decodeSingle(new FileInputStream(pf));
                    flow = FlowEstimator.estimate(before.data, before.w, before.h, now.data, now.w, now.h,
                            (t - pt) / 3_600_000.0);
                }
                Log.i(TAG, "clouds from cache: map age " + String.format(java.util.Locale.US, "%.1f", (System.currentTimeMillis() - t) / 3_600_000.0)
                        + " h, previous map " + (pt > 0 ? String.format(java.util.Locale.US, "%.1f h", (t - pt) / 3_600_000.0) + " earlier" : "none")
                        + " (" + pf.length() + " B), motion " + FlowEstimator.stats(flow));
                return new CloudFrame(now, flow, t);
            } catch (Exception e) { Log.w(TAG, "cached clouds unreadable", e); }
        }
        Images.Single s = Images.decodeSingle(c.getAssets().open("clouds.jpg"));
        return new CloudFrame(s, FlowEstimator.still(), System.currentTimeMillis());
    }

    static void maybeUpdate(Context ctx, boolean force) {
        final Context c = ctx.getApplicationContext();
        final SharedPreferences p = Prefs.get(c);
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        Network net = cm.getActiveNetwork();
        if (net == null) return;
        final boolean metered = cm.isActiveNetworkMetered();
        long now = System.currentTimeMillis();
        long interval = metered ? METERED_INTERVAL : CHECK_INTERVAL;
        if (!force && now - p.getLong(Prefs.CLOUDS_CHECKED, 0) < interval) return;
        if (busy) return;
        busy = true;
        io.execute(() -> {
            try { fetch(c, p, metered, force); }
            catch (Exception e) { Log.w(TAG, "cloud update failed", e); }
            finally { busy = false; }
        });
    }

    /** Mean absolute difference in grey levels, sampled on a 512x256 grid (works across resolutions). */
    static float meanAbsDiff(Images.Single a, Images.Single b) {
        long sum = 0; int n = 0;
        for (int y = 0; y < 256; y++) for (int x = 0; x < 512; x++) {
            int va = a.data.get((y * a.h / 256) * a.w + x * a.w / 512) & 0xFF;
            int vb = b.data.get((y * b.h / 256) * b.w + x * b.w / 512) & 0xFF;
            sum += Math.abs(va - vb); n++;
        }
        return sum / (float) n;
    }

    private static void fetch(Context c, SharedPreferences p, boolean metered, boolean force) throws Exception {
        String res = (metered && p.getBoolean(Prefs.HD_WIFI_ONLY, true)) ? "2048x1024" : "4096x2048";
        HttpURLConnection conn = (HttpURLConnection) new URL(String.format(URL_FMT, res)).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent", "EarthWallpaper/1.1 (Android live wallpaper)");
        String etag = p.getString(Prefs.CLOUDS_ETAG, null);
        File curFile = cur(c);
        if (!force && etag != null && res.equals(p.getString(Prefs.CLOUDS_RES, "")) && curFile.exists()) {
            conn.setRequestProperty("If-None-Match", etag);
        }
        int code = conn.getResponseCode();
        long now = System.currentTimeMillis();
        if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
            p.edit().putLong(Prefs.CLOUDS_CHECKED, now).apply();
            conn.disconnect();
            return;
        }
        if (code != HttpURLConnection.HTTP_OK) { conn.disconnect(); throw new IllegalStateException("HTTP " + code); }
        File tmp = new File(c.getFilesDir(), "clouds.tmp");
        try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        long modified = conn.getLastModified();
        String newTag = conn.getHeaderField("ETag");
        conn.disconnect();

        long newTime = modified > 0 ? Math.min(modified, now) : now;    // server sends no Last-Modified: download time
        long oldTime = p.getLong(Prefs.CLOUDS_TIME, 0);
        Images.Single fresh = Images.decodeSingle(new FileInputStream(tmp));   // validate before replacing
        Images.Single before = (curFile.length() > 10_000 && oldTime > 0)
                ? Images.decodeSingle(new FileInputStream(curFile)) : null;
        // A new ETag doesn't always mean new weather (other resolution, CDN re-encode). Compare pixels:
        // re-encodes differ by ~1 level, three hours of real weather by far more.
        float diff = before != null ? meanAbsDiff(before, fresh) : 255f;
        Log.i(TAG, "cloud map " + res + " differs from the current one by " + String.format(java.util.Locale.US, "%.2f", diff) + " levels");
        File pf = prev(c);
        long prevTime = p.getLong(Prefs.CLOUDS_PREV_TIME, 0);
        if (before != null && diff < 3f) {                                    // same weather
            boolean resChanged = !res.equals(p.getString(Prefs.CLOUDS_RES, ""));
            if (resChanged) { curFile.delete(); if (!tmp.renameTo(curFile)) throw new IllegalStateException("rename failed"); }
            else tmp.delete();
            p.edit().putLong(Prefs.CLOUDS_CHECKED, now).putString(Prefs.CLOUDS_ETAG, newTag).putString(Prefs.CLOUDS_RES, res).apply();
            if (resChanged) {
                ByteBuffer flow = FlowEstimator.still();
                if (pf.length() > 10_000 && prevTime > 0) {
                    Images.Single pb = Images.decodeSingle(new FileInputStream(pf));
                    flow = FlowEstimator.estimate(pb.data, pb.w, pb.h, fresh.data, fresh.w, fresh.h, (oldTime - prevTime) / 3_600_000.0);
                }
                CloudFrame f = new CloudFrame(fresh, flow, oldTime);
                for (Sink s : sinks) s.onClouds(f);
            }
            return;
        }

        ByteBuffer flow = FlowEstimator.still();
        if (before != null) {
            double hours = (newTime - oldTime) / 3_600_000.0;
            boolean usePrev = hours < 1.0 && pf.length() > 10_000 && prevTime > 0 && newTime - prevTime < 9 * 3_600_000L;
            Images.Single from = before; long fromTime = oldTime;
            if (usePrev) { from = Images.decodeSingle(new FileInputStream(pf)); fromTime = prevTime; }   // too close: keep the older baseline
            long t0 = System.nanoTime();
            double h = (newTime - fromTime) / 3_600_000.0;
            flow = FlowEstimator.estimate(from.data, from.w, from.h, fresh.data, fresh.w, fresh.h, h);
            Log.i(TAG, "cloud motion estimated in " + (System.nanoTime() - t0) / 1_000_000 + " ms over "
                    + String.format(java.util.Locale.US, "%.1f h: ", h) + FlowEstimator.stats(flow));
            if (!usePrev) {
                pf.delete();
                if (!curFile.renameTo(pf)) Log.w(TAG, "could not keep previous map");
                prevTime = oldTime;
            }
        }
        if (!tmp.renameTo(curFile)) {
            curFile.delete();
            if (!tmp.renameTo(curFile)) throw new IllegalStateException("rename failed");
        }
        SharedPreferences.Editor e = p.edit()
                .putLong(Prefs.CLOUDS_CHECKED, now)
                .putLong(Prefs.CLOUDS_TIME, newTime)
                .putString(Prefs.CLOUDS_ETAG, newTag)
                .putString(Prefs.CLOUDS_RES, res);
        if (prevTime > 0) e.putLong(Prefs.CLOUDS_PREV_TIME, prevTime);
        e.apply();
        Log.i(TAG, "clouds updated " + res + " map time " + newTime);
        CloudFrame f = new CloudFrame(fresh, flow, newTime);
        for (Sink s : sinks) s.onClouds(f);
    }
}
