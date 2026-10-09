$adb = 'C:\Users\chait\android-dev\sdk\platform-tools\adb.exe'
$line = (& $adb -d shell pm list packages -U com.chai.earthwall) -join ''
$uid = ([regex]::Match($line, 'uid:(\d+)')).Groups[1].Value
"uid=$uid"
function row { (& $adb -d shell dumpsys gpu --gpuwork) | Where-Object { $_ -match "^\s*\d+\s+$uid\s" } | Select -First 1 }
$a = row; $t0 = Get-Date
Start-Sleep 20
$b = row; $t1 = Get-Date
"before: $a"; "after:  $b"
$sec = ($t1 - $t0).TotalSeconds
if ($a -and $b) {
    $aa = -split $a; $bb = -split $b
    $act = ([double]$bb[2] - [double]$aa[2]) / 1e9
    "GPU active: $([math]::Round($act,2)) s over $([math]::Round($sec,1)) s  => duty $([math]::Round(100*$act/$sec,1)) %"
}
& $adb -d shell dumpsys window | Select-String 'mCurrentFocus|isKeyguardShowing' | Select -First 2
