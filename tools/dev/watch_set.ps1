$adb = 'C:\Users\chait\android-dev\sdk\platform-tools\adb.exe'
$deadline = (Get-Date).AddSeconds(50)
while ((Get-Date) -lt $deadline) {
    $w = (& $adb -d shell dumpsys wallpaper 2>$null) -join "`n"
    $lock = ($w -split 'Lock wallpaper state:')[1]
    $sys  = ($w -split 'Lock wallpaper state:')[0]
    if ($lock -match 'com\.chai\.earthwall') {
        'LOCK=EARTH'
        if ($sys -match 'magicportrait') { 'HOME=MagicPortrait (unchanged)' } else { 'HOME CHANGED:'; ($sys -split "`n" | Select-String 'mWallpaperComponent') }
        exit 0
    }
    Start-Sleep 3
}
'not set yet'
