$adb = 'C:\Users\chait\android-dev\sdk\platform-tools\adb.exe'
Start-Sleep 50
& $adb -d logcat -d -s EarthWall:V AndroidRuntime:E | Select-String 'onCommand|touch|FATAL|Exception' | Select -Last 40
'--- wallpaper-related system commands'
& $adb -d logcat -d 2>$null | Select-String -Pattern 'wallpaper.*(tap|wak|sleep|keyguard|lockscreen)' | Select -Last 10 | % { $_.Line.Substring(0, [Math]::Min(200, $_.Line.Length)) }
