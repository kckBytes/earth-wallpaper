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
    static final String TWIST = "twist";
    static final String FLIP = "flip";
    static final String KNOCK = "knock_zoom";
    static final String STARS = "stars";
    static final String HD_WIFI_ONLY = "hd_wifi_only";
    static final String LIVE_CLOUDS = "live_clouds";   // true: 5-satellite stitch every 10 min (earth-live); false: 3-hourly composite
    static final String CLOUDS_SRC = "clouds_src";
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

    static final class Config {
        City city = HOME_DEFAULT;
        float size = 1f, height = DEF_HEIGHT, gain = DEF_GAIN;
        boolean horizon, motion, parallax = true, marker = true, stars = true;
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
        c.stars = p.getBoolean(STARS, true);
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
