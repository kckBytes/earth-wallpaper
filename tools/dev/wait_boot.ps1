$adb = 'C:\Users\chait\android-dev\sdk\platform-tools\adb.exe'
$deadline = (Get-Date).AddSeconds(50)
while ((Get-Date) -lt $deadline) {
    $b = (& $adb -e shell getprop sys.boot_completed 2>$null) -join ''
    if ($b.Trim() -eq '1') { 'BOOTED'; & $adb -e shell getprop ro.build.version.release; exit 0 }
    Start-Sleep 4
}
'not yet'
& $adb devices
Get-Content C:\Users\chait\android-dev\emu.log -Tail 4
