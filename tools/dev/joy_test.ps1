$adb = 'C:\Users\chait\android-dev\sdk\platform-tools\adb.exe'
$out = 'C:\Users\chait\Claude\EarthWallpaper\phone'
function shot($n) { & $adb -d shell screencap -p /sdcard/earth_shot.png; & $adb -d pull /sdcard/earth_shot.png "$out\$n.png" 2>&1 | Out-Null; & $adb -d shell rm /sdcard/earth_shot.png }
& $adb -d shell am start -n com.chai.earthwall/.ExploreActivity | Out-Null
Start-Sleep 5
shot 'joy_before'
# find the joystick row: dump UI, locate the ◀ ▶ buttons, sticks sit left/right of them
& $adb -d shell uiautomator dump /sdcard/earth_ui.xml 2>&1 | Out-Null
& $adb -d pull /sdcard/earth_ui.xml "$out\ui.xml" 2>&1 | Out-Null
& $adb -d shell rm /sdcard/earth_ui.xml
$nodes = ([xml](Get-Content "$out\ui.xml")).SelectNodes('//node')
$prev = $nodes | ? { $_.text -eq [string][char]0x25C0 } | Select -First 1
"prev button: $($prev.bounds)"
if ($prev) {
    $m = [regex]::Matches($prev.bounds, '\d+') | % { [int]$_.Value }
    $cy = [int](($m[1] + $m[3]) / 2)
    $sx = 60 + 198     # move stick centre x (pad 20dp + 66dp at 3x density)
    # hold the move stick pushed right for 1.5 s
    & $adb -d shell input swipe $sx $cy ($sx + 150) $cy 1500
    Start-Sleep 0.3
    shot 'joy_after_move'
    $zx = 1280 - 60 - 96   # zoom stick centre x
    & $adb -d shell input swipe $zx $cy $zx ($cy - 150) 1500
    Start-Sleep 0.3
    shot 'joy_after_zoom'
}
& $adb -d logcat -d -s AndroidRuntime:E EarthWall:V | Select-String 'FATAL|explore' | Select -Last 4 | % { $_.Line.Substring(31) }
