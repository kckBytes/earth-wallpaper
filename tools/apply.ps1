param([string]$ver, [string]$hashes)
# hashes: "name=md5;name=md5;..."  Files in incoming\<ver>\ (java -> src, everything else -> assets)
$root = 'C:\Users\chait\Claude\EarthWallpaper'
$in = "$root\incoming\$ver"
$expect = @{}
foreach ($p in $hashes.Split(';')) { $kv = $p.Split('='); $expect[$kv[0]] = $kv[1] }
$bad = 0
foreach ($k in $expect.Keys) {
    $f = Get-ChildItem $in -Recurse -File -Filter $k | Select -First 1
    if (-not $f) { "MISSING $k"; $bad++; continue }
    $h = (Get-FileHash $f.FullName -Algorithm MD5).Hash.ToLower()
    if ($h -ne $expect[$k]) { "MISMATCH $k $h"; $bad++ }
}
if ($bad) { 'aborting'; exit 1 }
"all $($expect.Count) verified"
Get-ChildItem $in -Recurse -File | % {
    if ($_.Extension -eq '.java') { Copy-Item $_.FullName "$root\app\src\com\chai\earthwall\" -Force }
    else { Copy-Item $_.FullName "$root\app\assets\" -Force }
}
powershell -ExecutionPolicy Bypass -File "$root\build.ps1" 2>&1 | Select-String 'BUILT|FAILED|error:' -Context 0,3
$adb = 'C:\Users\chait\android-dev\sdk\platform-tools\adb.exe'
& $adb -d install -r "$root\Earth.apk" 2>&1 | Select -Last 1
Copy-Item "$root\Earth.apk" "$env:USERPROFILE\Downloads\Earth.apk" -Force
& $adb -d logcat -c
& $adb -d shell dumpsys wallpaper | Select-String 'mWallpaperComponent' | Select -First 2 | % { $_.Line.Trim() }
