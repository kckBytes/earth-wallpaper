package com.chai.earthwall;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Right after the app is updated (Android tells us with MY_PACKAGE_REPLACED): one notification saying what's new,
 * and, if the setup guide isn't finished, how far along it is. Tapping opens the app, where the guide waits at the
 * top. So people who already had Earth find the new setup steps without being told.
 * Golden app only (Earth Next is reinstalled from the laptop all the time).
 */
public class Installed extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent in) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(in.getAction()) || !Updater.enabled(c)) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return;
        String name = Updater.installedName(c);
        android.content.SharedPreferences up = Updater.prefs(c);
        String notes = up.getLong("code", 0) == Updater.installed(c) ? up.getString("notes", "") : "";
        int[] s = SettingsActivity.setupProgress(c);
        boolean todo = s[0] < s[1];
        String line = todo ? "Finish setting up: " + s[0] + " of " + s[1] + " steps done. Tap to continue."
                : "Tap to open.";
        NotificationChannel ch = new NotificationChannel(Updater.CH, "App updates", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("A newer version of Earth is ready to install");
        nm.createNotificationChannel(ch);
        PendingIntent pi = PendingIntent.getActivity(c, 1, new Intent(c, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, Updater.CH)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("Earth updated to " + name)
                .setContentText(todo ? line : (notes.isEmpty() ? line : notes))
                .setStyle(new Notification.BigTextStyle().bigText((notes.isEmpty() ? "" : notes + "\n") + line))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.cancel(4401);                                      // the "update ready" note is done with
        nm.notify(4402, n);
        Log.i("EarthUpdate", "updated to " + name + ", setup " + s[0] + "/" + s[1]);
    }
}
