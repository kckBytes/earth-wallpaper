$adb = 'C:\Users\chait\android-dev\sdk\platform-tools\adb.exe'
$uid = ([regex]::Match(((& $adb -d shell pm list packages -U com.chai.earthwall) -join ''), 'uid:(\d+)')).Groups[1].Value
function row { (& $adb -d shell dumpsys gpu --gpuwork) | Where-Object { $_ -match "^\s*\d+\s+$uid\s" } | Select -First 1 }
function awake { ((& $adb -d shell dumpsys power) | Select-String 'mWakefulness=' | Select -First 1).Line }
function kg { ((& $adb -d shell dumpsys window) | Select-String 'isKeyguardShowing' | Select -First 1).Line.Trim() }
$deadline = (Get-Date).AddSeconds(40)
while ((Get-Date) -lt $deadline -and (awake) -notmatch 'Awake') { Start-Sleep 1 }
if ((awake) -notmatch 'Awake') { 'screen still off'; exit 0 }
Start-Sleep 3
$a = row; $t0 = Get-Date
Start-Sleep 12
$b = row; $t1 = Get-Date
$aa = -split $a; $bb = -split $b
$act = ([double]$bb[2] - [double]$aa[2]) / 1e9; $sec = ($t1 - $t0).TotalSeconds
"$(kg)  GPU active $([math]::Round($act,2)) s of $([math]::Round($sec,1)) s => $([math]::Round(100*$act/$sec,1)) %"
