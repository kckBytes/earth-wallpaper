$adb = 'C:\Users\chait\android-dev\sdk\platform-tools\adb.exe'
$out = 'C:\Users\chait\Claude\EarthWallpaper\emu'
& $adb -e shell locksettings set-pin 1234
& $adb -e logcat -c
& $adb -e shell input keyevent 26
Start-Sleep 3
& $adb -e shell input keyevent 26
Start-Sleep 0.5
& $adb -e shell screencap -p /sdcard/a.png; & $adb -e pull /sdcard/a.png "$out\lock1.png" 2>&1 | Out-Null
Start-Sleep 3
& $adb -e shell screencap -p /sdcard/b.png; & $adb -e pull /sdcard/b.png "$out\lock2.png" 2>&1 | Out-Null
Start-Sleep 20
& $adb -e shell screencap -p /sdcard/c.png; & $adb -e pull /sdcard/c.png "$out\lock3.png" 2>&1 | Out-Null
& $adb -e logcat -d -s EarthWall:V AndroidRuntime:E | Select -Last 10
& $adb -e shell dumpsys window | Select-String 'mDreamingLockscreen|isKeyguardShowing|mShowingLockscreen|KeyguardShowing' | Select -First 3
'done'
