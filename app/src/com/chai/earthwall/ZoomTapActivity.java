package com.chai.earthwall;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Invisible trampoline for Pixel's Quick Tap (double tap on the back): Settings -> System ->
 * Gestures -> Quick Tap -> Open app -> "Earth zoom". It tells the lock-screen wallpaper to zoom
 * in/out on the city that's showing, then disappears without drawing anything.
 */
public class ZoomTapActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);                      // must run over the lock screen, no unlock
        sendBroadcast(new Intent(EarthWallpaperService.ACTION_TOGGLE_ZOOM).setPackage(getPackageName()));
        finish();
        overridePendingTransition(0, 0);
    }
}
