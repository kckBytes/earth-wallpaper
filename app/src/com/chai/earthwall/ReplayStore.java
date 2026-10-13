package com.chai.earthwall;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The real past day for the back triple-tap replay. The earth-live server publishes a stitched frame every
 * 30 min for the last 24 h (frames.json, frames/<t>_c.jpg clouds, <t>_bt.jpg infrared, <t>_f.png motion to the
 * next frame). The phone keeps only the frames inside the chosen replay length, for the current view, and only
 * downloads what it doesn't have (steady state: one ~0.4 MB frame per half hour). On mobile data it tops up at
 * most the newest two frames.
 */
final class ReplayStore {
    private ReplayStore() {}
    private static final String TAG = "EarthWall";
    private static final long H = 3_600_000L;

    static File dir(Context c) { File d = new File(c.getFilesDir(), "replay"); d.mkdirs(); return d; }

    private static String suffix(String kind) { return kind.equals("bt") ? "_bt.jpg" : "_c.jpg"; }

    static void sync(Context c, SharedPreferences p, boolean metered) throws Exception {
        sync(c, p, metered, p.getInt(Prefs.REPLAY_H, 12));
    }

    /** Downloads what's missing of the last `hours` (control mode asks for 24); keeps up to 24 h on the phone. */
    static void sync(Context c, SharedPreferences p, boolean metered, int hours) throws Exception {
        String kind = CloudUpdater.kind(p);
        if (kind.equals("sst")) return;                                   // daily map, nothing to replay
        byte[] fj = CloudUpdater.download(CloudUpdater.LIVE_URL + "frames.json?t=" + System.currentTimeMillis(), 20_000);
        JSONObject j = new JSONObject(new String(fj, "UTF-8"));
        write(new File(dir(c), "frames.json"), fj);                     // kept for the per-step activity
        JSONArray arr = j.getJSONArray("frames");
        long[] ts = new long[arr.length()];
        java.util.Map<Long, Long> ver = new java.util.HashMap<>();      // server-side version of each cloud frame
        for (int i = 0; i < ts.length; i++) { JSONObject o = arr.getJSONObject(i); ts[i] = o.getLong("t"); ver.put(ts[i], o.optLong("v", 0)); }
        Arrays.sort(ts);
        if (ts.length == 0) return;
        long newest = ts[ts.length - 1];
        List<Long> want = new ArrayList<>();
        for (long t : ts) if (t >= newest - hours * H - 60_000) want.add(t);
        File d = dir(c);
        int fetched = 0, kb = 0;
        int start = metered ? Math.max(0, want.size() - 2) : 0;
        for (int i = want.size() - 1; i >= start; i--) {                  // newest first
            long t = want.get(i);
            File img = new File(d, t + suffix(kind)), fl = new File(d, t + "_f.png");
            long v = kind.equals("c") ? ver.getOrDefault(t, 0L) : 0L;     // frame re-made on the server since we got it
            if (!img.exists() || img.length() < 1000 || (v > 0 && img.lastModified() < v)) {
                byte[] b = CloudUpdater.download(CloudUpdater.LIVE_URL + "frames/" + t + suffix(kind), 60_000);
                write(img, b); fetched++; kb += b.length / 1024;
            }
            // a frame that arrives later (backfilled into a gap) changes the motion of the frame before it
            File nextImg = i + 1 < want.size() ? new File(d, want.get(i + 1) + suffix(kind)) : null;
            boolean stale = nextImg != null && nextImg.exists() && fl.exists() && fl.lastModified() < nextImg.lastModified();
            if (!fl.exists() || fl.length() < 50 || stale) {
                byte[] b = CloudUpdater.download(CloudUpdater.LIVE_URL + "frames/" + t + "_f.png", 20_000);
                write(fl, b);
            }
        }
        // the newest frame's motion changes as the next frame arrives: refresh it every time
        try { write(new File(d, newest + "_f.png"), CloudUpdater.download(CloudUpdater.LIVE_URL + "frames/" + newest + "_f.png", 20_000)); }
        catch (Exception ignored) { }
        // keep at most 24 h, nothing older than the chosen length for this view, and only frames the server
        // still lists (frames it has replaced, e.g. off-grid ones from before a rebuild, would play out of step)
        java.util.Set<Long> listed = new java.util.HashSet<>();
        for (long t : ts) listed.add(t);
        int stale = 0;
        File[] all = d.listFiles();
        if (all != null) for (File f : all) {
            if (f.getName().equals("frames.json")) continue;
            try {
                long t = Long.parseLong(f.getName().substring(0, f.getName().indexOf('_')));
                if (!listed.contains(t)) { if (f.delete()) stale++; continue; }
                boolean other = !f.getName().endsWith("_f.png") && !f.getName().endsWith(suffix(kind));
                if (t < newest - 24 * H - 60_000) f.delete();              // a full day stays for control mode's replay
            } catch (Exception e) { f.delete(); }
        }
        if (stale > 0) Log.i(TAG, "replay: removed " + stale + " files of frames the server no longer lists");
        if (fetched > 0) Log.i(TAG, "replay: fetched " + fetched + " " + kind + " frames (" + kb + " KB); have " + available(c, kind, hours).size() + " for " + hours + " h");
    }

    private static void write(File f, byte[] b) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(b); }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
    }

    /** Frames (oldest first) of this kind within the last `hours` of what's on the phone, each with its motion file. */
    static List<Long> available(Context c, String kind, int hours) {
        List<Long> out = new ArrayList<>();
        File[] all = dir(c).listFiles();
        if (all == null) return out;
        for (File f : all) {
            String n = f.getName();
            if (!n.endsWith(suffix(kind))) continue;
            try {
                long t = Long.parseLong(n.substring(0, n.indexOf('_')));
                if (new File(dir(c), t + "_f.png").exists()) out.add(t);
            } catch (Exception ignored) { }
        }
        java.util.Collections.sort(out);
        if (out.isEmpty()) return out;
        long newest = out.get(out.size() - 1);
        out.removeIf(t -> t < newest - hours * H - 60_000);
        return out;
    }

    /** How much the clouds change from each frame to the next: 8 x 4 grid (lon x lat, row 0 = north), x1000. */
    static java.util.Map<Long, int[]> activity(Context c) {
        java.util.Map<Long, int[]> out = new java.util.HashMap<>();
        try {
            File f = new File(dir(c), "frames.json");
            byte[] b = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) { int o = 0, n; while (o < b.length && (n = in.read(b, o, b.length - o)) > 0) o += n; }
            JSONArray arr = new JSONObject(new String(b, "UTF-8")).getJSONArray("frames");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                JSONArray a = o.optJSONArray("a");
                if (a == null || a.length() != 32) continue;
                int[] v = new int[32];
                for (int k = 0; k < 32; k++) v[k] = a.getInt(k);
                out.put(o.getLong("t"), v);
            }
        } catch (Exception ignored) { }
        return out;
    }

    static Images.Single loadImage(Context c, long t, String kind) throws Exception {
        return Images.decodeSingle(new FileInputStream(new File(dir(c), t + suffix(kind))));
    }

    static ByteBuffer loadFlow(Context c, long t) {
        int gw = FlowEstimator.GW, gh = FlowEstimator.GH;
        ByteBuffer out = ByteBuffer.allocateDirect(gw * gh * 2).order(ByteOrder.nativeOrder());
        Bitmap b = BitmapFactory.decodeFile(new File(dir(c), t + "_f.png").getPath());
        if (b == null || b.getWidth() != gw || b.getHeight() != gh) return FlowEstimator.still();
        int[] px = new int[gw * gh];
        b.getPixels(px, 0, gw, 0, 0, gw, gh);
        for (int v : px) { out.put((byte) ((v >> 16) & 0xFF)); out.put((byte) ((v >> 8) & 0xFF)); }
        out.position(0);
        return out;
    }
}
