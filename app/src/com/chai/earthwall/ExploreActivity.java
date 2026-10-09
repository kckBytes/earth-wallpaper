package com.chai.earthwall;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.SimpleTimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Control mode. Opens over the lock screen (Quick Settings tile) and looks like it: the globe sits
 * exactly where the wallpaper puts it. Controls:
 *  - double-tap the globe: zoom in on that spot; again to go closer; at full zoom, back out
 *  - the small button: tap = next city; press and hold (or drag) = joystick to fly the Earth
 * City list, home city, adding cities: Settings.
 */
public class ExploreActivity extends Activity implements SurfaceHolder.Callback {
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences p;
    private GlobeThread gl;
    private TextView title, sub;
    private final List<Prefs.City> cities = new ArrayList<>();
    private int cityIdx;
    private int utcOffsetSec = Integer.MIN_VALUE;
    private String weatherText = "";
    private int weatherSeq;

    private final Runnable clock = new Runnable() {
        @Override public void run() { updateSubtitle(); ui.postDelayed(this, 20_000); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = Prefs.get(this);
        setShowWhenLocked(true);           // like the camera: allowed over the lock screen, no unlock
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        SurfaceView sv = new SurfaceView(this);
        sv.getHolder().addCallback(this);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { gl.zoomAt(e.getX(), e.getY()); return true; }
        });
        sv.setOnTouchListener((v, e) -> gd.onTouchEvent(e));

        // Only the Earth and one button on screen — nothing else (by design). The text views stay
        // detached so the city/weather logic below needs no special cases.
        title = text("", 1, false);
        sub = text("", 1, false);

        // ---- bottom: [home] [-] (next / hold = joystick) [+] [hold = fast-forward time]
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        CityStickButton btn = new CityStickButton(this, new CityStickButton.Listener() {
            @Override public void onTapNext() { nextCity(); }
            @Override public void onStick(float x, float y) { gl.setStick(x, y); }
        });
        row.addView(small("\u2302", v -> { cityIdx = 0; gl.flyTo(cities.get(0), 1); }));        // home
        row.addView(small("\u2212", v -> gl.zoomStep(-1)));                                    // zoom out
        row.addView(btn, new LinearLayout.LayoutParams(dp(150), dp(150)));
        row.addView(small("+", v -> gl.zoomStep(+1)));                                          // zoom in
        TextView time = small("\u25F7", null);                                                 // hold: time-lapse
        time.setOnTouchListener((v, e) -> {
            int a2 = e.getActionMasked();
            if (a2 == MotionEvent.ACTION_DOWN) { v.setPressed(true); gl.setTimeWarp(true); }
            else if (a2 == MotionEvent.ACTION_UP || a2 == MotionEvent.ACTION_CANCEL) { v.setPressed(false); gl.setTimeWarp(false); }
            return true;
        });
        row.addView(time);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-1, dp(150), Gravity.BOTTOM);
        root.addView(row, bl);

        root.setOnApplyWindowInsetsListener((v, in) -> {
            Insets s = in.getInsets(WindowInsets.Type.systemBars());
            bl.bottomMargin = s.bottom + dp(18);
            row.setLayoutParams(bl);
            return WindowInsets.CONSUMED;
        });
        setContentView(root);

        gl = new GlobeThread(this, GlobeThread.MODE_EXPLORE);
        gl.setConfig(Prefs.read(p));
        loadCities();
        show(cities.get(0), false);
    }

    @Override protected void onResume() {
        super.onResume();
        gl.setConfig(Prefs.read(p));
        gl.setVisible(true, false);
        CloudUpdater.maybeUpdate(this, false);
        clock.run();
    }

    @Override protected void onPause() {
        gl.setVisible(false, false);
        ui.removeCallbacks(clock);
        super.onPause();
    }

    @Override protected void onDestroy() {
        gl.shutdown();
        net.shutdown();
        super.onDestroy();
    }

    @Override public void surfaceCreated(SurfaceHolder h) { gl.surfaceCreated(h); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { gl.surfaceChanged(w, hh); }
    @Override public void surfaceDestroyed(SurfaceHolder h) { gl.surfaceDestroyedAndWait(); }

    // ------------------------------------------------------------------ cities
    /** Home city first, then the saved list (no duplicates). */
    private void loadCities() {
        cities.clear();
        cities.add(Prefs.home(p));
        for (Prefs.City c : Prefs.presets(p)) {
            boolean dup = false;
            for (Prefs.City d : cities) if (Math.abs(d.lat - c.lat) < 1e-3 && Math.abs(d.lon - c.lon) < 1e-3) dup = true;
            if (!dup) cities.add(c);
        }
        cityIdx = 0;
    }

    private void nextCity() {
        cityIdx = (cityIdx + 1) % cities.size();
        show(cities.get(cityIdx), true);
    }

    private void show(Prefs.City c, boolean fly) {
        gl.flyTo(c, fly ? 0 : 1);             // 0 = keep the current zoom while hopping
        title.setText(c.name);
        utcOffsetSec = Integer.MIN_VALUE;
        weatherText = "";
    }

    private void openSettings() {
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km != null && km.isKeyguardLocked()) {            // settings need an unlocked phone
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override public void onDismissSucceeded() { startActivity(new Intent(ExploreActivity.this, SettingsActivity.class)); }
            });
        } else startActivity(new Intent(this, SettingsActivity.class));
    }

    // ------------------------------------------------------------------ weather + clock (Open-Meteo, free, no key)
    private void fetchWeather(Prefs.City c) {
        final int seq = ++weatherSeq;
        final boolean f = "US".equals(Locale.getDefault().getCountry());
        net.execute(() -> {
            try {
                String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,weather_code&timezone=auto%s", c.lat, c.lon,
                        f ? "&temperature_unit=fahrenheit" : "");
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (InputStream in = conn.getInputStream()) {
                    byte[] buf = new byte[4096]; int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                }
                JSONObject j = new JSONObject(bo.toString("UTF-8"));
                JSONObject cur = j.getJSONObject("current");
                int off = j.getInt("utc_offset_seconds");
                String w = Math.round(cur.getDouble("temperature_2m")) + (f ? "°F" : "°C") + " · "
                        + describe(cur.getInt("weather_code"));
                ui.post(() -> { if (seq == weatherSeq) { utcOffsetSec = off; weatherText = w; updateSubtitle(); } });
            } catch (Exception e) {
                ui.post(() -> { if (seq == weatherSeq) { weatherText = ""; updateSubtitle(); } });
            }
        });
    }

    private void updateSubtitle() {
        if (sub == null) return;
        String time = "";
        if (utcOffsetSec != Integer.MIN_VALUE) {
            SimpleDateFormat fmt = new SimpleDateFormat("h:mm a", Locale.getDefault());
            fmt.setTimeZone(new SimpleTimeZone(utcOffsetSec * 1000, "local"));
            time = fmt.format(new Date());
        }
        String s = time.isEmpty() ? weatherText : (weatherText.isEmpty() ? time : time + " · " + weatherText);
        sub.setText(s);
    }

    private static String describe(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorms";
        return "";
    }

    // ------------------------------------------------------------------ small UI kit
    /** Small round control (46dp) with a glyph. */
    private TextView small(String glyph, android.view.View.OnClickListener click) {
        TextView t = text(glyph, 20, false);
        t.setShadowLayer(0, 0, 0, 0);
        t.setGravity(Gravity.CENTER);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed}, circle(0x55FFFFFF));
        bg.addState(new int[]{}, circle(0x26FFFFFF));
        t.setBackground(bg);
        if (click != null) t.setOnClickListener(click);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(dp(46), dp(46));
        l.leftMargin = dp(6); l.rightMargin = dp(6);
        t.setLayoutParams(l);
        return t;
    }

    private android.graphics.drawable.GradientDrawable circle(int color) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(dp(1), 0x55FFFFFF);
        return g;
    }
    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setShadowLayer(dp(6), 0, 0, 0xCC000000);
        return t;
    }
}
