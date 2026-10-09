$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = "$env:USERPROFILE\android-dev"
New-Item -ItemType Directory -Force $root | Out-Null
Set-Location $root

# 1. JDK 21 (Temurin, portable zip)
if (-not (Test-Path "$root\jdk\bin\java.exe")) {
  "Downloading JDK..."
  Invoke-WebRequest -UseBasicParsing "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse" -OutFile jdk.zip
  Expand-Archive jdk.zip -DestinationPath jdk-tmp -Force
  Move-Item (Get-ChildItem jdk-tmp | Select -First 1).FullName "$root\jdk"
  Remove-Item jdk.zip, jdk-tmp -Recurse -Force
}
$env:JAVA_HOME = "$root\jdk"
cmd /c "`"$root\jdk\bin\java.exe`" -version 2>&1"

# 2. Android command-line tools
$sdk = "$root\sdk"
if (-not (Test-Path "$sdk\cmdline-tools\latest\bin\sdkmanager.bat")) {
  "Finding cmdline-tools version..."
  $xml = (Invoke-WebRequest -UseBasicParsing "https://dl.google.com/android/repository/repository2-3.xml").Content
  $name = [regex]::Matches($xml, 'commandlinetools-win-\d+_latest\.zip') | % { $_.Value } | Sort { [int]($_ -replace '\D','') } | Select -Last 1
  "Downloading $name"
  Invoke-WebRequest -UseBasicParsing "https://dl.google.com/android/repository/$name" -OutFile clt.zip
  Expand-Archive clt.zip -DestinationPath clt-tmp -Force
  New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
  Move-Item "clt-tmp\cmdline-tools" "$sdk\cmdline-tools\latest"
  Remove-Item clt.zip, clt-tmp -Recurse -Force
}
$sm = "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"

# 3. Pick latest stable platform + build-tools
$ErrorActionPreference = 'Continue'
$list = & $sm --sdk_root=$sdk --list 2>$null | Out-String
$plat = [regex]::Matches($list, 'platforms;android-(\d+)\s') | % { [int]$_.Groups[1].Value } | Sort -Unique | Select -Last 1
$bt = [regex]::Matches($list, 'build-tools;(\d+\.\d+\.\d+)\s') | % { $_.Groups[1].Value } | Sort { [version]$_ } -Unique | Select -Last 1
"Installing platform android-$plat, build-tools $bt"
$yes = (1..20 | % { 'y' }) -join "`n"
$yes | & $sm --sdk_root=$sdk --licenses | Out-Null
& $sm --sdk_root=$sdk --install "platform-tools" "platforms;android-$plat" "build-tools;$bt" 2>&1 | Select-String -NotMatch '^\[=*' | Select -Last 5
"DONE plat=$plat bt=$bt"
Get-ChildItem "$sdk\platforms","$sdk\build-tools" | Select -Expand FullName


