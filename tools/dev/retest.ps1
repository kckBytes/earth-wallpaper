$adb = 'C:\Users\chait\android-dev\sdk\platform-tools\adb.exe'
$out = 'C:\Users\chait\EarthWallpaper\emu'
function shot($n) { & $adb -e shell screencap -p /sdcard/s.png; & $adb -e pull /sdcard/s.png "$out\$n.png" 2>&1 | Out-Null }
& $adb -e install -r C:\Users\chait\EarthWallpaper\Earth.apk
& $adb -e logcat -c
& $adb -e shell input keyevent 26; Start-Sleep 2
& $adb -e shell input keyevent 26; Start-Sleep 4
shot 'lock_v2'
# unlock with PIN, open settings activity
& $adb -e shell input keyevent 82; Start-Sleep 1
& $adb -e shell input text 1234; & $adb -e shell input keyevent 66; Start-Sleep 2
& $adb -e shell am start -n com.chai.earthwall/.SettingsActivity | Out-Null; Start-Sleep 3
shot 'settings'
& $adb -e shell am force-stop com.chai.earthwall 2>$null   # also kills the wallpaper; system should rebind it
Start-Sleep 4
& $adb -e shell input keyevent 26; Start-Sleep 2
& $adb -e shell input keyevent 26; Start-Sleep 4
shot 'lock_after_restart'
& $adb -e logcat -d -s EarthWall:V AndroidRuntime:E | Select -Last 12
& $adb -e shell dumpsys meminfo com.chai.earthwall | Select-String 'TOTAL PSS|Graphics|GL mtrack|EGL mtrack' | Select -First 4
'done'
