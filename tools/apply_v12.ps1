$root = 'C:\Users\chait\Claude\EarthWallpaper'
$in = "$root\incoming\v12"
$expect = @{
  'globe.frag' = '83ecb98e6c09e08c389fe7d43e82dc8e'; 'globe.vert' = '87f767132e533237e7e19fe8fc0edb98'; 'label.frag' = '8c1014dbd96ffb97fd1c128e06e03035'
  'EarthWallpaperService.java' = '560078407516d066153bb0474177d7e7'; 'GlobeRenderer.java' = '0f50c84d5746986b5d8176a08fd5ba5f'
  'GlobeThread.java' = '521ca9700fe678cec7bf3f3bc02da52a'; 'Prefs.java' = '349c76f91167604064708fbf52eefa7c'; 'SettingsActivity.java' = 'f290339bc048b803f38c48cd631efa9f'
}
$bad = 0
Get-ChildItem $in -Recurse -File | % {
  $h = (Get-FileHash $_.FullName -Algorithm MD5).Hash.ToLower()
  if ($expect[$_.Name] -ne $h) { "MISMATCH $($_.Name) $h"; $bad++ }
}
if ($bad -gt 0) { 'aborting'; exit 1 }
'all 8 verified'
Copy-Item "$in\assets\*" "$root\app\assets\" -Force
Copy-Item "$in\src\*.java" "$root\app\src\com\chai\earthwall\" -Force
powershell -ExecutionPolicy Bypass -File "$root\build.ps1" 2>&1 | Select-String 'BUILT|FAILED|error:' -Context 0,3
