package com.chai.earthwall;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * One screen: "Earth 1.3 is ready" → Update. Downloads the new build, checks its SHA-256 against version.json,
 * then installs it through Android's own installer (which also checks it's signed by the same key). Android asks
 * once to allow "Install unknown apps" for Earth, and may show its own Update confirmation.
 */
public class UpdateActivity extends Activity {
    private static final String TAG = "EarthUpdate";
    private TextView title, body, state;
    private Button go;
    private ProgressBar bar;
    private Updater.Info info;
    private boolean waitingForPermission, busy;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        col.setPadding(pad, pad, pad, pad);
        col.setOnApplyWindowInsetsListener((v, in) -> {
            Insets s = in.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(pad + s.left, pad + s.top, pad + s.right, pad + s.bottom);
            return WindowInsets.CONSUMED;
        });
        title = text(26, true);
        body = text(15, false);
        body.setAlpha(0.8f);
        state = text(14, false);
        state.setAlpha(0.7f);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(android.view.View.GONE);
        go = new Button(this);
        go.setOnClickListener(v -> start());
        col.addView(title);
        col.addView(body, lp(12));
        col.addView(go, lp(24));
        col.addView(bar, lp(16));
        col.addView(state, lp(8));
        setContentView(col);
        refresh();
        if (info == null) {                                       // nothing known yet: ask now
            state.setText("Checking…");
            new Thread(() -> {
                try {
                    Updater.Info i = Updater.fetch();
                    Updater.prefs(this).edit().putLong("code", i.code).putString("name", i.name).putString("apk", i.apk)
                            .putString("sha256", i.sha256).putString("notes", i.notes).apply();
                } catch (Exception e) { Log.i(TAG, "check failed: " + e); }
                runOnUiThread(() -> { state.setText(""); refresh(); });
            }).start();
        }
    }

    private void refresh() {
        info = Updater.pending(this);
        if (info == null) {
            title.setText("Earth is up to date");
            body.setText("Version " + Updater.installedName(this));
            go.setText("Close");
            go.setOnClickListener(v -> finish());
        } else {
            title.setText("Earth " + info.name + " is ready");
            body.setText((info.notes.isEmpty() ? "" : info.notes + "\n\n") + "You have " + Updater.installedName(this) + ".");
            go.setText("Update now");
            go.setAllCaps(false);
            go.setTextColor(0xFFFFFFFF);
            go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            go.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            android.graphics.drawable.GradientDrawable red = new android.graphics.drawable.GradientDrawable();
            red.setColor(0xFFD93025);
            red.setCornerRadius(dp(28));
            go.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x40FFFFFF), red, null));
            go.setPadding(dp(20), dp(14), dp(20), dp(14));
            go.setOnClickListener(v -> start());
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (waitingForPermission && getPackageManager().canRequestPackageInstalls()) { waitingForPermission = false; start(); }
    }

    private void start() {
        if (info == null || busy) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            waitingForPermission = true;
            state.setText("Android asks once: turn on \"Allow from this source\" for Earth, then come back.");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        busy = true;
        go.setEnabled(false);
        bar.setVisibility(android.view.View.VISIBLE);
        state.setText("Downloading…");
        final Updater.Info i = info;
        new Thread(() -> {
            try {
                File f = download(i);
                runOnUiThread(() -> state.setText("Installing…"));
                install(f);
            } catch (Exception e) {
                Log.w(TAG, "update failed", e);
                runOnUiThread(() -> { busy = false; go.setEnabled(true); bar.setVisibility(android.view.View.GONE); state.setText("Couldn't update: " + e.getMessage()); });
            }
        }, "earth-update").start();
    }

    private File download(Updater.Info i) throws Exception {
        File f = new File(getCacheDir(), "update.apk");
        HttpURLConnection h = (HttpURLConnection) new URL(i.apk).openConnection();
        h.setConnectTimeout(15_000);
        h.setReadTimeout(60_000);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(f)) {
            if (h.getResponseCode() != 200) throw new Exception("download error " + h.getResponseCode());
            long total = h.getContentLengthLong(), got = 0;
            byte[] b = new byte[64 * 1024];
            int n, lastPct = -1;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
                md.update(b, 0, n);
                got += n;
                if (total > 0) {
                    int pct = (int) (got * 100 / total);
                    if (pct != lastPct) { lastPct = pct; runOnUiThread(() -> bar.setProgress(pct)); }
                }
            }
        } finally { h.disconnect(); }
        StringBuilder hex = new StringBuilder();
        for (byte x : md.digest()) hex.append(String.format("%02x", x));
        if (!i.sha256.isEmpty() && !i.sha256.equalsIgnoreCase(hex.toString()))
            throw new Exception("the download didn't match (try again in a few minutes)");
        return f;
    }

    private void install(File f) throws Exception {
        PackageInstaller pi = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(getPackageName());
        if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream out = s.openWrite("base.apk", 0, f.length()); InputStream in = new FileInputStream(f)) {
                byte[] b = new byte[64 * 1024];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                s.fsync(out);
            }
            PendingIntent done = PendingIntent.getBroadcast(this, id, new Intent(this, Result.class),
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(done.getIntentSender());
        }
    }

    /** Android's answer: needs your OK (shows its dialog), done (the app restarts as the new version), or failed. */
    public static class Result extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent in) {
            int st = in.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = in.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(confirm); }
            } else if (st != PackageInstaller.STATUS_SUCCESS) {
                String m = in.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Log.w(TAG, "install failed: " + st + " " + m);
                Toast.makeText(c, "Update didn't install" + (m == null ? "" : ": " + m), Toast.LENGTH_LONG).show();
            } else Log.i(TAG, "updated");
        }
    }

    private TextView text(float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.topMargin = dp(top);
        return l;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
