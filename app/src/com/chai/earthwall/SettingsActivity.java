package com.chai.earthwall;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.LocationManager;
import android.text.InputType;
import android.widget.EditText;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Launcher entry + wallpaper settings. Built in code: no layouts, no AndroidX. */
public class SettingsActivity extends Activity {
    private SharedPreferences p;
    private LinearLayout col;
    private TextView status, city;
    private LinearLayout cityList;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private static final int REQ_LOCATION = 7;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = Prefs.get(this);
        ScrollView scroll = new ScrollView(this);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        col.setPadding(pad, pad, pad, pad);
        scroll.addView(col);
        scroll.setOnApplyWindowInsetsListener((v, in) -> {
            Insets s = in.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(s.left, s.top, s.right, s.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(scroll);

        TextView title = text("Earth", 30, true);
        col.addView(title);
        status = text("", 14, false);
        status.setAlpha(0.7f);
        col.addView(status);

        Button set = new Button(this);
        set.setText("Set as wallpaper");
        set.setOnClickListener(v -> openPicker());
        col.addView(set, lp(dp(16)));

        header("Cities");
        city = text("", 15, false);
        col.addView(city, lp(dp(6)));
        TextView cityHint = text("Tap a city to centre the lock screen on it; long-press to remove. "
                + "The \u203A button in control mode cycles through this list.", 12, false);
        cityHint.setAlpha(0.6f);
        col.addView(cityHint, lp(dp(2)));
        cityList = new LinearLayout(this);
        cityList.setOrientation(LinearLayout.VERTICAL);
        col.addView(cityList, lp(dp(6)));
        LinearLayout row = new LinearLayout(this);
        Button add = new Button(this);
        add.setText("+ Add city");
        add.setOnClickListener(v -> addCity());
        Button mine = new Button(this);
        mine.setText("Use my location");
        mine.setOnClickListener(v -> myLocation());
        row.addView(add, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(mine, new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(row, lp(dp(4)));
        Button control = new Button(this);
        control.setText("Open control mode");
        control.setOnClickListener(v -> startActivity(new Intent(this, ExploreActivity.class)));
        col.addView(control, lp(dp(4)));
        TextView qsHint = text("Tip: add the \u201CEarth\u201D Quick Settings tile (swipe down twice \u2192 pencil) to open "
                + "control mode straight from the lock screen, no unlock.", 12, false);
        qsHint.setAlpha(0.6f);
        col.addView(qsHint, lp(dp(4)));

        header("View");
        toggle("Mark my city", Prefs.MARKER, true);
        slider("Globe size", Prefs.SIZE, 1f, 70, 130, 0.01f, "%");
        slider("Height on screen", Prefs.HEIGHT, Prefs.DEF_HEIGHT, 10, 60, 0.01f, "%");

        RadioGroup comp = new RadioGroup(this);
        RadioButton disk = radio("Full disk — geostationary view", 1);
        RadioButton hor = radio("Horizon — Earth rising from the bottom", 2);
        comp.addView(disk);
        comp.addView(hor);
        comp.check(p.getBoolean(Prefs.HORIZON, false) ? 2 : 1);
        comp.setOnCheckedChangeListener((g, id) -> p.edit().putBoolean(Prefs.HORIZON, id == 2).apply());
        col.addView(comp, lp(dp(8)));

        header("Motion");
        toggle("Slow drift after waking", Prefs.MOTION, false);
        toggle("Flip and flip back to change city (lock screen)", Prefs.FLIP, true);
        toggle("Double-tap the back to zoom (lock screen)", Prefs.KNOCK, true);
        TextView qtHint = text("Hold the phone still for a moment, then double-tap its back: zoom in on the city that's "
                + "showing, again to zoom out. Ignored while walking. Everything resets to home when the screen "
                + "turns off. (Turn Pixel's own Quick Tap off, or it will also react to back taps.)", 12, false);
        qtHint.setAlpha(0.6f);
        col.addView(qtHint, lp(dp(2)));
        toggle("Tilt parallax (gyroscope)", Prefs.PARALLAX, true);
        slider("Tilt sensitivity", Prefs.PARALLAX_GAIN, Prefs.DEF_GAIN, 5, 100, 0.01f, "%");
        toggle("Stars", Prefs.STARS, true);

        header("Clouds");
        toggle("Full-resolution clouds on Wi-Fi only", Prefs.HD_WIFI_ONLY, true);
        Button refresh = new Button(this);
        refresh.setText("Refresh clouds now");
        refresh.setOnClickListener(v -> {
            p.edit().putLong(Prefs.REFRESH, System.currentTimeMillis()).apply();
            Toast.makeText(this, "Fetching the latest cloud map…", Toast.LENGTH_SHORT).show();
        });
        col.addView(refresh, lp(dp(8)));

        TextView credits = text("Sun position computed live. Clouds: global satellite composite refreshed "
                + "every 3 hours, checked every 30 min; clouds drift along their measured motion between maps "
                + "— contains modified EUMETSAT data (clouds.matteason.co.uk, CC0). "
                + "Surface: NASA Blue Marble (monthly) and Black Marble city lights.", 12, false);
        credits.setAlpha(0.55f);
        col.addView(credits, lp(dp(28)));
    }

    @Override protected void onResume() {
        super.onResume();
        city.setText("Lock screen centres on: " + Prefs.home(p).name);
        buildCityList();
        long t = p.getLong(Prefs.CLOUDS_TIME, 0);
        status.setText(t == 0 ? "Clouds: bundled snapshot (updates once the wallpaper is visible)"
                : "Latest satellite cloud map: " + DateUtils.getRelativeTimeSpanString(t).toString().toLowerCase());
    }

    // ------------------------------------------------------------------ cities
    private void buildCityList() {
        cityList.removeAllViews();
        Prefs.City home = Prefs.home(p);
        for (Prefs.City c : Prefs.presets(p)) {
            boolean isHome = Math.abs(c.lat - home.lat) < 1e-3 && Math.abs(c.lon - home.lon) < 1e-3;
            TextView t = text((isHome ? "\u25CF  " : "\u25CB  ") + c.name, 16, false);
            t.setPadding(0, dp(10), 0, dp(10));
            t.setOnClickListener(v -> {
                Prefs.setHome(p, c);
                Toast.makeText(this, "Lock screen now centres on " + c.name, Toast.LENGTH_SHORT).show();
                onResume();
            });
            t.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setMessage("Remove " + c.name + "?")
                        .setPositiveButton("Remove", (d, w) -> {
                            List<Prefs.City> list = Prefs.presets(p);
                            list.removeIf(x -> Math.abs(x.lat - c.lat) < 1e-3 && Math.abs(x.lon - c.lon) < 1e-3);
                            Prefs.savePresets(p, list);
                            buildCityList();
                        }).setNegativeButton("Cancel", null).show();
                return true;
            });
            cityList.addView(t);
        }
    }

    private void addCityToList(Prefs.City c) {
        List<Prefs.City> list = Prefs.presets(p);
        list.add(c);
        Prefs.savePresets(p, list);
        buildCityList();
        Toast.makeText(this, "Added " + c.name, Toast.LENGTH_SHORT).show();
    }

    private void toast(String s) { runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    private void addCity() {
        EditText in = new EditText(this);
        in.setHint("City name");
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        new AlertDialog.Builder(this).setTitle("Add a city").setView(in)
                .setPositiveButton("Add", (d, w) -> {
                    String q = in.getText().toString().trim();
                    if (q.isEmpty()) return;
                    net.execute(() -> {
                        try {
                            List<Address> r = new Geocoder(this, Locale.getDefault()).getFromLocationName(q, 1);
                            if (r == null || r.isEmpty()) { toast("Couldn't find " + q); return; }
                            Address a = r.get(0);
                            Prefs.City c = new Prefs.City(a.getLocality() != null ? a.getLocality() : q, a.getLatitude(), a.getLongitude());
                            runOnUiThread(() -> addCityToList(c));
                        } catch (Exception e) { toast("Search failed — check your connection"); }
                    });
                }).setNegativeButton("Cancel", null).show();
    }

    private void myLocation() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        LocationManager lm = getSystemService(LocationManager.class);
        String provider = lm.hasProvider(LocationManager.FUSED_PROVIDER) ? LocationManager.FUSED_PROVIDER
                : LocationManager.NETWORK_PROVIDER;
        try {
            lm.getCurrentLocation(provider, null, getMainExecutor(), loc -> {
                if (loc == null) { toast("Location unavailable right now"); return; }
                net.execute(() -> {
                    String name = "My location";
                    try {
                        List<Address> r = new Geocoder(this, Locale.getDefault()).getFromLocation(loc.getLatitude(), loc.getLongitude(), 1);
                        if (r != null && !r.isEmpty() && r.get(0).getLocality() != null) name = r.get(0).getLocality();
                    } catch (Exception ignored) { }
                    Prefs.City c = new Prefs.City(name, loc.getLatitude(), loc.getLongitude());
                    runOnUiThread(() -> addCityToList(c));
                });
            });
        } catch (SecurityException e) { toast("Location permission needed"); }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_LOCATION && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) myLocation();
    }

    private void openPicker() {
        Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                new ComponentName(this, EarthWallpaperService.class));
        try { startActivity(i); }
        catch (ActivityNotFoundException e) {
            startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
        }
    }

    // ------------------------------------------------------------------ tiny UI kit
    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        l.topMargin = top;
        return l;
    }

    private TextView text(String s, float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void header(String s) {
        TextView t = text(s, 15, true);
        t.setAlpha(0.8f);
        col.addView(t, lp(dp(28)));
    }

    private RadioButton radio(String s, int id) {
        RadioButton r = new RadioButton(this);
        r.setText(s);
        r.setId(id);
        return r;
    }

    private void toggle(String label, String key, boolean def) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setChecked(p.getBoolean(key, def));
        s.setOnCheckedChangeListener((v, on) -> p.edit().putBoolean(key, on).apply());
        col.addView(s, lp(dp(10)));
    }

    /** Integer seek bar over [min, max]; stored value = progress * unit (so % sliders store 0.7..1.3). */
    private void slider(String label, String key, float def, int min, int max, float unit, String suffix) {
        TextView t = text("", 14, false);
        col.addView(t, lp(dp(14)));
        SeekBar sb = new SeekBar(this);
        sb.setMin(min);
        sb.setMax(max);
        int cur = Math.round(p.getFloat(key, def) / unit);
        sb.setProgress(cur);
        t.setText(label + ": " + cur + suffix);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                t.setText(label + ": " + v + suffix);
                if (user) p.edit().putFloat(key, v * unit).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        col.addView(sb, lp(0));
    }
}
