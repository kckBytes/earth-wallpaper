package com.chai.earthwall;

import android.app.KeyguardManager;
import android.app.WallpaperColors;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;

public class EarthWallpaperService extends WallpaperService {

    private static final float RENDER_SCALE = 0.8f;
    /** Sent by ZoomTapActivity (Pixel Quick Tap -> "Open app" -> Earth zoom). */
    static final String ACTION_TOGGLE_ZOOM = "com.chai.earthwall.TOGGLE_ZOOM";

    @Override public Engine onCreateEngine() { return new EarthEngine(); }

    private final class EarthEngine extends Engine
            implements SharedPreferences.OnSharedPreferenceChangeListener, SensorEventListener {

        private GlobeThread gl;
        private SharedPreferences prefs;
        private SensorManager sensors;
        private Sensor gyro, accel;
        private boolean sensorOn, accelOn, visible, parallax, flip, knockZoom;
        // back double-tap detector (accelerometer knocks, only after the phone was held still)
        private final float[] grav = new float[3];
        private boolean gravInit;
        private int taps;
        private long firstTapNs, secondTapNs, lastTapNs, lastMoveNs;
        private float gyroMag, prevHz, prevHz2;
        private long burstStartNs, prevBurstEndNs;
        private long lastGyroNs;
        private float tiltX, tiltY;
        // flip-and-back detector (integrated rotation about the phone's long and short axes)
        private double angX, angY;
        private boolean flipOut;
        private int flipAxis;                       // 0 = x, 1 = y
        private long flipOutNs, cooldownUntilNs;
        private final Handler main = new Handler(Looper.getMainLooper());

        /** While the wallpaper stays on screen (e.g. on a charger), keep checking for new clouds. */
        private final Runnable periodicCheck = new Runnable() {
            @Override public void run() {
                if (!visible) return;
                CloudUpdater.maybeUpdate(EarthWallpaperService.this, false);
                main.postDelayed(this, 5 * 60_000L);
            }
        };

        private final BroadcastReceiver rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (gl == null) return;
                String a = i.getAction();
                if (Intent.ACTION_USER_PRESENT.equals(a)) gl.setHome(true);           // unlocked
                else if (Intent.ACTION_SCREEN_OFF.equals(a)) { gl.setHome(false); gl.onSleep(); }
                else if (ACTION_TOGGLE_ZOOM.equals(a)) { Log.i("EarthWall", "quick tap -> zoom"); gl.toggleZoom(); }
            }
        };

        @Override public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(false);          // the lock screen never forwards touches anyway
            setOffsetNotificationsEnabled(false);
            prefs = Prefs.get(EarthWallpaperService.this);
            prefs.registerOnSharedPreferenceChangeListener(this);
            sensors = getSystemService(SensorManager.class);
            gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gl = new GlobeThread(EarthWallpaperService.this, GlobeThread.MODE_WALLPAPER);
            // Shade at 80% resolution into an offscreen buffer, upscale on the GPU.
            // NB: wallpapers may NOT call SurfaceHolder.setFixedSize — it throws.
            gl.setRenderScale(RENDER_SCALE);
            applyPrefs();
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_USER_PRESENT);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(ACTION_TOGGLE_ZOOM);
            registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED);
        }

        @Override public void onDestroy() {
            main.removeCallbacks(periodicCheck);
            unregisterReceiver(rx);
            prefs.unregisterOnSharedPreferenceChangeListener(this);
            setSensor(false);
            gl.shutdown();
            super.onDestroy();
        }

        @Override public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            gl.surfaceCreated(holder);
        }

        @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            super.onSurfaceChanged(holder, format, w, h);
            gl.surfaceChanged(w, h);
        }

        @Override public void onSurfaceDestroyed(SurfaceHolder holder) {
            gl.surfaceDestroyedAndWait();
            super.onSurfaceDestroyed(holder);
        }

        @Override public void onVisibilityChanged(boolean v) {
            visible = v;
            KeyguardManager km = getSystemService(KeyguardManager.class);
            gl.setVisible(v, km != null && km.isKeyguardLocked());
            setSensor(v && (parallax || flip || knockZoom));
            main.removeCallbacks(periodicCheck);
            if (v) periodicCheck.run();
        }

        /** System UI tells wallpapers when the screen wakes and sleeps (seen on this Pixel). */
        @Override public Bundle onCommand(String action, int x, int y, int z, Bundle extras, boolean resultRequested) {
            if ("android.wallpaper.wakingup".equals(action)) gl.onWake();
            else if ("android.wallpaper.goingtosleep".equals(action)) gl.onSleep();
            return super.onCommand(action, x, y, z, extras, resultRequested);
        }

        /** 0 = full size, 1 = fully zoomed out (shade pulled, app switcher). */
        @Override public void onZoomChanged(float zoom) { gl.setZoomOut(zoom); }

        @Override public WallpaperColors onComputeColors() {
            return new WallpaperColors(Color.valueOf(0xFF1B3F73), Color.valueOf(0xFF0A1424),
                    Color.valueOf(0xFF000000), WallpaperColors.HINT_SUPPORTS_DARK_THEME);
        }

        @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
            if (Prefs.REFRESH.equals(key) || Prefs.LIVE_CLOUDS.equals(key)) { CloudUpdater.maybeUpdate(EarthWallpaperService.this, true); return; }
            if (Prefs.VIEW.equals(key) || Prefs.REPLAY_H.equals(key) || Prefs.WX.equals(key) || Prefs.STREAKS.equals(key)) { applyPrefs(); CloudUpdater.maybeUpdate(EarthWallpaperService.this, true); return; }
            if (key != null && (key.startsWith("clouds_") || key.equals(Prefs.PRESETS))) return;
            applyPrefs();
        }

        private void applyPrefs() {
            Prefs.Config c = Prefs.read(prefs);
            parallax = c.parallax && gyro != null;
            flip = prefs.getBoolean(Prefs.FLIP, true) && gyro != null;
            knockZoom = prefs.getBoolean(Prefs.KNOCK, true) && accel != null && gyro != null;
            if (!parallax) { tiltX = tiltY = 0f; gl.setTilt(0f, 0f); }
            setSensor(visible && (parallax || flip || knockZoom));
            gl.setConfig(c);
        }

        private void setSensor(boolean on) {
            if (gyro != null && on != sensorOn) {
                sensorOn = on;
                if (on) { lastGyroNs = 0; angX = angY = 0; flipOut = false; sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME); }
                else sensors.unregisterListener(this, gyro);
            }
            boolean wantAccel = on && knockZoom;
            if (wantAccel != accelOn) {
                accelOn = wantAccel;
                if (wantAccel) {
                    gravInit = false; taps = 0; lastMoveNs = SystemClockNs();
                    sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_FASTEST);
                } else sensors.unregisterListener(this, accel);
            }
        }

        private long SystemClockNs() { return android.os.SystemClock.elapsedRealtimeNanos(); }

        /**
         * Double-tap the back (or the screen) = zoom. A tap jolts the phone along its screen normal:
         * two sharp z spikes 90-420 ms apart. The first tap only counts if the phone was held still
         * for 120 ms before it (not counting the 350 ms recoil after a previous tap) (no jolt > 1 m/s^2, no turn > 0.9 rad/s) — walking is never that still, which
         * is what made the previous version zoom while walking. Between the two taps only big motion
         * cancels (your hand's small correction after tap one does not).
         * Knocks measured on this phone: 1.3-3.0 m/s^2.
         */
        private void onAccel(SensorEvent e) {
            if (!gravInit) { System.arraycopy(e.values, 0, grav, 0, 3); gravInit = true; return; }
            for (int i = 0; i < 3; i++) grav[i] += (e.values[i] - grav[i]) * 0.08f;
            float hx = e.values[0] - grav[0], hy = e.values[1] - grav[1], hz = e.values[2] - grav[2];
            float az = Math.abs(hz), axy = Math.max(Math.abs(hx), Math.abs(hy));
            long t = e.timestamp;
            boolean ringing = t - lastTapNs < 120_000_000L;          // a knock's own aftershock
            boolean recoil = t - lastTapNs < 350_000_000L;           // your hand absorbing the tap
            // A finger tap is an impulse: it rises within ~5 ms (2 samples at 400 Hz). Footsteps (phone
            // held flat while walking) build up over 30-80 ms, so they fail this sharpness test.
            boolean sharp = Math.abs(hz - prevHz2) > 0.5f * az;
            prevHz2 = prevHz; prevHz = hz;
            // Measured on this phone: a tap on the back pushes the phone toward the screen side first (+z,
            // 3-16 m/s^2 for the first tap, 1.3-8 for the second). Footsteps and hand jiggle showed up as
            // small spikes of either sign (mostly 1.4-2.5, many negative), so require +z and a firmer first tap.
            boolean knock = hz > (taps >= 1 ? 1.2f : 2.6f) && az > 1.8f * axy && gyroMag < 2.5f && sharp;
            // Stillness ignores tap recoil (measured: hard taps 5-15 m/s^2 jolt the hand for ~300 ms,
            // which made back-to-back double-taps — e.g. zoom out right after zoom in — never arm).
            if (!knock && !recoil && (az > 1.0f || axy > 1.0f || gyroMag > 0.9f)) {
                if (t - lastMoveNs > 40_000_000L) { prevBurstEndNs = lastMoveNs; burstStartNs = t; }
                lastMoveNs = t;
            }
            if (taps == 1 && !knock && !ringing && (axy > 3f || gyroMag > 2.5f)) taps = 0;   // (taps == 2 still zooms)
            if (knock && !ringing && t > cooldownUntilNs) {
                // The tap's own leading edge (the few ms before its peak) also counts as "motion";
                // ignore a burst that began < 40 ms ago so it doesn't cancel the tap it belongs to.
                long still = t - burstStartNs < 40_000_000L ? burstStartNs - prevBurstEndNs : t - lastMoveNs;
                Log.i("EarthWall", String.format(java.util.Locale.US, "knock z=%.2f xy=%.2f gyro=%.2f still=%dms taps=%d",
                        hz, axy, gyroMag, still / 1_000_000, taps));
                if (taps == 2 && t - secondTapNs >= 90_000_000L && t - secondTapNs <= 420_000_000L) {
                    taps = 0;
                    cooldownUntilNs = t + 800_000_000L;
                    Log.i("EarthWall", "back triple-tap -> time-lapse");
                    gl.playTimelapse();
                } else if (taps == 1 && t - firstTapNs >= 90_000_000L && t - firstTapNs <= 420_000_000L) {
                    taps = 2;                                  // zoom fires unless a third tap follows
                    secondTapNs = t;
                } else if (still > 120_000_000L) {
                    taps = 1;
                    firstTapNs = t;
                }
                lastTapNs = t;
            }
            if (taps == 1 && t - firstTapNs > 420_000_000L) taps = 0;
            if (taps == 2 && t - secondTapNs > 420_000_000L) {    // no third tap: it was a double-tap
                taps = 0;
                cooldownUntilNs = t + 400_000_000L;
                Log.i("EarthWall", "back double-tap -> zoom");
                gl.toggleZoom();
            }
        }

        /**
         * Flip and flip back = next city. Integrate rotation about x and y; "out" when either passes
         * 140 deg, "back" when that axis returns within 35 deg of the start inside 1.6 s.
         * Walking never rotates the phone 140 deg; picking it up from face-down is a single flip
         * (no return), so neither triggers. While stationary the angles slowly bleed to 0 (gyro drift).
         */
        private boolean detectFlip(SensorEvent e, float dt) {
            float wx = e.values[0], wy = e.values[1];
            angX += wx * dt;
            angY += wy * dt;
            if (Math.abs(wx) < 0.6f && Math.abs(wy) < 0.6f && !flipOut) {
                double k = Math.exp(-dt / 0.7);
                angX *= k; angY *= k;
            }
            long t = e.timestamp;
            if (t < cooldownUntilNs) { angX = angY = 0; return false; }
            final double OUT = Math.toRadians(140), BACK = Math.toRadians(35);
            if (!flipOut) {
                if (Math.abs(angX) > OUT || Math.abs(angY) > OUT) {
                    flipOut = true;
                    flipAxis = Math.abs(angY) >= Math.abs(angX) ? 1 : 0;
                    flipOutNs = t;
                }
                return false;
            }
            double a = flipAxis == 1 ? angY : angX;
            if (Math.abs(a) < BACK) {
                flipOut = false; angX = angY = 0;
                cooldownUntilNs = t + 900_000_000L;
                return true;
            }
            if (t - flipOutNs > 1_600_000_000L) { flipOut = false; angX = angY = 0; }   // didn't come back in time
            return false;
        }

        /** Gyro: flip gesture + parallax (tilt that springs back to centre, a high-pass on attitude). */
        @Override public void onSensorChanged(SensorEvent e) {
            if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) { onAccel(e); return; }
            gyroMag = (float) Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]);
            float dt = lastGyroNs == 0 ? 0f : Math.min((e.timestamp - lastGyroNs) / 1e9f, 0.05f);
            lastGyroNs = e.timestamp;
            if (flip && dt > 0 && detectFlip(e, dt)) {
                Log.i("EarthWall", "flip -> next city");
                tiltX = tiltY = 0f; gl.setTilt(0f, 0f);
                gl.nextCity();
                return;
            }
            // a flip in progress shouldn't throw the parallax around
            if (!parallax || flipOut || Math.abs(angX) > 0.5 || Math.abs(angY) > 0.5) {
                if (tiltX != 0f || tiltY != 0f) { tiltX = tiltY = 0f; gl.setTilt(0f, 0f); }
                return;
            }
            if (dt > 0) {
                float decay = (float) Math.exp(-dt / 1.4f);
                tiltX = clamp((tiltX + e.values[1] * dt) * decay, 0.14f);
                tiltY = clamp((tiltY + e.values[0] * dt) * decay, 0.14f);
                gl.setTilt(tiltX, tiltY);
            }
        }

        @Override public void onAccuracyChanged(Sensor s, int a) { }

        private float clamp(float v, float m) { return Math.max(-m, Math.min(m, v)); }
    }
}
