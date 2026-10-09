$adb = 'C:\Users\chait\android-dev\sdk\platform-tools\adb.exe'
$kg = ((& $adb -d shell dumpsys window) | Select-String 'isKeyguardShowing' | Select -First 1).Line.Trim()
$aw = ((& $adb -d shell dumpsys power) | Select-String 'mWakefulness=' | Select -First 1).Line.Trim()
"$aw $kg"
if ($kg -match 'true' -or $aw -notmatch 'Awake') { 'PHONE LOCKED - cannot open preview'; exit 0 }
& $adb -d logcat -c
& $adb -d shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT com.chai.earthwall/.EarthWallpaperService | Out-Null
Start-Sleep 9
& $adb -d logcat -d -s EarthWall:V AndroidRuntime:E | Select-String 'frames/3s|FATAL|Exception|incomplete|failed' | Select -Last 8 | % { $_.Line.Substring(19) }
& $adb -d shell screencap -p /sdcard/earth_shot.png; & $adb -d pull /sdcard/earth_shot.png C:\Users\chait\EarthWallpaper\phone\preview_v14.png 2>&1 | Out-Null; & $adb -d shell rm /sdcard/earth_shot.png
'shot saved'
