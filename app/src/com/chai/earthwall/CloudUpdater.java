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
        final String kind;           // what the map holds: c clouds, bt infrared temperature, sst sea temperature
        CloudFrame(Images.Single img, ByteBuffer flow, long timeMs) { this(img, flow, timeMs, "c"); }
        CloudFrame(Images.Single img, ByteBuffer flow, long timeMs, String kind) { this.img = img; this.flow = flow; this.timeMs = timeMs; this.kind = kind; }
    }

    interface Sink { void onClouds(CloudFrame frame); }

    private static final String TAG = "EarthWall";
    private static final String URL_FMT = "https://clouds.matteason.co.uk/images/%s/clouds.jpg";
    /** Live source: five geostationary satellites stitched every 10 min by github.com/kckBytes/earth-live. */
    private static final String LIVE_BASE = "https://kckbytes.github.io/earth-live/";
    private static final long LIVE_INTERVAL = 10 * 60_000L, LIVE_METERED_INTERVAL = 30 * 60_000L;
    static final long CHECK_INTERVAL = 30 * 60_000L;
    private static final long METERED_INTERVAL = 3 * 3600_000L;

    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final CopyOnWriteArraySet<Sink> sinks = new CopyOnWriteArraySet<>();
    private static volatile boolean busy;

    static void register(Sink s) { sinks.add(s); }
    static void unregister(Sink s) { sinks.remove(s); }

    private static File cur(Context c) { return new File(c.getFilesDir(), "clouds.jpg"); }
    private static File prev(Context c) { return new File(c.getFilesDir(), "clouds_prev.jpg"); }
    private static File liveFlow(Context c) { return new File(c.getFilesDir(), "flow_live.bin"); }
    /** Infrared and sea-temperature views only exist on the live source. */
    static boolean live(SharedPreferences p) { return p.getBoolean(Prefs.LIVE_CLOUDS, true) || p.getInt(Prefs.VIEW, 0) == 1 || p.getInt(Prefs.VIEW, 0) == 2; }
    static String kind(SharedPreferences p) { return Prefs.kind(p.getInt(Prefs.VIEW, 0)); }
    static final String LIVE_URL = "https://kckbytes.github.io/earth-live/";

    /** Latest map with its motion field (cache, else the bundled snapshot). Blocking; off the GL thread. */
    static CloudFrame loadInitial(Context c) throws Exception {
        SharedPreferences p = Prefs.get(c);
        File f = cur(c);
        if (live(p) && ("live:" + kind(p)).equals(p.getString(Prefs.CLOUDS_SRC, "")) && f.length() > 10_000 && liveFlow(c).length() == FlowEstimator.GW * FlowEstimator.GH * 2) {
            try {
                Images.Single now = Images.decodeSingle(new FileInputStream(f));
                byte[] fb = java.nio.file.Files.readAllBytes(liveFlow(c).toPath());
                ByteBuffer flow = ByteBuffer.allocateDirect(fb.length).order(java.nio.ByteOrder.nativeOrder());
                flow.put(fb).position(0);
                long t = p.getLong(Prefs.CLOUDS_TIME, System.currentTimeMillis());
                Log.i(TAG, "live clouds from cache: observed " + String.format(java.util.Locale.US, "%.0f", (System.currentTimeMillis() - t) / 60_000.0)
                        + " min ago, motion " + FlowEstimator.stats(flow));
                return new CloudFrame(now, flow, t, kind(p));
            } catch (Exception e) { Log.w(TAG, "cached live clouds unreadable", e); }
        }
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
        long interval = live(p) ? (metered ? LIVE_METERED_INTERVAL : LIVE_INTERVAL) : (metered ? METERED_INTERVAL : CHECK_INTERVAL);
        if (!force && now - p.getLong(Prefs.CLOUDS_CHECKED, 0) < interval) return;
        if (busy) return;
        busy = true;
        io.execute(() -> {
            try { if (live(p)) fetchLive(c, p, metered, force); else fetch(c, p, metered, force); }
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

    static byte[] download(String url, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(timeoutMs);
        conn.setUseCaches(false);
        conn.setRequestProperty("Cache-Control", "no-cache");
        conn.setRequestProperty("User-Agent", "EarthWallpaper/1.2 (Android live wallpaper)");
        try {
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IllegalStateException("HTTP " + conn.getResponseCode() + " " + url);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[64 * 1024]; int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } finally { conn.disconnect(); }
    }

    /**
     * Live source: meta.json (~0.5 KB) says when the newest stitched map was observed. Only a newer map is
     * downloaded, together with its motion field measured on the server against the map ~1 h earlier.
     * The map's time is the real observation time, so the clouds drift forward from that moment to now.
     */
    private static void fetchLive(Context c, SharedPreferences p, boolean metered, boolean force) throws Exception {
        long now = System.currentTimeMillis();
        org.json.JSONObject meta = new org.json.JSONObject(new String(download(LIVE_BASE + "meta.json?t=" + now, 20_000), "UTF-8"));
        long obs = meta.getLong("time");
        String kind = kind(p);
        String res = kind.equals("sst") ? "2048" : (metered && p.getBoolean(Prefs.HD_WIFI_ONLY, true)) ? "2048" : "4096";
        boolean have = ("live:" + kind).equals(p.getString(Prefs.CLOUDS_SRC, "")) && cur(c).length() > 10_000
                && res.equals(p.getString(Prefs.CLOUDS_RES, ""));
        syncStorms(c, p, now);
        if (!force && have && obs <= p.getLong(Prefs.CLOUDS_TIME, 0)) {
            p.edit().putLong(Prefs.CLOUDS_CHECKED, now).apply();
            try { ReplayStore.sync(c, p, metered); } catch (Exception e) { Log.w(TAG, "replay sync failed", e); }
            return;
        }
        String file = kind.equals("c") ? "clouds_" + res + ".jpg" : kind.equals("bt") ? "bt_" + res + ".jpg" : "sst_2048.png";
        byte[] jpg = download(LIVE_BASE + file + "?t=" + obs, 90_000);
        byte[] png = download(LIVE_BASE + "flow.png?t=" + obs, 20_000);
        Images.Single fresh = Images.decodeSingle(new java.io.ByteArrayInputStream(jpg));     // validate first
        android.graphics.Bitmap fb = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
        int gw = FlowEstimator.GW, gh = FlowEstimator.GH;
        if (fb == null || fb.getWidth() != gw || fb.getHeight() != gh) throw new IllegalStateException("bad flow.png");
        int[] px = new int[gw * gh];
        fb.getPixels(px, 0, gw, 0, 0, gw, gh);
        byte[] rg = new byte[gw * gh * 2];
        for (int i = 0; i < px.length; i++) { rg[2 * i] = (byte) ((px[i] >> 16) & 0xFF); rg[2 * i + 1] = (byte) ((px[i] >> 8) & 0xFF); }
        if (kind.equals("sst")) java.util.Arrays.fill(rg, (byte) 128);           // sea temperature is daily: no drift
        File tmp = new File(c.getFilesDir(), "clouds.tmp");
        try (OutputStream out = new FileOutputStream(tmp)) { out.write(jpg); }
        File curFile = cur(c);
        if (!tmp.renameTo(curFile)) { curFile.delete(); if (!tmp.renameTo(curFile)) throw new IllegalStateException("rename failed"); }
        try (OutputStream out = new FileOutputStream(liveFlow(c))) { out.write(rg); }
        p.edit().putLong(Prefs.CLOUDS_CHECKED, now).putLong(Prefs.CLOUDS_TIME, obs).putString(Prefs.CLOUDS_RES, res)
                .putString(Prefs.CLOUDS_SRC, "live:" + kind).remove(Prefs.CLOUDS_ETAG).apply();
        ByteBuffer flow = ByteBuffer.allocateDirect(rg.length).order(java.nio.ByteOrder.nativeOrder());
        flow.put(rg).position(0);
        Log.i(TAG, "live " + kind + " " + res + " observed " + String.format(java.util.Locale.US, "%.0f", (now - obs) / 60_000.0)
                + " min ago (" + jpg.length / 1024 + " KB), motion " + FlowEstimator.stats(flow));
        CloudFrame f = new CloudFrame(fresh, flow, obs, kind);
        for (Sink s : sinks) s.onClouds(f);
        try { ReplayStore.sync(c, p, metered); } catch (Exception e) { Log.w(TAG, "replay sync failed", e); }
    }

    /** Active tropical cyclones with tracks (a few KB), at most every 20 min. */
    private static void syncStorms(Context c, SharedPreferences p, long now) {
        if (now - p.getLong("storms_checked", 0) < 20 * 60_000L) return;
        try {
            byte[] b = download(LIVE_BASE + "storms.json?t=" + now, 20_000);
            new org.json.JSONObject(new String(b, "UTF-8"));                     // validate
            File tmp = new File(c.getFilesDir(), "storms.tmp"), dst = new File(c.getFilesDir(), "storms.json");
            try (OutputStream out = new FileOutputStream(tmp)) { out.write(b); }
            if (!tmp.renameTo(dst)) { dst.delete(); tmp.renameTo(dst); }
            p.edit().putLong("storms_checked", now).apply();
        } catch (Exception e) { Log.w(TAG, "storms: " + e.getMessage()); }
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
                .putString(Prefs.CLOUDS_RES, res)
                .putString(Prefs.CLOUDS_SRC, "classic");
        if (prevTime > 0) e.putLong(Prefs.CLOUDS_PREV_TIME, prevTime);
        e.apply();
        Log.i(TAG, "clouds updated " + res + " map time " + newTime);
        CloudFrame f = new CloudFrame(fresh, flow, newTime);
        for (Sink s : sinks) s.onClouds(f);
    }
}
