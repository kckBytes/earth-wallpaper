package com.chai.earthwall;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Requests: extra things you asked this phone to track, on top of what everyone sees.
 * Right now that's launches: after a rocket lifts off, Earth asks whether to follow what it carried. Its orbit is
 * published a day or two later (Launch Library 2 gives the launch its international designator, CelesTrak lists
 * the objects); the earth-live server puts it in launched.json. Until then the request waits; Earth checks on its
 * own (every 30 min while the wallpaper runs) and "Check now" asks right away. Once it's there it goes on the
 * globe in green. "Make permanent" opens a pre-filled GitHub issue asking for it to be added for everyone.
 */
final class Requests {
    private Requests() {}
    private static final String TAG = "EarthWall";
    static final String CH = "launches";
    static final String ACTION_TRACK = "com.chai.earthwall.TRACK_LAUNCH";
    static final String NOTIFY = "notify_launches";                 // Settings switch (main prefs), default on
    private static final long AUTO_EVERY = 30 * 60_000L;
    static final String ISSUES = "https://github.com/kckBytes/earth-wallpaper/issues/new";

    static final class Req {
        String id, rocket, mission; long net, added, checked; boolean on, permanent; String result = "";
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("requests", Context.MODE_PRIVATE); }

    /** Bumped on every change, so the globe reloads its labels. */
    static long version(Context c) { return prefs(c).getLong("ver", 0); }

    static List<Req> list(Context c) {
        List<Req> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Req r = new Req();
                r.id = o.getString("id"); r.rocket = o.optString("rocket"); r.mission = o.optString("mission");
                r.net = o.optLong("net"); r.added = o.optLong("added"); r.checked = o.optLong("checked");
                r.on = o.optBoolean("on"); r.permanent = o.optBoolean("permanent"); r.result = o.optString("result");
                out.add(r);
            }
        } catch (Exception e) { Log.w(TAG, "requests unreadable", e); }
        return out;
    }

    static synchronized void save(Context c, List<Req> l) {
        JSONArray a = new JSONArray();
        try {
            for (Req r : l) a.put(new JSONObject().put("id", r.id).put("rocket", r.rocket).put("mission", r.mission)
                    .put("net", r.net).put("added", r.added).put("checked", r.checked).put("on", r.on)
                    .put("permanent", r.permanent).put("result", r.result));
        } catch (Exception ignored) { }
        prefs(c).edit().putString("list", a.toString()).putLong("ver", System.currentTimeMillis()).apply();
    }

    static Req find(List<Req> l, String id) { for (Req r : l) if (r.id.equals(id)) return r; return null; }

    static void add(Context c, String id, String rocket, String mission, long net) {
        List<Req> l = list(c);
        if (find(l, id) != null) return;
        Req r = new Req();
        r.id = id; r.rocket = rocket; r.mission = mission; r.net = net; r.added = System.currentTimeMillis();
        r.result = "Waiting for its orbit to be published";
        l.add(r);
        save(c, l);
        markFromCache(c);
    }

    static void remove(Context c, String id) {
        List<Req> l = list(c);
        l.removeIf(r -> r.id.equals(id));
        save(c, l);
    }

    static void setPermanentAsked(Context c, String id) {
        List<Req> l = list(c);
        Req r = find(l, id);
        if (r != null) { r.permanent = true; save(c, l); }
    }

    static File cache(Context c) { return new File(c.getFilesDir(), "launched.json"); }

    /** launched.json as last downloaded: launch id -> its entry. */
    static java.util.Map<String, JSONObject> launched(Context c) {
        java.util.Map<String, JSONObject> out = new java.util.LinkedHashMap<>();
        try {
            File f = cache(c);
            if (!f.exists()) return out;
            JSONArray a = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8")).getJSONArray("launches");
            for (int i = 0; i < a.length(); i++) { JSONObject o = a.getJSONObject(i); out.put(o.getString("id"), o); }
        } catch (Exception e) { Log.w(TAG, "launched.json unreadable", e); }
        return out;
    }

    /** Network: download launched.json. Off the main thread. */
    static void fetch(Context c) throws Exception {
        byte[] b = CloudUpdater.download(CloudUpdater.LIVE_URL + "launched.json?t=" + System.currentTimeMillis(), 30_000);
        new JSONObject(new String(b, "UTF-8"));                                  // validate
        File tmp = new File(c.getFilesDir(), "launched.tmp"), dst = cache(c);
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(b); }
        if (!tmp.renameTo(dst)) { dst.delete(); tmp.renameTo(dst); }
        prefs(c).edit().putLong("fetched", System.currentTimeMillis()).apply();
    }

    /** Update every request from the downloaded file; returns the ones that just became available. */
    static List<Req> markFromCache(Context c) {
        java.util.Map<String, JSONObject> m = launched(c);
        List<Req> l = list(c), now = new ArrayList<>();
        long t = System.currentTimeMillis();
        for (Req r : l) {
            JSONObject o = m.get(r.id);
            boolean avail = o != null && o.optBoolean("available");
            r.checked = t;
            if (avail && !r.on) now.add(r);
            r.on = avail;
            r.result = avail ? "On the globe ✓  " + o.optInt("objects", 1) + " object" + (o.optInt("objects", 1) == 1 ? "" : "s")
                    + ", " + o.optInt("alt_km") + " km up"
                    : o == null ? "Not in the launch list any more (over 10 days old?)" : "Still not available: orbit not published yet";
        }
        save(c, l);
        return now;
    }

    /** "Check now" and the automatic checks. Off the main thread. */
    static List<Req> check(Context c) throws Exception {
        fetch(c);
        List<Req> got = markFromCache(c);
        for (Req r : got) notifyOn(c, r);
        return got;
    }

    /** After each storms.json update (wallpaper / control mode): launch notifications, and the automatic checks. */
    static void afterSync(Context ctx) {
        final Context c = ctx.getApplicationContext();
        try { notifyLaunches(c); } catch (Exception e) { Log.w(TAG, "launch note failed", e); }
        boolean waiting = false;
        for (Req r : list(c)) if (!r.on) waiting = true;
        if (!waiting || System.currentTimeMillis() - prefs(c).getLong("fetched", 0) < AUTO_EVERY) return;
        try { check(c); } catch (Exception e) { Log.i(TAG, "requests: check failed: " + e.getMessage()); }
    }

    /** A rocket has lifted off (10 min to 12 h ago): ask once whether to follow what it carried. */
    private static void notifyLaunches(Context c) throws Exception {
        if (!Prefs.get(c).getBoolean(NOTIFY, true)) return;
        File f = new File(c.getFilesDir(), "storms.json");
        if (!f.exists()) return;
        JSONArray a = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8")).getJSONArray("storms");
        SharedPreferences p = prefs(c);
        String done = p.getString("asked", "");
        long now = System.currentTimeMillis();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            if (!"launch".equals(o.optString("type")) || !o.has("id")) continue;
            String id = o.getString("id"), st = o.optString("status");
            long net = o.optLong("last_obs");
            if (now - net < 10 * 60_000L || now - net > 12 * 3_600_000L) continue;
            if (st.equals("Failure") || st.equals("Hold") || st.equals("TBD") || st.equals("TBC")) continue;
            if (done.contains(id) || find(list(c), id) != null) continue;
            done = (done + "," + id);
            if (done.length() > 2000) done = done.substring(done.length() - 2000);
            p.edit().putString("asked", done).apply();
            notifyLaunched(c, id, o.optString("name"), o.optString("mission", o.optString("detail")), net);
        }
    }

    private static NotificationManager channel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return null;
        NotificationChannel ch = new NotificationChannel(CH, "Launches", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("After a rocket lifts off: follow what it carried?");
        nm.createNotificationChannel(ch);
        return nm;
    }

    private static void notifyLaunched(Context c, String id, String rocket, String mission, long net) {
        NotificationManager nm = channel(c);
        if (nm == null) return;
        int nid = 4500 + (id.hashCode() & 0x3FF);
        Intent track = new Intent(c, Receiver.class).setAction(ACTION_TRACK).putExtra("id", id).putExtra("rocket", rocket)
                .putExtra("mission", mission).putExtra("net", net).putExtra("nid", nid);
        PendingIntent pt = PendingIntent.getBroadcast(c, nid, track, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(c, nid, new Intent(c, SettingsActivity.class).putExtra("requests", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(rocket + " lifted off: " + mission)
                .setContentText("Track what it carried once its orbit is published?")
                .setStyle(new Notification.BigTextStyle().bigText("Track what it carried once its orbit is published? "
                        + "That usually takes a day or two; Earth keeps checking and puts it on your globe in green."))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Track it", pt).build())
                .setAutoCancel(true)
                .build();
        nm.notify(nid, n);
    }

    private static void notifyOn(Context c, Req r) {
        NotificationManager nm = channel(c);
        if (nm == null) return;
        PendingIntent open = PendingIntent.getActivity(c, 4499, new Intent(c, ExploreActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(4500 + (r.id.hashCode() & 0x3FF), new Notification.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(r.mission + " is on your globe")
                .setContentText("Its orbit was published. Tap to see it in control mode.")
                .setContentIntent(open).setAutoCancel(true).build());
    }

    /** "Track it" on the launch notification. */
    public static class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent in) {
            if (!ACTION_TRACK.equals(in.getAction())) return;
            add(c, in.getStringExtra("id"), in.getStringExtra("rocket"), in.getStringExtra("mission"), in.getLongExtra("net", 0));
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(in.getIntExtra("nid", 0));
            android.widget.Toast.makeText(c, "Added to Requests. Earth will put it on the globe once its orbit is out.",
                    android.widget.Toast.LENGTH_LONG).show();
            final PendingResult pr = goAsync();
            new Thread(() -> { try { check(c); } catch (Exception ignored) { } finally { pr.finish(); } }).start();
        }
    }

    /** Pre-filled GitHub issue: "make this permanent for everyone". */
    static String permanentUrl(Req r, JSONObject o) {
        String title = "Make permanent: " + r.mission + " (" + r.rocket + ")";
        String body = "Please track this for everyone in the main app.\n\n"
                + "- Launch: " + r.rocket + " / " + r.mission + "\n"
                + "- Launched: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", java.util.Locale.US) {{
                    setTimeZone(java.util.TimeZone.getTimeZone("UTC")); }}.format(new java.util.Date(r.net)) + "\n"
                + "- Launch Library 2 id: " + r.id + "\n"
                + (o != null && !o.optString("cospar").isEmpty() && !o.isNull("cospar") ? "- International designator: " + o.optString("cospar") + "\n" : "")
                + "\n(Sent from the Earth app's Requests.)";
        return ISSUES + "?title=" + android.net.Uri.encode(title) + "&body=" + android.net.Uri.encode(body);
    }
}
