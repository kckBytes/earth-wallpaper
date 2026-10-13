package com.chai.earthwall;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Where you are, without GPS: the phone's own network/fused location (approximate is plenty for a globe).
 *  - find(): "Use my location" in Settings. Asks for a fresh fix, gives up after 12 s and falls back to the
 *    newest location any app already got (up to a day old), then names it.
 *  - follow(): "Follow me when I travel". When the wallpaper comes on screen, at most every 30 min, it reads the
 *    location the phone already has cached (no new fix, so no battery cost). If you're 30+ km from home, home
 *    moves to where you are. Needs location "Allow all the time", because the wallpaper isn't an open app.
 */
final class Locate {
    private Locate() {}
    private static final String TAG = "EarthWall";
    private static final long FOLLOW_EVERY = 30 * 60_000L;
    private static final double FOLLOW_KM = 30;

    interface Done { void done(Prefs.City c, String problem); }     // on the main thread; one of the two is null

    static boolean permitted(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean background(Context c) {
        return permitted(c) && (Build.VERSION.SDK_INT < 29
                || c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED);
    }

    static boolean switchedOn(Context c) {
        LocationManager lm = c.getSystemService(LocationManager.class);
        return lm != null && lm.isLocationEnabled();
    }

    /** The newest location the phone already knows, no older than maxAgeMs; null if none. Never starts a fix. */
    static Location lastKnown(Context c, long maxAgeMs) {
        LocationManager lm = c.getSystemService(LocationManager.class);
        if (lm == null || !permitted(c)) return null;
        Location best = null;
        for (String pr : new String[]{LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                if (!lm.hasProvider(pr)) continue;
                Location l = lm.getLastKnownLocation(pr);
                if (l != null && (best == null || l.getElapsedRealtimeNanos() > best.getElapsedRealtimeNanos())) best = l;
            } catch (Exception ignored) { }
        }
        if (best == null) return null;
        long ageMs = (SystemClock.elapsedRealtimeNanos() - best.getElapsedRealtimeNanos()) / 1_000_000L;
        return ageMs <= maxAgeMs ? best : null;
    }

    /** Settings' "Use my location". Caller has checked the permission and that Location is on. */
    static void find(Context ctx, Done d) {
        final Context c = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        final AtomicBoolean over = new AtomicBoolean();
        final CancellationSignal cancel = new CancellationSignal();
        LocationManager lm = c.getSystemService(LocationManager.class);
        String provider = lm.hasProvider(LocationManager.FUSED_PROVIDER) ? LocationManager.FUSED_PROVIDER : LocationManager.NETWORK_PROVIDER;
        Runnable fallback = () -> {
            if (!over.compareAndSet(false, true)) return;
            cancel.cancel();
            Location l = lastKnown(c, 24 * 3_600_000L);
            if (l == null) { d.done(null, "Couldn't get a location fix. Try again near a window or on Wi-Fi."); return; }
            Log.i(TAG, "location: no fresh fix, using one " + (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 60_000_000_000L + " min old");
            named(c, l, d);
        };
        try {
            lm.getCurrentLocation(provider, cancel, c.getMainExecutor(), loc -> {
                if (loc == null) { fallback.run(); return; }
                if (!over.compareAndSet(false, true)) return;
                main.removeCallbacks(fallback);
                named(c, loc, d);
            });
            main.postDelayed(fallback, 12_000);
        } catch (SecurityException e) {
            d.done(null, "Location permission is needed");
        } catch (Exception e) {
            Log.w(TAG, "location request failed", e);
            fallback.run();
        }
    }

    private static void named(Context c, Location l, Done d) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Prefs.City city = new Prefs.City(name(c, l.getLatitude(), l.getLongitude()), l.getLatitude(), l.getLongitude());
            main.post(() -> d.done(city, null));
        }, "earth-locate").start();
    }

    /** Town name for a point (network call: off the main thread). */
    static String name(Context c, double lat, double lon) {
        try {
            List<Address> r = new Geocoder(c, Locale.getDefault()).getFromLocation(lat, lon, 1);
            if (r != null && !r.isEmpty()) {
                Address a = r.get(0);
                if (a.getLocality() != null) return a.getLocality();
                if (a.getSubAdminArea() != null) return a.getSubAdminArea();
                if (a.getAdminArea() != null) return a.getAdminArea();
                if (a.getCountryName() != null) return a.getCountryName();
            }
        } catch (Exception e) { Log.i(TAG, "geocoder: " + e); }
        return String.format(Locale.US, "%.1f°, %.1f°", lat, lon);
    }

    /** Wallpaper came on screen: if following is on and you've moved 30+ km, home moves with you. Cheap. */
    static void follow(Context ctx) {
        final Context c = ctx.getApplicationContext();
        final SharedPreferences p = Prefs.get(c);
        if (!p.getBoolean(Prefs.FOLLOW, false) || !background(c)) return;
        SharedPreferences s = c.getSharedPreferences("follow", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - s.getLong("checked", 0) < FOLLOW_EVERY) return;
        s.edit().putLong("checked", now).apply();
        Location l = lastKnown(c, 6 * 3_600_000L);
        if (l == null) { Log.i(TAG, "follow: no recent location on the phone"); return; }
        Prefs.City home = Prefs.home(p);
        float[] m = new float[1];
        Location.distanceBetween(home.lat, home.lon, l.getLatitude(), l.getLongitude(), m);
        if (m[0] / 1000.0 < FOLLOW_KM) return;
        new Thread(() -> {
            String n = name(c, l.getLatitude(), l.getLongitude());
            Log.i(TAG, String.format(Locale.US, "follow: %.0f km from %s, home is now %s", m[0] / 1000.0, home.name, n));
            Prefs.setHome(p, new Prefs.City(n, l.getLatitude(), l.getLongitude()));
        }, "earth-follow").start();
    }
}
