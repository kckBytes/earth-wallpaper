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
    private TextView status, city, update;
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

        // tap-to-update (golden app only): shows when a newer build is published
        update = text("", 15, true);
        update.setTextColor(0xFF7FC8FF);
        update.setVisibility(android.view.View.GONE);
        update.setOnClickListener(v -> startActivity(new android.content.Intent(this, UpdateActivity.class)));
        col.addView(update, lp(dp(10)));
        if (Updater.enabled(this) && android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 41);

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
        Button vent = new Button(this);
        vent.setText("Open Ventusky here (wind, rain, temperature)");
        vent.setOnClickListener(v -> {                         // their live site, centred on the home city
            Prefs.City h = Prefs.home(Prefs.get(this));
            String url = String.format(java.util.Locale.US, "https://www.ventusky.com/?p=%.2f;%.2f;5", h.lat, h.lon);
            try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
            catch (Exception e) { android.widget.Toast.makeText(this, "No browser found", android.widget.Toast.LENGTH_SHORT).show(); }
        });
        col.addView(vent, lp(dp(4)));
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

        // ---------------- Layers: base Earth + weather overlay + motion + labels + dots, all independent
        header("Layers");
        TextView lh = text("Stack them like a map app: pick the Earth underneath, tint it with a weather layer, "
                + "and choose exactly which labels and glows appear.", 12, false);
        lh.setAlpha(0.6f);
        col.addView(lh, lp(dp(2)));

        sub("1 · Base Earth");
        RadioGroup views = new RadioGroup(this);
        String[] vn = {"Natural — true colour, live clouds", "Infrared — cloud-top temperature, day and night",
                "Sea temperature — today's ocean surface", "Night lights — the whole planet after dark"};
        for (int i = 0; i < vn.length; i++) views.addView(radio(vn[i], 100 + i));
        views.check(100 + Math.max(0, Math.min(3, p.getInt(Prefs.VIEW, 0))));
        views.setOnCheckedChangeListener((g, id) -> p.edit().putInt(Prefs.VIEW, id - 100).apply());
        col.addView(views, lp(dp(4)));

        sub("2 · Weather on top (NOAA model, every 3 h)");
        RadioGroup wx = new RadioGroup(this);
        wx.addView(radio("None", 299));
        String[] wn = {"Temperature", "Feels like", "Rain & snow", "Wind speed", "Wind gusts", "Air pressure",
                "Thunderstorms", "Humidity", "Snow cover", "Waves"};
        for (int i = 0; i < wn.length; i++) wx.addView(radio(wn[i], 310 + i));
        int w0 = p.getInt(Prefs.WX, Prefs.DEF_WX);
        wx.check(w0 >= 10 ? 300 + w0 : 299);
        wx.setOnCheckedChangeListener((g, id) -> p.edit().putInt(Prefs.WX, id == 299 ? -1 : id - 300).apply());
        col.addView(wx, lp(dp(4)));
        RadioGroup style = new RadioGroup(this);
        style.addView(radio("On the real Earth — keeps clouds, sunlight and night", 400));
        style.addView(radio("Weather map — full colour, like Ventusky", 401));
        style.check(400 + p.getInt(Prefs.WX_STYLE, 0));
        style.setOnCheckedChangeListener((g, id) -> p.edit().putInt(Prefs.WX_STYLE, id - 400).apply());
        col.addView(style, lp(dp(8)));
        slider("Weather strength", Prefs.WX_AMT, Prefs.DEF_WX_AMT, 10, 100, 0.01f, "%");
        TextView sh = text("On the real Earth: how strongly the colours show through the gaps in the clouds. "
                + "Weather map: how much of the map covers the Earth (100% = the map alone).", 12, false);
        sh.setAlpha(0.6f);
        col.addView(sh, lp(dp(2)));

        sub("3 · Motion");
        toggle("Wind streaks — on any Earth, with or without a weather layer", Prefs.STREAKS, true);
        col.addView(text("Streak look (tune by eye on the globe; day = sunlit side, night = dark side)", 12, false), lp(dp(10)));
        sliderF("Day width", Prefs.S_DAY_W, Prefs.DEF_S_DAY_W, 1f, 7f, 0.1f, " px");
        sliderF("Day darkness", Prefs.S_DARK, Prefs.DEF_S_DARK, 0f, 1f, 0.05f, "");
        sliderF("Day outline width", Prefs.S_OUT_W, Prefs.DEF_S_OUT_W, 0f, 3f, 0.1f, " px");
        sliderF("Day outline strength", Prefs.S_OUT_A, Prefs.DEF_S_OUT_A, 0f, 1f, 0.05f, "");
        sliderF("Night width", Prefs.S_NIGHT_W, Prefs.DEF_S_NIGHT_W, 0.8f, 4f, 0.1f, " px");

        sub("4 · Labels");
        toggle("Country borders & coastlines", Prefs.BORDERS, true);
        bit("Hurricanes & tropical storms", Prefs.L_STORMS);
        bit("Highs & lows (H / L)", Prefs.L_SYSTEMS);
        bit("Earthquakes", Prefs.L_QUAKES);
        bit("Volcanic eruptions", Prefs.L_VOLCANOES);
        bit("Wildfires (named)", Prefs.L_FIRES);
        bit("Floods", Prefs.L_FLOODS);
        bit("Rocket launches", Prefs.L_LAUNCHES);
        bit("Space stations & Hubble", Prefs.L_STATIONS);
        bit("Weather satellites that take the cloud pictures", Prefs.L_CAMERAS);

        sub("5 · Dots & glows");
        bit("Lightning flashes", Prefs.D_LIGHTNING);
        bit("Fire hotspots (glowing dots)", Prefs.D_FIRES);
        bit("Aurora on the night side", Prefs.D_AURORA);

        TextView vHint = text("Infrared and sea temperature come from the live 5-satellite map. In infrared, white is cold "
                + "high cloud and colours mark the coldest storm tops.", 12, false);
        vHint.setAlpha(0.6f);
        col.addView(vHint, lp(dp(8)));

        header("Replay (back triple-tap)");
        steps("Replay the last", Prefs.REPLAY_H, Prefs.REPLAY_CHOICES, 12, " h");
        TextView rHint = text("Plays the real satellite record of the past hours: clouds forming, drifting, dissolving. "
                + "The phone keeps only that many hours of frames (about 0.4 MB per half hour) and fetches the past day on Wi-Fi.", 12, false);
        rHint.setAlpha(0.6f);
        col.addView(rHint, lp(dp(2)));

        header("Clouds");
        toggle("Live clouds: 5 satellites, new map every 10 min (off = 3-hourly map)", Prefs.LIVE_CLOUDS, true);
        toggle("Full-resolution clouds on Wi-Fi only", Prefs.HD_WIFI_ONLY, true);
        Button refresh = new Button(this);
        refresh.setText("Refresh clouds now");
        refresh.setOnClickListener(v -> {
            p.edit().putLong(Prefs.REFRESH, System.currentTimeMillis()).apply();
            Toast.makeText(this, "Fetching the latest cloud map…", Toast.LENGTH_SHORT).show();
        });
        col.addView(refresh, lp(dp(8)));

        TextView credits = text("Sun position computed live. Live clouds: GOES-East, GOES-West, Himawari (NASA GIBS) and "
                + "Meteosat MTG + Indian Ocean (EUMETSAT) stitched every 10 min at github.com/kckBytes/earth-live, with "
                + "GHRSST sea-surface temperature and SRTM elevation. Classic: clouds.matteason.co.uk (CC0). "
                + "Contains modified EUMETSAT data. "
                + "Surface: NASA Blue Marble (monthly) and Black Marble city lights.", 12, false);
        credits.setAlpha(0.55f);
        col.addView(credits, lp(dp(28)));
    }

    @Override protected void onResume() {
        super.onResume();
        city.setText("Lock screen centres on: " + Prefs.home(p).name);
        showUpdate();
        if (Updater.enabled(this)) { Updater.check(this, true); update.postDelayed(this::showUpdate, 4000); }
        buildCityList();
        long t = p.getLong(Prefs.CLOUDS_TIME, 0);
        status.setText(t == 0 ? "Clouds: bundled snapshot (updates once the wallpaper is visible)"
                : "Latest satellite cloud map: " + DateUtils.getRelativeTimeSpanString(t).toString().toLowerCase());
    }

    private void showUpdate() {
        Updater.Info u = Updater.pending(this);
        if (u == null) { update.setVisibility(android.view.View.GONE); return; }
        update.setText("Update ready: Earth " + u.name + "  ›  tap to install");
        update.setVisibility(android.view.View.VISIBLE);
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
                            // up to 5 matches, each shown with its region and country, and you pick: a loose spelling
                            // used to silently take the first guess (and label it with what you typed)
                            List<Address> r = new Geocoder(this, Locale.getDefault()).getFromLocationName(q, 5);
                            if (r == null || r.isEmpty()) { toast("Couldn't find " + q); return; }
                            String[] lines = new String[r.size()];
                            Prefs.City[] picks = new Prefs.City[r.size()];
                            for (int i = 0; i < r.size(); i++) {
                                Address a = r.get(i);
                                String name = a.getLocality() != null ? a.getLocality()
                                        : a.getFeatureName() != null ? a.getFeatureName() : q;
                                StringBuilder where = new StringBuilder(name);
                                if (a.getAdminArea() != null && !a.getAdminArea().equals(name)) where.append(", ").append(a.getAdminArea());
                                if (a.getCountryName() != null) where.append(", ").append(a.getCountryName());
                                lines[i] = where + String.format(Locale.US, "\n%.2f°, %.2f°", a.getLatitude(), a.getLongitude());
                                picks[i] = new Prefs.City(name, a.getLatitude(), a.getLongitude());
                            }
                            runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Which one?")
                                    .setItems(lines, (dd, which) -> addCityToList(picks[which]))
                                    .setNegativeButton("None of these", null).show());
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

    private void sub(String s) {
        TextView t = text(s, 13, true);
        t.setAlpha(0.7f);
        col.addView(t, lp(dp(18)));
    }

    /** One switch per label / dot category, stored together as bits of Prefs.MASK. */
    private void bit(String label, int bit) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setChecked((p.getInt(Prefs.MASK, Prefs.MASK_ALL) & bit) != 0);
        s.setOnCheckedChangeListener((v, on) -> {
            int m = p.getInt(Prefs.MASK, Prefs.MASK_ALL);
            p.edit().putInt(Prefs.MASK, on ? (m | bit) : (m & ~bit)).apply();
        });
        col.addView(s, lp(dp(6)));
    }

    private void toggle(String label, String key, boolean def) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setChecked(p.getBoolean(key, def));
        s.setOnCheckedChangeListener((v, on) -> p.edit().putBoolean(key, on).apply());
        col.addView(s, lp(dp(10)));
    }

    /** Seek bar over a fixed list of values (stored as int). */
    private void steps(String label, String key, int[] values, int def, String suffix) {
        TextView t = text("", 14, false);
        col.addView(t, lp(dp(14)));
        SeekBar sb = new SeekBar(this);
        sb.setMax(values.length - 1);
        int v0 = p.getInt(key, def), idx = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == v0) idx = i;
        sb.setProgress(idx);
        t.setText(label + " " + values[idx] + suffix);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int i, boolean user) {
                t.setText(label + " " + values[i] + suffix);
                if (user) p.edit().putInt(key, values[i]).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        col.addView(sb, lp(0));
    }

    /** Integer seek bar over [min, max]; stored value = progress * unit (so % sliders store 0.7..1.3). */
    /** A slider over a float range, showing the real value (e.g. "Day width: 3.5 px"). */
    private void sliderF(String label, String key, float def, float min, float max, float step, String suffix) {
        TextView t = text("", 14, false);
        col.addView(t, lp(dp(8)));
        SeekBar sb = new SeekBar(this);
        int steps = Math.round((max - min) / step);
        sb.setMax(steps);
        float cur = p.getFloat(key, def);
        sb.setProgress(Math.round((cur - min) / step));
        t.setText(label + ": " + String.format(java.util.Locale.US, step < 0.1f ? "%.2f" : "%.1f", cur) + suffix);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                float val = min + v * step;
                t.setText(label + ": " + String.format(java.util.Locale.US, step < 0.1f ? "%.2f" : "%.1f", val) + suffix);
                if (user) p.edit().putFloat(key, val).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        col.addView(sb, lp(0));
    }

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
