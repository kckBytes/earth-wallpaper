package com.chai.earthwall;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;
import android.view.Choreographer;
import android.view.SurfaceHolder;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * One GL thread per surface (wallpaper engine or the Explore screen). Frames are paced by
 * Choreographer: every vsync during real animations (wake spin, fly-to, drag, zoom, crossfade,
 * label), 60 fps while the phone is being tilted, 30 fps when nothing but stars/clouds change.
 */
final class GlobeThread extends HandlerThread implements Choreographer.FrameCallback, CloudUpdater.Sink {
    private static final String TAG = "EarthWall";
    private static final ExecutorService decoder = Executors.newSingleThreadExecutor();

    static final int MODE_WALLPAPER = 0, MODE_EXPLORE = 1;
    private static final int EASE_OUT_QUINT = 1, EASE_IN_OUT_CUBIC = 2;

    private final Context ctx;
    private final int mode;
    private final Handler h;
    private final GlobeRenderer r = new GlobeRenderer();
    private final GlobeRenderer.Frame fr = new GlobeRenderer.Frame();

    private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
    private EGLContext egl = EGL14.EGL_NO_CONTEXT;
    private EGLConfig cfgEgl;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE, window = EGL14.EGL_NO_SURFACE;
    private boolean glOk;
    private Choreographer choreo;

    // ---- GL-thread state
    private int width, height;
    private boolean visible, frameQueued, dragging;
    private Prefs.Config cfg = new Prefs.Config();
    private int loadedMonth = -1;
    private long lastFrameNs, lastRenderNs, wakeNs, cloudFadeNs;
    private float zoomOut, zoomOutTarget, home, homeTarget, parX, parY, readyFade, lastRadius = 500f;
    private final float[] lay = new float[4];

    // camera (degrees; zoom 1 = normal)
    private double camLat = Prefs.HOME_DEFAULT.lat, camLon = Prefs.HOME_DEFAULT.lon, camZoom = 1;
    private double fLat, fLon, fZoom, tLat, tLon, tZoom, twArc;
    private long twStart, twDur;
    private int twEase;
    private Prefs.City markerCity, overrideCity;
    private float pixelScale = 1f;                      // surface px per screen px (wallpaper renders at 0.8)
    private int statVsync, statTilt, statIdle;
    private long statStartNs, statCpuNs;
    private long labelStartNs;
    private boolean zoomed;                             // wallpaper: Quick Tap toggles a close-up
    private boolean asleep = true;
    private long hiddenAtNs;
    private static final double LOCK_ZOOM = 2.2;

    // ---- written from other threads
    private volatile float gyroX, gyroY;
    private volatile float stickX, stickY, stickZ;   // Explore joysticks, -1..1
    private long lastStickNs;
    private float lastCx, lastCy, lastDist = 6.6f;     // last frame's placement, for screen -> globe picks
    private static final double MAX_ZOOM = 3.0;

    GlobeThread(Context c, int mode) {
        super("EarthGL", Process.THREAD_PRIORITY_DISPLAY);
        ctx = c.getApplicationContext();
        this.mode = mode;
        start();
        h = new Handler(getLooper());
        h.post(this::initGl);
        CloudUpdater.register(this);
    }

    // ------------------------------------------------------------------ API (any thread)
    void setConfig(Prefs.Config c) {
        h.post(() -> {
            boolean cityChanged = cfg.city.lat != c.city.lat || cfg.city.lon != c.city.lon;
            cfg = c;
            if (mode == MODE_WALLPAPER) {
                if (overrideCity == null) markerCity = c.city;
                if (cityChanged && overrideCity == null) tweenTo(lockLat(c.city), c.city.lon, 1, 1400, EASE_IN_OUT_CUBIC);
            } else if (markerCity == null) {
                markerCity = c.city;
                camLat = c.city.lat; camLon = c.city.lon;
            }
            kick();
        });
    }

    void surfaceCreated(SurfaceHolder holder) { h.post(() -> createWindow(holder)); }
    void surfaceChanged(int w, int hgt) { h.post(() -> { width = w; height = hgt; kick(); }); }

    /** Shade at a fraction of screen resolution (offscreen buffer + GPU upscale). */
    void setRenderScale(float s) { h.post(() -> r.renderScale = s); }

    /** Blocks until the EGL window surface is gone, as SurfaceHolder.Callback requires. */
    void surfaceDestroyedAndWait() {
        CountDownLatch done = new CountDownLatch(1);
        h.post(() -> { destroyWindow(); done.countDown(); });
        try { done.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }

    /**
     * Visibility alone no longer resets the view: a brief cover (Quick Tap launching the zoom
     * trampoline, a notification shade) must not undo your city/zoom. The reset + wake spin happen
     * on a real sleep -> wake (onSleep/onWake, or after being hidden for > 3 s as a fallback).
     */
    void setVisible(boolean v, boolean onLockScreen) {
        h.post(() -> {
            boolean was = visible;
            visible = v;
            long now = System.nanoTime();
            if (!v && was) hiddenAtNs = now;
            if (v && !was) {
                maybeReloadMonth();
                if (mode == MODE_WALLPAPER) {
                    homeTarget = home = onLockScreen ? 0f : 1f;
                    boolean longGone = hiddenAtNs == 0 || now - hiddenAtNs > 3_000_000_000L;
                    if (asleep || longGone) startWake(onLockScreen);
                } else {
                    wakeNs = now - 10_000_000_000L;
                }
            }
            kick();
        });
    }

    /** Screen went to sleep: next wake starts from home, full globe. */
    void onSleep() { h.post(() -> { asleep = true; }); }

    /** Screen woke: play the spin-in (the "wakingup" wallpaper command arrives before visibility). */
    void onWake() { h.post(() -> { if (mode == MODE_WALLPAPER && asleep) startWake(true); }); }

    private void startWake(boolean onLockScreen) {
        asleep = false;
        overrideCity = null;
        zoomed = false;
        markerCity = cfg.city;
        labelStartNs = 0;
        double lat = lockLat(cfg.city);
        if (onLockScreen) {
            // start a little west of home and let Earth turn eastward into place
            wakeNs = System.nanoTime();
            camLat = lat; camLon = cfg.city.lon + 12.0; camZoom = 1;
            tweenTo(lat, cfg.city.lon, 1, 2300, EASE_OUT_QUINT);
            lapseNs = wakeNs;                                  // clouds replay the last 3 h meanwhile
        } else {
            wakeNs = System.nanoTime() - 10_000_000_000L;
            camLat = lat; camLon = cfg.city.lon; camZoom = 1; twEase = 0;
        }
        kick();
    }

    // ---- cloud time-lapse: replay the last LAPSE_H hours of cloud motion, easing into real time
    private static final double LAPSE_H = 12.0, LAPSE_S = 3.0;
    private long lapseNs;
    /** Back triple-tap: play the cloud time-lapse again. */
    void playTimelapse() { h.post(() -> { lapseNs = System.nanoTime(); android.util.Log.i("EarthWall", "time-lapse start"); kick(); }); }
    private double lapseOffsetMs(long frameNs) {
        if (lapseNs == 0) return 0;
        double p = (frameNs - lapseNs) / (LAPSE_S * 1e9);
        if (p >= 1) { lapseNs = 0; return 0; }
        double e = 1 - Math.pow(1 - Math.max(0, p), 3);     // ease-out: fast at first, settles into "now"
        return -LAPSE_H * 3_600_000.0 * (1 - e);
    }

    // ---- time warp (control mode): hold to fast-forward, release to snap back to now
    private volatile boolean warp;
    private double warpOffsetMs;
    void setTimeWarp(boolean on) { warp = on; h.post(this::kick); }

    /** Control mode: zoom in/out one step around the current centre. */
    void zoomStep(int dir) {
        h.post(() -> {
            double nz = dir > 0 ? Math.min(MAX_ZOOM, camZoom * 1.6) : Math.max(1.0, camZoom / 1.6);
            tweenTo(camLat, camLon, nz, 600, EASE_IN_OUT_CUBIC);
            kick();
        });
    }

    void setHome(boolean onHome) { h.post(() -> { homeTarget = onHome ? 1f : 0f; kick(); }); }
    void setZoomOut(float z) { h.post(() -> { zoomOutTarget = z; kick(); }); }
    void setTilt(float x, float y) { gyroX = x; gyroY = y; }

    /** Explore joysticks: move (x = east/west, y = north/south) and zoom (z > 0 = in). */
    void setStick(float x, float y) { stickX = x; stickY = y; h.post(this::kick); }
    void setZoomStick(float z) { stickZ = z; h.post(this::kick); }

    /**
     * Explore: double-tap at a screen point (px, top-left origin). Zooms in step by step on the tapped
     * region (it slides to the centre); a double-tap when fully zoomed returns to the whole globe.
     */
    void zoomAt(float sx, float sy) {
        h.post(() -> {
            double nz = camZoom >= MAX_ZOOM - 0.05 ? 1.0 : Math.min(MAX_ZOOM, camZoom * 1.7);
            double lat = camLat, lon = camLon;
            float gx = sx, gy = height - sy;                     // to GL convention
            double qx = (gx - lastCx) / lastRadius, qy = (gy - lastCy) / lastRadius;
            if (nz > camZoom && qx * qx + qy * qy < 1.0) {
                // ray from the camera through the tapped pixel, hit the unit sphere (same as the shader)
                double D = lastDist, tanA = 1.0 / Math.sqrt(D * D - 1.0);
                double rx = qx * tanA, ry = qy * tanA, rz = -1.0;
                double n = Math.sqrt(rx * rx + ry * ry + 1.0); rx /= n; ry /= n; rz /= n;
                double b = D * rz, c = D * D - 1.0, disc = b * b - c;
                if (disc > 0) {
                    double t = -b - Math.sqrt(disc);
                    double px = rx * t, py = ry * t, pz = D + rz * t;
                    float[] m = fr.view;                           // view -> ECEF, column-major
                    double ex = m[0] * px + m[3] * py + m[6] * pz;
                    double ey = m[1] * px + m[4] * py + m[7] * pz;
                    double ez = m[2] * px + m[5] * py + m[8] * pz;
                    lat = Math.max(-80, Math.min(80, Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, ez))))));
                    lon = Math.toDegrees(Math.atan2(ey, ex));
                }
            }
            tweenTo(lat, lon, nz, 900, EASE_IN_OUT_CUBIC);
            kick();
        });
    }

    /** Explore: fly the camera to a city at a zoom level (1 = whole disk; <= 0 keeps the current zoom). */
    void flyTo(Prefs.City c, double zoomArg) {
        h.post(() -> {
            double zoom = zoomArg > 0 ? zoomArg : Math.max(1.0, camZoom);
            markerCity = c;
            double ang = angularDistance(camLat, camLon, c.lat, c.lon);
            long dur = (long) (900 + 900 * Math.min(1.0, ang / 120.0) + 300 * Math.abs(zoom - camZoom));
            tweenTo(c.lat, c.lon, zoom, dur, EASE_IN_OUT_CUBIC);
            twArc = Math.min(1.0, ang / 70.0);
            kick();
        });
    }

    /** Wallpaper: double-twist gesture — fly to the next city in the Explore list and show its name. */
    void nextCity() {
        h.post(() -> {
            List<Prefs.City> list = new ArrayList<>();
            list.add(cfg.city);
            for (Prefs.City c : Prefs.presets(Prefs.get(ctx))) {
                boolean dup = false;
                for (Prefs.City d : list) if (Math.abs(d.lat - c.lat) < 1e-3 && Math.abs(d.lon - c.lon) < 1e-3) dup = true;
                if (!dup) list.add(c);
            }
            Prefs.City now = overrideCity != null ? overrideCity : cfg.city;
            int idx = 0;
            for (int i = 0; i < list.size(); i++)
                if (Math.abs(list.get(i).lat - now.lat) < 1e-3 && Math.abs(list.get(i).lon - now.lon) < 1e-3) idx = i;
            Prefs.City next = list.get((idx + 1) % list.size());
            overrideCity = next;
            markerCity = next;
            double ang = angularDistance(camLat, camLon, lockLat(next), next.lon);
            tweenTo(lockLat(next), next.lon, zoomed ? LOCK_ZOOM : 1, (long) (1100 + 900 * Math.min(1.0, ang / 120.0)), EASE_IN_OUT_CUBIC);
            twArc = Math.min(1.0, ang / 70.0);
            if (glOk) r.setLabel(makeLabel(next.name));
            labelStartNs = System.nanoTime();
            kick();
        });
    }

    /** Wallpaper: flick gesture — ease in close on the city that's showing, or back out. */
    void toggleZoom() {
        h.post(() -> {
            Prefs.City c = overrideCity != null ? overrideCity : cfg.city;
            zoomed = !zoomed;
            tweenTo(lockLat(c), c.lon, zoomed ? LOCK_ZOOM : 1, zoomed ? 1300 : 1000, EASE_IN_OUT_CUBIC);
            if (zoomed) {
                markerCity = c;
                if (glOk) r.setLabel(makeLabel(c.name));
                labelStartNs = System.nanoTime();
            }
            kick();
        });
    }

    private Bitmap makeLabel(String text) {
        float d = ctx.getResources().getDisplayMetrics().density * pixelScale;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setTextSize(21 * d);
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        p.setLetterSpacing(0.04f);
        p.setShadowLayer(7 * d, 0, 0, 0xB0000000);
        int pad = (int) (12 * d);
        int w = (int) Math.ceil(p.measureText(text)) + 2 * pad;
        Paint.FontMetrics fm = p.getFontMetrics();
        int hgt = (int) Math.ceil(fm.descent - fm.ascent) + 2 * pad;
        Bitmap b = Bitmap.createBitmap(w, hgt, Bitmap.Config.ARGB_8888);
        new Canvas(b).drawText(text, pad, pad - fm.ascent, p);
        return b;
    }

    private float labelAlpha(long now) {
        if (labelStartNs == 0) return 0f;
        float t = (now - labelStartNs) / 1e9f;
        if (t < 0.4f) return t / 0.4f;
        if (t < 3.2f) return 1f;
        if (t < 4.0f) return 1f - (t - 3.2f) / 0.8f;
        labelStartNs = 0;
        return 0f;
    }

    /** Explore: rotate the globe with a finger drag (screen px). */
    void dragBy(float dx, float dy) {
        h.post(() -> {
            twEase = 0;
            double k = Math.toDegrees(1.0 / Math.max(lastRadius, 1f)) * 0.9;
            camLon = wrapLon(camLon - dx * k);
            camLat = Math.max(-80, Math.min(80, camLat + dy * k));
            kick();
        });
    }

    void setDragging(boolean d) { h.post(() -> { dragging = d; kick(); }); }

    void shutdown() {
        CloudUpdater.unregister(this);
        h.post(() -> {
            if (choreo != null) choreo.removeFrameCallback(this);
            destroyWindow();
            if (glOk) r.release();
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, pbuffer);
                EGL14.eglDestroyContext(dpy, egl);
                EGL14.eglReleaseThread();
            }
            quitSafely();
        });
    }

    @Override public void onClouds(CloudUpdater.CloudFrame f) {
        h.post(() -> {
            if (!glOk) return;
            r.pushClouds(f);
            cloudFadeNs = System.nanoTime();          // crossfade: predicted clouds -> real new map
            kick();
        });
    }

    // ------------------------------------------------------------------ camera
    private static double lockLat(Prefs.City c) { return Math.max(-60, Math.min(60, c.lat)); }

    private static double wrapLon(double l) { return ((l + 540.0) % 360.0) - 180.0; }

    private static double angularDistance(double la1, double lo1, double la2, double lo2) {
        double a = Math.toRadians(la1), b = Math.toRadians(la2), d = Math.toRadians(lo2 - lo1);
        double c = Math.sin(a) * Math.sin(b) + Math.cos(a) * Math.cos(b) * Math.cos(d);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c))));
    }

    private void tweenTo(double lat, double lon, double zoom, long ms, int ease) {
        fLat = camLat; fLon = camLon; fZoom = camZoom;
        tLat = lat; tZoom = zoom;
        double d = ((lon - camLon) % 360.0 + 540.0) % 360.0 - 180.0;      // shortest way round
        tLon = camLon + d;
        twStart = System.nanoTime(); twDur = ms * 1_000_000L; twEase = ease; twArc = 0;
    }

    private boolean sticking() { return stickX != 0f || stickY != 0f || stickZ != 0f; }

    private void stepCamera(long now) {
        if (sticking()) {
            twEase = 0;                                         // the stick overrides any fly-to
            double dt = lastStickNs == 0 ? 0 : Math.min((now - lastStickNs) / 1e9, 0.05);
            lastStickNs = now;
            double sx = Math.signum(stickX) * Math.pow(Math.abs(stickX), 1.6);   // fine control near centre
            double sy = Math.signum(stickY) * Math.pow(Math.abs(stickY), 1.6);
            double rate = 75.0 / Math.max(camZoom, 0.8);        // deg/s at full deflection, slower when close
            camLon = wrapLon(camLon + sx * rate * dt);
            camLat = Math.max(-80, Math.min(80, camLat + sy * rate * dt));
            camZoom = Math.max(0.8, Math.min(MAX_ZOOM, camZoom * Math.exp(stickZ * 1.0 * dt)));
            return;
        }
        lastStickNs = 0;
        if (twEase == 0) return;
        double t = Math.min(1.0, (now - twStart) / (double) twDur);
        double e = twEase == EASE_OUT_QUINT ? 1 - Math.pow(1 - t, 5)
                : (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2);
        camLat = fLat + (tLat - fLat) * e;
        camLon = wrapLon(fLon + (tLon - fLon) * e);
        camZoom = (fZoom + (tZoom - fZoom) * e) * (1 - 0.18 * twArc * Math.sin(Math.PI * t));  // pull back on long hops
        if (t >= 1.0) twEase = 0;
    }

    // ------------------------------------------------------------------ EGL
    private void initGl() {
        choreo = Choreographer.getInstance();
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) { Log.e(TAG, "eglInitialize failed"); return; }
        int[] attrs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 0,
                EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE};
        EGLConfig[] cfgs = new EGLConfig[1];
        int[] n = new int[1];
        if (!EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, n, 0) || n[0] == 0) { Log.e(TAG, "no EGL config"); return; }
        cfgEgl = cfgs[0];
        egl = EGL14.eglCreateContext(dpy, cfgEgl, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
        pbuffer = EGL14.eglCreatePbufferSurface(dpy, cfgEgl,
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        EGL14.eglMakeCurrent(dpy, pbuffer, pbuffer, egl);
        glOk = r.init(ctx.getAssets());
        if (glOk) loadTextures();
    }

    private void createWindow(SurfaceHolder holder) {
        if (!glOk) return;
        destroyWindow();
        window = EGL14.eglCreateWindowSurface(dpy, cfgEgl, holder.getSurface(), new int[]{EGL14.EGL_NONE}, 0);
        if (window == EGL14.EGL_NO_SURFACE) { Log.e(TAG, "window surface failed"); return; }
        EGL14.eglMakeCurrent(dpy, window, window, egl);
        EGL14.eglSwapInterval(dpy, 1);
        kick();
    }

    private void destroyWindow() {
        if (window == EGL14.EGL_NO_SURFACE) return;
        EGL14.eglMakeCurrent(dpy, pbuffer, pbuffer, egl);
        EGL14.eglDestroySurface(dpy, window);
        window = EGL14.EGL_NO_SURFACE;
    }

    // ------------------------------------------------------------------ textures
    private static int currentMonth() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).get(Calendar.MONTH) + 1;
    }

    private void maybeReloadMonth() {
        if (glOk && loadedMonth != -1 && loadedMonth != currentMonth()) loadDay(currentMonth());
    }

    private void loadDay(int month) {
        decoder.execute(() -> {
            try {
                Bitmap b = Images.decodeRgb(ctx.getAssets().open(String.format("day/%02d.jpg", month)));
                h.post(() -> { r.uploadDay(b); b.recycle(); loadedMonth = month; kick(); });
            } catch (Exception e) { Log.e(TAG, "day texture", e); }
        });
    }

    private void loadTextures() {
        loadDay(currentMonth());
        decoder.execute(() -> {
            try {
                Images.Single l = Images.decodeSingle(ctx.getAssets().open("lights.jpg"));
                h.post(() -> r.uploadSingle(GlobeRenderer.LIGHTS, l.data, l.w, l.h));
                Images.Single w = Images.decodeSingle(ctx.getAssets().open("water.jpg"));
                h.post(() -> r.uploadSingle(GlobeRenderer.WATER, w.data, w.w, w.h));
                CloudUpdater.CloudFrame c = CloudUpdater.loadInitial(ctx);
                h.post(() -> { r.pushClouds(c); cloudFadeNs = 0; kick(); });
            } catch (Exception e) { Log.e(TAG, "texture load", e); }
        });
    }

    // ------------------------------------------------------------------ frame loop
    private void kick() {
        if (!frameQueued && visible && choreo != null && window != EGL14.EGL_NO_SURFACE) {
            frameQueued = true;
            choreo.postFrameCallback(this);
        }
    }

    private static float approach(float cur, float target, float rate, float dt) {
        return cur + (target - cur) * (1f - (float) Math.exp(-rate * dt));
    }

    @Override public void doFrame(long frameNs) {
        frameQueued = false;
        if (!visible || window == EGL14.EGL_NO_SURFACE || width == 0) return;
        kick();

        float dt = lastFrameNs == 0 ? 0f : Math.min((frameNs - lastFrameNs) / 1e9f, 0.1f);
        lastFrameNs = frameNs;

        zoomOut = approach(zoomOut, zoomOutTarget, 14f, dt);
        home = approach(home, homeTarget, 7f, dt);
        float g = cfg.parallax ? cfg.gain : 0f;
        float gx = gyroX * g, gy = gyroY * g;
        parX = approach(parX, gx, 9f, dt);
        parY = approach(parY, gy, 9f, dt);
        if (r.ready()) readyFade = Math.min(1f, readyFade + dt / 0.6f);

        if (warp) warpOffsetMs += dt * 7_200_000.0;                       // 2 hours per second
        else if (warpOffsetMs > 0) warpOffsetMs = warpOffsetMs < 60_000 ? 0 : warpOffsetMs * Math.exp(-6.0 * dt);
        float wake = Math.min(1f, (frameNs - wakeNs) / 1.0e9f);
        float cloudMix = cloudFadeNs == 0 ? 1f : Math.min(1f, (frameNs - cloudFadeNs) / 3.0e9f);
        lapseOffsetMs(frameNs);                                         // ends the time-lapse when due
        boolean animating = lapseNs != 0 || twEase != 0 || dragging || sticking() || warp || warpOffsetMs > 0 || wake < 1f || readyFade < 1f || cloudMix < 1f
                || labelStartNs != 0
                || Math.abs(zoomOut - zoomOutTarget) > 1e-3f || Math.abs(home - homeTarget) > 1e-3f;
        // Hand tremor alone moves the parallax by far less than a pixel: ignore it (deadband).
        boolean tilting = Math.abs(parX - gx) > 1.2e-3f || Math.abs(parY - gy) > 1.2e-3f;

        long minGap = animating ? 0L : tilting ? 15_500_000L : 32_000_000L;   // vsync / 60 fps / 30 fps
        if (frameNs - lastRenderNs < minGap) return;
        lastRenderNs = frameNs;
        stepCamera(frameNs);
        long c0 = System.nanoTime();
        render(frameNs, wake, cloudMix);
        statCpuNs += System.nanoTime() - c0;
        if (animating) statVsync++; else if (tilting) statTilt++; else statIdle++;
        if (statStartNs == 0) statStartNs = frameNs;
        if (frameNs - statStartNs > 3_000_000_000L) {
            int n = statVsync + statTilt + statIdle;
            Log.i(TAG, String.format(java.util.Locale.US, "%s %dx%d frames/3s: anim=%d tilt=%d idle=%d  cpu+swap %.1f ms/frame",
                    mode == MODE_WALLPAPER ? "wallpaper" : "explore", width, height, statVsync, statTilt, statIdle,
                    n == 0 ? 0 : statCpuNs / 1e6 / n));
            statVsync = statTilt = statIdle = 0; statCpuNs = 0; statStartNs = frameNs;
        }
    }

    private void render(long frameNs, float wake, float cloudMix) {
        float e = 1f - (float) Math.pow(1f - wake, 3);
        double t = frameNs / 1e9;

        float cx, cy, radius, dist;
        if (mode == MODE_WALLPAPER) {
            Prefs.layout(cfg, width, height, lay);
            float scale = (1.03f - 0.03f * e) * (1f - 0.07f * zoomOut) * (1f - 0.05f * home);
            float pz = (float) Math.pow(camZoom, 1.5);          // zoomed in: calmer parallax
            cx = width * 0.5f + (lay[0] - width * 0.5f) * scale - parX * 30f * pixelScale / pz;
            cy = height * 0.5f + (lay[1] - height * 0.5f) * scale + parY * 30f * pixelScale / pz;
            radius = (float) (lay[2] * scale * camZoom);
            dist = lay[3];
        } else {
            // Same placement as the lock-screen wallpaper, so opening over the lock screen is seamless.
            Prefs.Config disk = cfg;
            if (disk.horizon) { disk = Prefs.read(Prefs.get(ctx)); disk.horizon = false; }
            Prefs.layout(disk, width, height, lay);
            cx = lay[0]; cy = lay[1];
            radius = (float) (lay[2] * camZoom);
            dist = lay[3];
        }
        lastRadius = radius;
        lastCx = cx; lastCy = cy; lastDist = dist;

        double lat = camLat, lon = camLon;
        if (mode == MODE_WALLPAPER && cfg.motion) {
            lon += 5.0 * Math.sin(2 * Math.PI * t / 140.0);
            lat += 2.0 * Math.sin(2 * Math.PI * t / 97.0 + 1.3);
        }
        double pzr = Math.pow(camZoom, 1.5);                   // same surface motion would be 2x on screen when zoomed
        lon += parX * 45.0 / pzr;
        lat = Math.max(-80, Math.min(80, lat - parY * 45.0 / pzr));
        Astro.viewToEcef(lat, lon, fr.view);
        long nowMs = System.currentTimeMillis() + (long) warpOffsetMs;
        fr.gmst = (float) Astro.sun(nowMs, fr.sun);

        fr.w = width; fr.h = height; fr.cx = cx; fr.cy = cy; fr.radius = radius; fr.camDist = dist;
        fr.cloudMix = cloudMix; fr.nowMs = nowMs; fr.cloudNowMs = nowMs + (long) lapseOffsetMs(frameNs);
        fr.fade = readyFade * (mode == MODE_WALLPAPER ? (0.25f + 0.75f * e) : 1f)
                * (1f - 0.22f * zoomOut) * (1f - 0.30f * home);
        fr.time = (float) (t % 3600.0);
        fr.starDx = parX * -260f * pixelScale; fr.starDy = parY * 260f * pixelScale;
        fr.stars = cfg.stars;
        projectMarker(dist, cx, cy, radius);
        fr.labelAmt = labelAlpha(frameNs) * readyFade;
        fr.labelCx = width * 0.5f;
        fr.labelCy = Math.max(cy - radius - 46f * ctx.getResources().getDisplayMetrics().density * pixelScale,
                height * 0.07f);                                       // just under the globe
        r.draw(fr);
        if (!EGL14.eglSwapBuffers(dpy, window)) {
            int err = EGL14.eglGetError();
            Log.w(TAG, "swap failed 0x" + Integer.toHexString(err));
            if (err == EGL14.EGL_BAD_SURFACE || err == EGL14.EGL_BAD_NATIVE_WINDOW) destroyWindow();
        }
    }

    /** Places the city marker in screen space (fades out as it rotates over the limb). */
    private void projectMarker(float dist, float cx, float cy, float radius) {
        fr.markerAmt = 0f;
        boolean show = markerCity != null && (mode == MODE_EXPLORE || cfg.marker);
        if (!show) return;
        double la = Math.toRadians(markerCity.lat), lo = Math.toRadians(markerCity.lon);
        double ex = Math.cos(la) * Math.cos(lo), ey = Math.cos(la) * Math.sin(lo), ez = Math.sin(la);
        float[] m = fr.view;
        double vx = m[0] * ex + m[1] * ey + m[2] * ez;
        double vy = m[3] * ex + m[4] * ey + m[5] * ez;
        double vz = m[6] * ex + m[7] * ey + m[8] * ez;
        double facing = vz * dist;                                   // > 1 means on the visible side
        if (facing <= 1.0) return;
        double tanA = 1.0 / Math.sqrt(dist * dist - 1.0), dz = dist - vz;
        fr.markerX = (float) (cx + vx / dz / tanA * radius);
        fr.markerY = (float) (cy + vy / dz / tanA * radius);
        float vis = (float) Math.min(1.0, (facing - 1.0) / 0.6);
        float density = ctx.getResources().getDisplayMetrics().density;
        fr.markerSize = 2.6f * density * pixelScale;
        fr.markerAmt = vis * (mode == MODE_EXPLORE ? 0.95f : 0.6f) * fr.fade;
    }
}
