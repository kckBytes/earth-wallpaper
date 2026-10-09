$adb = 'C:\Users\chait\android-dev\sdk\platform-tools\adb.exe'
$out = 'C:\Users\chait\EarthWallpaper\emu'
& $adb -e shell input tap 540 1293
Start-Sleep 4
& $adb -e shell dumpsys wallpaper | Select-String -Pattern 'mWallpaperComponent|which=|mWhich|Lock|lock' | Select -First 8
& $adb -e shell input keyevent 26     # screen off
Start-Sleep 3
& $adb -e shell input keyevent 26     # screen on -> lock screen
Start-Sleep 0.4
& $adb -e shell screencap -p /sdcard/a.png; & $adb -e pull /sdcard/a.png "$out\lock_wake_early.png" 2>&1 | Out-Null
Start-Sleep 2.5
& $adb -e shell screencap -p /sdcard/b.png; & $adb -e pull /sdcard/b.png "$out\lock_settled.png" 2>&1 | Out-Null
& $adb -e shell dumpsys gfxinfo com.chai.earthwall 2>$null | Select -First 3
& $adb -e logcat -d -s EarthWall:V AndroidRuntime:E | Select -Last 10
'done'
