$r = 'C:\Users\chait\android-dev'
Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | ? { $_.CommandLine -like '*install.ps1*' -and $_.ProcessId -ne $PID } | % { Stop-Process -Id $_.ProcessId -Force; "killed $($_.ProcessId)" }
Start-Sleep 1
Remove-Item "$r\clt.zip", "$r\clt-tmp", "$r\sdk" -Recurse -Force -EA SilentlyContinue
$p = "$r\install.ps1"
$s = Get-Content $p -Raw
$s = $s.Replace("Select -Last 1 -Expand Value", "% { `$_.Value } | Sort { [int](`$_ -replace '\D','') } | Select -Last 1")
Set-Content $p $s
(Select-String -Path $p -Pattern 'commandlinetools').Line
Start-Process powershell -ArgumentList '-ExecutionPolicy', 'Bypass', '-Command', "& '$p' *> $r\install.log" -WindowStyle Hidden
'restarted'
