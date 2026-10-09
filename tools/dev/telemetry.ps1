$adb = 'C:\Users\chait\android-dev\sdk\platform-tools\adb.exe'
function awake { ((& $adb -d shell dumpsys power) | Select-String 'mWakefulness=' | Select -First 1).Line }
$deadline = (Get-Date).AddSeconds(45)
while ((Get-Date) -lt $deadline -and (awake) -notmatch 'Awake') { Start-Sleep 1 }
if ((awake) -notmatch 'Awake') { 'screen still off'; exit 0 }
$s = (& $adb -d shell 'for i in $(seq 1 44); do cat /sys/class/devfreq/34f00000.gpu0/cur_freq; sleep 0.25; done') | % { [int]([long]$_ / 1000000) }
"clock MHz (0.25 s steps): $($s -join ' ')"
& $adb -d logcat -d -s EarthWall:V AndroidRuntime:E | Select-String 'frames/3s|FATAL|Exception' | Select -Last 12 | % { $_.Line.Substring(19) }
