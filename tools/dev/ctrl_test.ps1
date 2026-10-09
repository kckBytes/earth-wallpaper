$adb = 'C:\Users\chait\android-dev\sdk\platform-tools\adb.exe'
$out = 'C:\Users\chait\EarthWallpaper\phone'
function shot($n) { & $adb -d shell screencap -p /sdcard/s.png; & $adb -d pull /sdcard/s.png "$out\$n.png" 2>&1 | Out-Null }
& $adb -d logcat -c
& $adb -d shell am start -n com.chai.earthwall/.ExploreActivity | Out-Null
Start-Sleep 5
shot 'ctrl_start'
# button: bottom centre. view 190dp square, bottomMargin = navbar + 18dp; button centre ~ (640, H - nav - 18dp - 95dp)
$size = ((& $adb -d shell wm size) -join '') -replace '.*?(\d+)x(\d+).*', '$1 $2'
$w, $h = $size.Split(' ') | % { [int]$_ }
$cx = [int]($w / 2); $cy = $h - 63 - 54 - 285
"button guess at $cx,$cy"
& $adb -d shell input tap $cx $cy           # tap -> next city
Start-Sleep 3
shot 'ctrl_next'
& $adb -d shell input swipe $cx $cy ($cx + 120) ($cy - 60) 1800   # hold + drag -> joystick
Start-Sleep 0.2
shot 'ctrl_stick'
& $adb -d shell rm /sdcard/s.png
& $adb -d logcat -d -s AndroidRuntime:E | Select-String 'FATAL|Exception' | Select -Last 3
'done'
