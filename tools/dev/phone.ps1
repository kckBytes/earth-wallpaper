param([string]$step = 'state', [string]$arg = '')
$adb = 'C:\Users\chait\android-dev\sdk\platform-tools\adb.exe'
$out = 'C:\Users\chait\Claude\EarthWallpaper\phone'
New-Item -ItemType Directory -Force $out | Out-Null
function ui {
    & $adb -d shell uiautomator dump /sdcard/earth_ui.xml 2>&1 | Out-Null
    & $adb -d pull /sdcard/earth_ui.xml "$out\ui.xml" 2>&1 | Out-Null
    & $adb -d shell rm /sdcard/earth_ui.xml 2>&1 | Out-Null
    ([xml](Get-Content "$out\ui.xml")).SelectNodes('//node') | ? { $_.text -or $_.'content-desc' } |
        % { "$($_.text) | $($_.'content-desc') | clickable=$($_.clickable) | $($_.bounds)" }
}
switch ($step) {
    'state' {
        & $adb -d shell getprop ro.build.version.release
        & $adb -d shell wm size
        & $adb -d shell dumpsys wallpaper | Select-String 'System wallpaper state|Lock wallpaper state|mWhich|mWallpaperComponent|mImageWallpaper' | % { $_.Line.Trim() }
    }
    'install' {
        & $adb -d install C:\Users\chait\Claude\EarthWallpaper\Earth.apk 2>&1
        & $adb -d shell pm list packages com.chai.earthwall
    }
    'open' {
        & $adb -d logcat -c
        & $adb -d shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT com.chai.earthwall/.EarthWallpaperService 2>&1
        Start-Sleep 6
        ui
    }
    'ui' { ui }
    'tap' { $xy = $arg.Split(','); & $adb -d shell input tap $xy[0] $xy[1]; Start-Sleep 4; ui }
    'shot' {
        & $adb -d shell screencap -p /sdcard/earth_shot.png
        & $adb -d pull /sdcard/earth_shot.png "$out\$arg.png" 2>&1 | Out-Null
        & $adb -d shell rm /sdcard/earth_shot.png
        "saved $arg"
    }
    'log' {
        & $adb -d logcat -d -s EarthWall:V AndroidRuntime:E | Select -Last 15
        & $adb -d shell pidof com.chai.earthwall
    }
}
