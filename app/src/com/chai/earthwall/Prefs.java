package com.chai.earthwall;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Prefs {
    private Prefs() {}

    static final String NAME = "earth";
    static final String CITY_NAME = "city_name";
    static final String CITY_LAT = "city_lat";
    static final String CITY_LON = "city_lon";
    static final String PRESETS = "presets";
    static final String HORIZON = "horizon";
    static final String SIZE = "size";
    static final String HEIGHT = "height";
    static final String MOTION = "motion";
    static final String PARALLAX = "parallax";
    static final String PARALLAX_GAIN = "parallax_gain";
    static final String MARKER = "marker";
    static final String STORMS = "storm_labels";
    static final String STREAKS = "wind_streaks";
    static final String TWIST = "twist";
    static final String FLIP = "flip";
    static final String KNOCK = "knock_zoom";
    static final String STARS = "stars";
    static final String HD_WIFI_ONLY = "hd_wifi_only";
    static final String LIVE_CLOUDS = "live_clouds";   // true: 5-satellite stitch every 10 min (earth-live); false: 3-hourly composite
    static final String CLOUDS_SRC = "clouds_src";
    static final String VIEW = "view_mode";             // base Earth: 0 natural, 1 infrared, 2 sea temperature, 3 night lights
    // Earth Next: layers stacked on the base
    static final String WX = "wx_layer";                // weather overlay: -1 none, 10..19 = temperature .. waves
    static final String WX_AMT = "wx_amount";
    static final String WX_STYLE = "wx_style";          // 0 on the real Earth, 1 weather map           // overlay strength 0..1 (1 = full Ventusky-style map)
    static final String BORDERS = "borders";            // country borders + coastlines as thin light lines
    static final String MASK = "layer_mask";            // which labels / dots are shown (bits below)
    static final int L_STORMS = 1, L_SYSTEMS = 2, L_QUAKES = 4, L_VOLCANOES = 8, L_FIRES = 16, L_FLOODS = 32,
            L_LAUNCHES = 64, L_STATIONS = 128, L_CAMERAS = 256, D_LIGHTNING = 512, D_FIRES = 1024, D_AURORA = 2048;
    static final int MASK_ALL = 4095;
    static final int DEF_WX = 10;                       // first look: temperature on the real Earth
    static final float DEF_WX_AMT = 0.6f;

    /** Label category for a storms.json / sats / cameras entry. */
    static boolean labelOn(int mask, String type) {
        int bit;
        switch (type) {
            case "storm": bit = L_STORMS; break;
            case "low": case "high": bit = L_SYSTEMS; break;
            case "quake": bit = L_QUAKES; break;
            case "volcano": bit = L_VOLCANOES; break;
            case "fire": bit = L_FIRES; break;
            case "flood": bit = L_FLOODS; break;
            case "launch": bit = L_LAUNCHES; break;
            case "sat": bit = L_STATIONS; break;
            case "camera": bit = L_CAMERAS; break;
            default: bit = L_STORMS;
        }
        return (mask & bit) != 0;
    }

    /** Which weather data the phone must keep: the overlay's sheet, or just the wind for streaks; -1 = none. */
    static int wxId(int wx, boolean streaks) { return wx >= 10 ? wx : streaks ? 13 : -1; }
    static int wxId(SharedPreferences p) { return wxId(p.getInt(WX, DEF_WX), p.getBoolean(STREAKS, true)); }
    static final String REPLAY_H = "replay_hours";      // back triple-tap replay length
    static final int[] REPLAY_CHOICES = {3, 6, 9, 12, 18, 24};
    static final String REFRESH = "refresh_token";

    // written by CloudUpdater
    static final String CLOUDS_CHECKED = "clouds_checked";
    static final String CLOUDS_TIME = "clouds_time";
    static final String CLOUDS_PREV_TIME = "clouds_prev_time";
    static final String CLOUDS_ETAG = "clouds_etag";
    static final String CLOUDS_RES = "clouds_res";

    static final float DEF_HEIGHT = 0.44f;     // globe centre, fraction of screen height from the bottom
    static final float DEF_GAIN = 0.4f;

    static final City HOME_DEFAULT = new City("Raleigh", 35.7796, -78.6382);
    private static final String DEFAULT_PRESETS =
            "Raleigh|35.7796|-78.6382;San Diego|32.7157|-117.1611;New York|40.7128|-74.0060;"
            + "London|51.5074|-0.1278;Tokyo|35.6762|139.6503;Sydney|-33.8688|151.2093";

    static final class City {
        final String name; final double lat, lon;
        City(String n, double la, double lo) { name = n; lat = la; lon = lo; }
        String encode() { return name.replace("|", " ").replace(";", " ") + "|" + String.format(Locale.US, "%.4f|%.4f", lat, lon); }
        static City decode(String s) {
            String[] p = s.split("\\|");
            if (p.length != 3) return null;
            try { return new City(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2])); }
            catch (NumberFormatException e) { return null; }
        }
    }

    static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** Which data map a view needs: c = clouds, bt = infrared temperature, sst = sea-surface temperature. */
    static String kind(int view) { return view == 1 ? "bt" : view == 2 ? "sst" : "c"; }   // weather views (10+) keep the clouds

    static final class Config {
        City city = HOME_DEFAULT;
        float size = 1f, height = DEF_HEIGHT, gain = DEF_GAIN;
        boolean horizon, motion, parallax = true, marker = true, stars = true, storms = true, streaks = true;
        int view = 0, replayH = 12;
        int wx = DEF_WX, mask = MASK_ALL; float wxAmt = DEF_WX_AMT; boolean borders = true; int wxStyle = 0;
    }

    static Config read(SharedPreferences p) {
        Config c = new Config();
        c.city = home(p);
        c.size = p.getFloat(SIZE, 1f);
        // One-time: the default moved up ~1 cm (0.37 -> 0.44). If the slider was ever touched, move
        // the saved value up by the same 1 cm so the change applies either way.
        if (!p.getBoolean("height_up_1cm_b", false)) {
            SharedPreferences.Editor e = p.edit().putBoolean("height_up_1cm_b", true);
            if (p.contains(HEIGHT)) e.putFloat(HEIGHT, Math.min(0.60f, p.getFloat(HEIGHT, DEF_HEIGHT) + 0.068f));
            e.commit();
        }
        c.height = p.getFloat(HEIGHT, DEF_HEIGHT);
        c.gain = p.getFloat(PARALLAX_GAIN, DEF_GAIN);
        c.horizon = p.getBoolean(HORIZON, false);
        c.motion = p.getBoolean(MOTION, false);
        c.parallax = p.getBoolean(PARALLAX, true);
        c.marker = p.getBoolean(MARKER, true);
        c.storms = p.getBoolean(STORMS, true);
        c.streaks = p.getBoolean(STREAKS, true);
        c.stars = p.getBoolean(STARS, true);
        c.view = Math.max(0, Math.min(3, p.getInt(VIEW, 0)));
        c.wx = p.getInt(WX, DEF_WX);
        c.wxAmt = p.getFloat(WX_AMT, DEF_WX_AMT);
        c.mask = p.getInt(MASK, MASK_ALL);
        c.borders = p.getBoolean(BORDERS, true);
        c.wxStyle = p.getInt(WX_STYLE, 0);
        c.storms = (c.mask & 511) != 0;
        c.replayH = p.getInt(REPLAY_H, 12);
        return c;
    }

    static City home(SharedPreferences p) {
        if (!p.contains(CITY_LAT)) return HOME_DEFAULT;
        return new City(p.getString(CITY_NAME, "Home"),
                Double.longBitsToDouble(p.getLong(CITY_LAT, 0)), Double.longBitsToDouble(p.getLong(CITY_LON, 0)));
    }

    static void setHome(SharedPreferences p, City c) {
        p.edit().putString(CITY_NAME, c.name)
                .putLong(CITY_LAT, Double.doubleToLongBits(c.lat))
                .putLong(CITY_LON, Double.doubleToLongBits(c.lon)).apply();
    }

    static List<City> presets(SharedPreferences p) {
        List<City> out = new ArrayList<>();
        for (String s : p.getString(PRESETS, DEFAULT_PRESETS).split(";")) {
            City c = City.decode(s);
            if (c != null) out.add(c);
        }
        return out;
    }

    static void savePresets(SharedPreferences p, List<City> list) {
        StringBuilder sb = new StringBuilder();
        for (City c : list) { if (sb.length() > 0) sb.append(';'); sb.append(c.encode()); }
        p.edit().putString(PRESETS, sb.toString()).apply();
    }

    /** Lock-screen placement: out = {centreX, centreY (GL, bottom-origin), radiusPx, cameraDistance}. */
    static void layout(Config c, int w, int h, float[] out) {
        float s = Math.min(w, h);
        if (c.horizon) {
            float r = 1.30f * s * c.size;
            out[0] = w * 0.5f; out[1] = h * (c.height + 0.10f) - r; out[2] = r; out[3] = 1.9f;
        } else {
            float r = 0.47f * s * c.size;
            out[0] = w * 0.5f; out[1] = (w < h) ? h * c.height : h * 0.5f; out[2] = r; out[3] = 6.6f;
        }
    }
}
