package com.chai.earthwall;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.Context;
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
    private Button update;
    private LinearLayout setup;
    private boolean homeFromNextAdd;
    static final String SETUP_TILE = "setup_tile", SETUP_CONTROL = "setup_control", SETUP_HIDDEN = "setup_hidden";
    private LinearLayout cityList;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private static final int REQ_LOCATION = 7, REQ_FOLLOW = 8, REQ_FOLLOW_BG = 9;
    private Switch follow;
    private boolean chainFollow, followPending;

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

        // tap-to-update (golden app only): a big red button at the top whenever a newer build is published
        update = new Button(this);
        update.setAllCaps(false);
        update.setTextColor(0xFFFFFFFF);
        update.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17);
        update.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        android.graphics.drawable.GradientDrawable red = new android.graphics.drawable.GradientDrawable();
        red.setColor(0xFFD93025);
        red.setCornerRadius(dp(28));
        update.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x40FFFFFF), red, null));
        update.setPadding(dp(20), dp(14), dp(20), dp(14));
        update.setVisibility(android.view.View.GONE);
        update.setOnClickListener(v -> startActivity(new android.content.Intent(this, UpdateActivity.class)));
        col.addView(update, lp(dp(14)));

        // first-run guide: a checklist that ticks itself off, then folds away
        setup = new LinearLayout(this);
        setup.setOrientation(LinearLayout.VERTICAL);
        col.addView(setup, lp(dp(14)));

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
        follow = new Switch(this);
        follow.setText("Follow me when I travel");
        follow.setChecked(p.getBoolean(Prefs.FOLLOW, false) && Locate.background(this));
        follow.setOnCheckedChangeListener((v, on) -> {
            if (!on) { p.edit().putBoolean(Prefs.FOLLOW, false).apply(); buildSetup(); }
            else if (!followOn(this)) startFollow();
        });
        col.addView(follow, lp(dp(10)));
        TextView followHint = text("Home moves with you: when you're 30+ km away, the lock screen centres on where you are, "
                + "no app open. It only reads the location your phone already has, at most every 30 min, so no extra battery.", 12, false);
        followHint.setAlpha(0.6f);
        col.addView(followHint, lp(dp(2)));
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

        header("Reset");
        Button reset = new Button(this);
        reset.setText("Reset settings to defaults");
        reset.setOnClickListener(v -> confirmReset());
        col.addView(reset, lp(dp(6)));

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
        city.setText("Lock screen centres on: " + Prefs.home(p).name
                + (p.getBoolean(Prefs.FOLLOW, false) && Locate.background(this) ? "  (follows you)" : ""));
        if (followPending && Locate.background(this)) { followPending = false; startFollow(); }
        if (follow != null && follow.isChecked() && !Locate.background(this)) follow.setChecked(false);
        showUpdate();
        buildSetup();
        if (Updater.enabled(this)) { Updater.check(this, true); update.postDelayed(this::showUpdate, 4000); }
        buildCityList();
        long t = p.getLong(Prefs.CLOUDS_TIME, 0);
        status.setText(t == 0 ? "Clouds: bundled snapshot (updates once the wallpaper is visible)"
                : "Latest satellite cloud map: " + DateUtils.getRelativeTimeSpanString(t).toString().toLowerCase());
    }

    private void showUpdate() {
        Updater.Info u = Updater.pending(this);
        if (u == null) { update.setVisibility(android.view.View.GONE); return; }
        update.setText("Update to Earth " + u.name);
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
        boolean dup = false;
        for (Prefs.City x : list) if (Math.abs(x.lat - c.lat) < 0.05 && Math.abs(x.lon - c.lon) < 0.05) dup = true;
        if (!dup) list.add(c);
        Prefs.savePresets(p, list);
        if (homeFromNextAdd) {                                    // from the setup checklist: this is home
            homeFromNextAdd = false;
            Prefs.setHome(p, c);
            Toast.makeText(this, "Home set to " + c.name, Toast.LENGTH_SHORT).show();
            onResume();
            if (chainFollow) { chainFollow = false; if (!followOn(this)) startFollow(); }   // setup: straight on to "all the time"
            return;
        }
        buildCityList();
        Toast.makeText(this, "Added " + c.name, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ reset

    /** Every look / motion / layer setting this screen changes. Downloaded clouds and app updates are kept. */
    private static final String[] SETTINGS = {Prefs.HORIZON, Prefs.SIZE, Prefs.HEIGHT, Prefs.MOTION, Prefs.PARALLAX,
            Prefs.PARALLAX_GAIN, Prefs.MARKER, Prefs.STORMS, Prefs.STREAKS, Prefs.S_DAY_W, Prefs.S_NIGHT_W, Prefs.S_OUT_W,
            Prefs.S_OUT_A, Prefs.S_DARK, Prefs.TWIST, Prefs.FLIP, Prefs.KNOCK, Prefs.STARS, Prefs.HD_WIFI_ONLY,
            Prefs.LIVE_CLOUDS, Prefs.CLOUDS_SRC, Prefs.VIEW, Prefs.WX, Prefs.WX_AMT, Prefs.WX_STYLE, Prefs.BORDERS,
            Prefs.MASK, Prefs.REPLAY_H, Prefs.FOLLOW};

    private void confirmReset() {
        android.widget.CheckBox cities = new android.widget.CheckBox(this);
        cities.setText("Also forget my cities and home");
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(cities);
        new AlertDialog.Builder(this).setTitle("Reset settings?")
                .setMessage("Size, position, motion, layers and streak look go back to how they came. The setup guide shows again.")
                .setView(box)
                .setPositiveButton("Reset", (d, w) -> {
                    SharedPreferences.Editor e = p.edit();
                    for (String k : SETTINGS) e.remove(k);
                    e.remove(SETUP_CONTROL).remove(SETUP_HIDDEN);   // (the tile stays ticked: it's still added)
                    if (cities.isChecked()) e.remove(Prefs.CITY_NAME).remove(Prefs.CITY_LAT).remove(Prefs.CITY_LON).remove(Prefs.PRESETS);
                    e.apply();
                    Toast.makeText(this, "Settings reset", Toast.LENGTH_SHORT).show();
                    recreate();
                }).setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ setup checklist

    private boolean stepHome() { return p.contains(Prefs.CITY_LAT); }
    private boolean stepWallpaper() { return wallpaperSet(this); }
    private boolean stepNotify() { return notifyAllowed(this); }

    /** {done, total} for the setup checklist; also used by the after-update notification. */
    static int[] setupProgress(Context c) {
        SharedPreferences sp = Prefs.get(c);
        boolean notify = Updater.enabled(c);
        boolean[] d = {sp.contains(Prefs.CITY_LAT), followOn(c), wallpaperSet(c), sp.getBoolean(SETUP_TILE, false),
                !notify || notifyAllowed(c), sp.getBoolean(SETUP_CONTROL, false)};
        int n = 0;
        for (int i = 0; i < d.length; i++) if (d[i] && (notify || i != 4)) n++;
        return new int[]{n, notify ? 6 : 5};
    }

    /** Location "Allow all the time" granted and following switched on. */
    static boolean followOn(Context c) {
        return Prefs.get(c).getBoolean(Prefs.FOLLOW, false) && Locate.background(c);
    }

    static boolean notifyAllowed(Context c) {
        return android.os.Build.VERSION.SDK_INT < 33
                || c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean wallpaperSet(Context c) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(c);
            ComponentName me = new ComponentName(c, EarthWallpaperService.class);
            android.app.WallpaperInfo h = wm.getWallpaperInfo();
            if (h != null && me.equals(h.getComponent())) return true;
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                android.app.WallpaperInfo l = wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK);
                if (l != null && me.equals(l.getComponent())) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void buildSetup() {
        setup.removeAllViews();
        boolean notify = Updater.enabled(this);                   // only the shared app gets update notifications
        boolean[] done = {stepHome(), followOn(this), stepWallpaper(), p.getBoolean(SETUP_TILE, false), !notify || stepNotify(), p.getBoolean(SETUP_CONTROL, false)};
        int total = notify ? 6 : 5, n = 0;
        for (int i = 0; i < done.length; i++) if (done[i] && (notify || i != 4)) n++;
        if (n >= total || p.getBoolean(SETUP_HIDDEN, false)) { setup.setVisibility(android.view.View.GONE); return; }
        setup.setVisibility(android.view.View.VISIBLE);
        android.graphics.drawable.GradientDrawable box = new android.graphics.drawable.GradientDrawable();
        box.setCornerRadius(dp(18));
        box.setStroke(dp(1), 0x557FC8FF);
        box.setColor(0x147FC8FF);
        setup.setBackground(box);
        setup.setPadding(dp(16), dp(14), dp(16), dp(10));

        TextView head = text("Set up Earth  ·  " + n + " of " + total + " done", 17, true);
        setup.addView(head);
        TextView sub = text((total == 6 ? "Six" : "Five") + " quick steps so it works the way it's meant to.", 13, false);
        sub.setAlpha(0.7f);
        setup.addView(sub);

        Prefs.City home = Prefs.home(p);
        setupRow(done[0], "Your home city", done[0] ? "Centred on " + home.name : "The globe turns to face it every time the screen wakes",
                "Use my location", v -> { homeFromNextAdd = true; chainFollow = true; myLocation(); });
        setupRow(done[1], "Location: Allow all the time", done[1] ? "Home follows you when you travel"
                        : "So the globe follows you when you travel, with no app open. Choose \u201CAllow all the time\u201D",
                "Allow", v -> startFollow());
        setupRow(done[2], "Set it as your wallpaper", "Lock screen (and home screen if you like)",
                "Set", v -> openPicker());
        setupRow(done[3], "Add the Earth tile", "Swipe down on the lock screen, tap Earth: zoom, cities, time-lapse, no unlock",
                "Add", v -> addTile());
        if (notify) setupRow(done[4], "Allow update alerts", "So you hear when a new version is out",
                "Allow", v -> requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 41));
        setupRow(done[5], "Try control mode", "Buttons for zoom, next city, and hold ◷ to fast-forward the clouds",
                "Try", v -> { p.edit().putBoolean(SETUP_CONTROL, true).apply(); startActivity(new Intent(this, ExploreActivity.class)); });

        TextView skip = text("Hide this", 13, false);
        skip.setAlpha(0.6f);
        skip.setPadding(0, dp(8), 0, dp(4));
        skip.setOnClickListener(v -> { p.edit().putBoolean(SETUP_HIDDEN, true).apply(); buildSetup(); });
        setup.addView(skip);
    }

    private void setupRow(boolean ok, String title, String hint, String action, android.view.View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(10), 0, 0);
        TextView mark = text(ok ? "✓" : "○", 18, true);
        mark.setTextColor(ok ? 0xFF34A853 : 0xFF7FC8FF);
        mark.setMinWidth(dp(28));
        r.addView(mark);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 15, true);
        TextView h = text(hint, 12, false);
        h.setAlpha(0.7f);
        words.addView(t);
        words.addView(h);
        r.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        if (!ok) {
            Button b = new Button(this);
            b.setText(action);
            b.setAllCaps(false);
            b.setOnClickListener(l);
            r.addView(b);
        } else r.setAlpha(0.55f);
        setup.addView(r);
    }

    /** Android 13+: the system's own one-tap "Add tile?" pop-up. Older: how to do it by hand. */
    private void addTile() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                getSystemService(android.app.StatusBarManager.class).requestAddTileService(
                        new ComponentName(this, EarthTileService.class), "Earth",
                        android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile), getMainExecutor(), res -> {
                            if (res == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                                    || res == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                                p.edit().putBoolean(SETUP_TILE, true).apply();
                                buildSetup();
                            }
                        });
                return;
            } catch (Exception ignored) { }
        }
        new AlertDialog.Builder(this).setTitle("Add the Earth tile")
                .setMessage("Swipe down twice from the top → tap the pencil → drag \u201CEarth\u201D into your tiles.")
                .setPositiveButton("Done", (d, w) -> { p.edit().putBoolean(SETUP_TILE, true).apply(); buildSetup(); })
                .setNegativeButton("Later", null).show();
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

    /** "Use my location": permission, then Location switched on, then a fix (12 s max, else the newest one the phone has). */
    private void myLocation() {
        if (!Locate.permitted(this)) {
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        if (!Locate.switchedOn(this)) { locationOff(); return; }
        Toast.makeText(this, "Finding you\u2026", Toast.LENGTH_SHORT).show();
        Locate.find(this, (c, problem) -> {
            if (isFinishing() || isDestroyed()) return;
            if (c == null) { homeFromNextAdd = false; Toast.makeText(this, problem, Toast.LENGTH_LONG).show(); return; }
            if (homeFromNextAdd) { addCityToList(c); return; }                // setup checklist: straight to home
            new AlertDialog.Builder(this).setTitle("You're in " + c.name)
                    .setMessage(String.format(Locale.US, "%.2f\u00B0, %.2f\u00B0", c.lat, c.lon))
                    .setPositiveButton("Make it home", (d, w) -> { homeFromNextAdd = true; addCityToList(c); })
                    .setNeutralButton("Just add to list", (d, w) -> addCityToList(c))
                    .setNegativeButton("Cancel", null).show();
        });
    }

    private void locationOff() {
        homeFromNextAdd = false;
        new AlertDialog.Builder(this).setTitle("Location is off")
                .setMessage("Turn on Location for the phone, then tap Use my location again.")
                .setPositiveButton("Open Location settings", (d, w) -> {
                    try { startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)); } catch (Exception ignored) { }
                }).setNegativeButton("Cancel", null).show();
    }

    /** Android only asks twice; after that the switch lives in the app's settings page. */
    private void permissionBlocked(String what) {
        new AlertDialog.Builder(this).setTitle("Location permission needed")
                .setMessage("Android won't ask again. In the next screen tap Permissions \u2192 Location \u2192 " + what + ".")
                .setPositiveButton("Open app settings", (d, w) -> startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + getPackageName()))))
                .setNegativeButton("Cancel", null).show();
    }

    /** "Follow me": approximate location first, then "Allow all the time" (Android shows that one as a settings page). */
    private void startFollow() {
        if (!Locate.permitted(this)) {
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_FOLLOW);
            return;
        }
        if (!Locate.background(this)) {
            new AlertDialog.Builder(this).setTitle("One more step")
                    .setMessage("So it works without the app open, Android needs location set to \u201CAllow all the time\u201D. "
                            + "Pick that on the next screen, then come back.")
                    .setPositiveButton("Continue", (d, w) -> requestPermissions(
                            new String[]{android.Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_FOLLOW_BG))
                    .setNegativeButton("Not now", (d, w) -> follow.setChecked(false))
                    .setOnCancelListener(d -> follow.setChecked(false))
                    .show();
            return;
        }
        p.edit().putBoolean(Prefs.FOLLOW, true).apply();
        getSharedPreferences("follow", MODE_PRIVATE).edit().remove("checked").apply();   // check on the next wake
        if (follow != null && !follow.isChecked()) follow.setChecked(true);
        buildSetup();
        if (!Locate.switchedOn(this)) Toast.makeText(this, "Following is on. Turn on Location so it can work.", Toast.LENGTH_LONG).show();
        else Toast.makeText(this, "Following is on", Toast.LENGTH_SHORT).show();
    }

    /** Anything short of "Allow all the time" (while using, only this time, don't allow): the app's own page, where it's one tap. */
    private void allTheTimeInSettings() {
        new AlertDialog.Builder(this).setTitle("Earth needs \u201CAllow all the time\u201D")
                .setMessage("It only uses the location your phone already has, at most every 30 min, so it costs no battery. "
                        + "On the next screen: Permissions \u2192 Location \u2192 Allow all the time.")
                .setPositiveButton("Open settings", (d, w) -> {
                    followPending = true;
                    startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + getPackageName())));
                })
                .setNegativeButton("Not now", null).show();
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        if (req == 41) buildSetup();
        if (req == REQ_LOCATION) {
            if (ok) myLocation();
            else if (!shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_COARSE_LOCATION)) permissionBlocked("Allow only while using the app");
            else homeFromNextAdd = false;
        }
        if (req == REQ_FOLLOW) {
            if (ok) startFollow();
            else { follow.setChecked(false); allTheTimeInSettings(); }
        }
        if (req == REQ_FOLLOW_BG) {
            if (Locate.background(this)) startFollow();
            else { follow.setChecked(false); allTheTimeInSettings(); }
        }
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
