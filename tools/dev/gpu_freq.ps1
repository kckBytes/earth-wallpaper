$adb = 'C:\Users\chait\android-dev\sdk\platform-tools\adb.exe'
$uid = ([regex]::Match(((& $adb -d shell pm list packages -U com.chai.earthwall) -join ''), 'uid:(\d+)')).Groups[1].Value
function row { (& $adb -d shell dumpsys gpu --gpuwork) | Where-Object { $_ -match "^\s*\d+\s+$uid\s" } | Select -First 1 }
$kg = ((& $adb -d shell dumpsys window) | Select-String 'isKeyguardShowing|mCurrentFocus' | % { $_.Line.Trim() }) -join ' | '
$aw = ((& $adb -d shell dumpsys power) | Select-String 'mWakefulness=' | Select -First 1).Line.Trim()
"$aw | $kg"
$a = row
# 40 samples of the GPU clock, ~4 per second, taken on the phone itself
$s = (& $adb -d shell 'for i in $(seq 1 40); do cat /sys/class/devfreq/34f00000.gpu0/cur_freq; sleep 0.25; done') | % { [int]([long]$_ / 1000000) }
$b = row
$aa = -split $a; $bb = -split $b
$act = ([double]$bb[2] - [double]$aa[2]) / 1e9
"GPU active during sampling: $([math]::Round($act,2)) s (~10 s window)"
"clock MHz samples: $($s -join ' ')"
$g = $s | Group-Object | Sort-Object Count -Descending | % { "$($_.Name)MHz x$($_.Count)" }
"histogram: $($g -join ', ')"
