$adb = 'C:\Users\chait\android-dev\sdk\platform-tools\adb.exe'
$uid = '10364'
function row { (& $adb -d shell dumpsys gpu --gpuwork) | Where-Object { $_ -match "^\s*\d+\s+$uid\s" } | Select -First 1 }
function awake { ((& $adb -d shell dumpsys power) | Select-String 'mWakefulness=' | Select -First 1).Line.Trim() }
# wait (up to ~50 s) for the screen to come on
$deadline = (Get-Date).AddSeconds(50)
while ((Get-Date) -lt $deadline -and (awake) -notmatch 'Awake') { Start-Sleep 1 }
if ((awake) -notmatch 'Awake') { 'screen still off'; exit 0 }
'screen on'
Start-Sleep 3                       # skip the wake animation itself; measure steady state
$a = row; $t0 = Get-Date
Start-Sleep 15
$b = row; $t1 = Get-Date
$kg = (& $adb -d shell dumpsys window | Select-String 'isKeyguardShowing' | Select -First 1).Line.Trim()
$aa = -split $a; $bb = -split $b
$act = ([double]$bb[2] - [double]$aa[2]) / 1e9; $sec = ($t1 - $t0).TotalSeconds
"steady state on $kg : GPU active $([math]::Round($act,2)) s of $([math]::Round($sec,1)) s => $([math]::Round(100*$act/$sec,1)) %"
& $adb -d shell dumpsys SurfaceFlinger --list 2>$null | Select-String -Pattern 'Wallpaper|earthwall' | Select -First 3
