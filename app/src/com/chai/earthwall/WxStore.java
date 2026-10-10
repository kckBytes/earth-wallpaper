package com.chai.earthwall;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Weather layers (NOAA GFS via the earth-live server): every 3 h from 24 h ago to 12 h ahead, 720 x 360.
 * Sheets: a = temperature, feels-like, humidity; b = precipitation, frozen share, thunderstorm energy;
 * c = pressure, snow depth, waves; w = wind east, wind north, gusts. The phone keeps only the sheet its view
 * needs plus the wind (for the streaks): about 0.5 MB per 3 h step.
 */
final class WxStore {
    private WxStore() {}
    private static final String TAG = "EarthWall";

    static File dir(Context c) { File d = new File(c.getFilesDir(), "wx"); d.mkdirs(); return d; }

    /** Which sheet holds a view's data, and which channel (3 = wind speed from east/north, 4 = precipitation). */
    static char sheet(int view) {
        switch (view) {
            case 10: case 11: case 17: return 'a';
            case 12: case 16: return 'b';
            case 15: case 18: case 19: return 'c';
            default: return 'w';
        }
    }
    static int channel(int view) {
        switch (view) {
            case 10: return 0; case 11: return 1; case 17: return 2;
            case 12: return 4; case 16: return 2;
            case 15: return 0; case 18: return 1; case 19: return 2;
            case 13: return 3; default: return 2;               // 14 gusts
        }
    }

    static void sync(Context c, SharedPreferences p, boolean metered) throws Exception {
        int view = p.getInt(Prefs.VIEW, 0);
        if (view < 10) return;
        long now = System.currentTimeMillis();
        if (now - p.getLong("wx_checked", 0) < 15 * 60_000L && p.getInt("wx_view", -1) == view) return;
        byte[] jb = CloudUpdater.download(CloudUpdater.LIVE_URL + "wx.json?t=" + now, 20_000);
        JSONObject j = new JSONObject(new String(jb, "UTF-8"));
        JSONArray arr = j.getJSONArray("times");
        char need = sheet(view);
        Set<String> keep = new HashSet<>();
        int got = 0;
        File d = dir(c);
        for (int i = arr.length() - 1; i >= 0; i--) {                  // newest first
            long t = arr.getLong(i);
            for (char s : need == 'w' ? new char[]{'w'} : new char[]{need, 'w'}) {
                String name = t + "_" + s + ".png";
                keep.add(name);
                File f = new File(d, name);
                if (f.exists() && f.length() > 1000) continue;
                if (metered && got >= 4) continue;                     // on mobile data: just the next few
                byte[] b = CloudUpdater.download(CloudUpdater.LIVE_URL + "wx/" + name, 30_000);
                File tmp = new File(d, name + ".tmp");
                try (OutputStream o = new FileOutputStream(tmp)) { o.write(b); }
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
                got++;
            }
        }
        File[] all = d.listFiles();
        if (all != null) for (File f : all) if (!keep.contains(f.getName())) f.delete();
        p.edit().putLong("wx_checked", now).putInt("wx_view", view).putLong("wx_stamp", now).apply();
        if (got > 0) Log.i(TAG, "weather: fetched " + got + " sheets for view " + view);
    }

    /** Times on the phone that have both the view's sheet and the wind, oldest first. */
    static List<Long> times(Context c, int view) {
        List<Long> out = new ArrayList<>();
        File[] all = dir(c).listFiles();
        if (all == null) return out;
        char need = sheet(view);
        for (File f : all) {
            String n = f.getName();
            if (!n.endsWith("_" + need + ".png")) continue;
            try {
                long t = Long.parseLong(n.substring(0, n.indexOf('_')));
                if (new File(dir(c), t + "_w.png").exists()) out.add(t);
            } catch (Exception ignored) { }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** RGB bytes (w*h*3) of one sheet, or null. */
    static byte[] load(Context c, long t, char sheet, int[] wh) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        o.inScaled = false;
        Bitmap b = BitmapFactory.decodeFile(new File(dir(c), t + "_" + sheet + ".png").getPath(), o);
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        byte[] out = new byte[w * h * 3];
        for (int i = 0; i < px.length; i++) {
            out[3 * i] = (byte) (px[i] >> 16); out[3 * i + 1] = (byte) (px[i] >> 8); out[3 * i + 2] = (byte) px[i];
        }
        wh[0] = w; wh[1] = h;
        return out;
    }
}
