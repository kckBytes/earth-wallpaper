param([string]$step = 'install')
$adb = 'C:\Users\chait\android-dev\sdk\platform-tools\adb.exe'
$out = 'C:\Users\chait\Claude\EarthWallpaper\emu'
New-Item -ItemType Directory -Force $out | Out-Null
function shot($name) { & $adb -e shell screencap -p /sdcard/s.png; & $adb -e pull /sdcard/s.png "$out\$name.png" 2>&1 | Out-Null }

if ($step -eq 'install') {
    & $adb -e install -r C:\Users\chait\Claude\EarthWallpaper\Earth.apk
    & $adb -e logcat -c
    & $adb -e shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT com.chai.earthwall/.EarthWallpaperService
    Start-Sleep 8
    & $adb -e shell uiautomator dump /sdcard/ui.xml | Out-Null
    & $adb -e pull /sdcard/ui.xml "$out\ui.xml" | Out-Null
    ([xml](Get-Content "$out\ui.xml")).SelectNodes('//node') | ? { $_.text -or $_.'content-desc' } | % { "$($_.text) | $($_.'content-desc') | $($_.bounds)" }
}
if ($step -eq 'log') {
    & $adb -e logcat -d -s EarthWall:V AndroidRuntime:E libEGL:E adreno:E WallpaperService:W '*:F' | Select -Last 40
    & $adb -e shell pidof com.chai.earthwall
}
if ($step -like 'shot*') { shot $step; "saved $step" }
if ($step -like 'tap:*') { $xy = $step.Substring(4).Split(','); & $adb -e shell input tap $xy[0] $xy[1]; Start-Sleep 3 }
if ($step -eq 'ui') {
    & $adb -e shell uiautomator dump /sdcard/ui.xml | Out-Null
    & $adb -e pull /sdcard/ui.xml "$out\ui.xml" | Out-Null
    ([xml](Get-Content "$out\ui.xml")).SelectNodes('//node') | ? { $_.text -or $_.'content-desc' } | % { "$($_.text) | $($_.'content-desc') | $($_.bounds)" }
}
