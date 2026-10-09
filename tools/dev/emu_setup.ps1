$env:JAVA_HOME = 'C:\Users\chait\android-dev\jdk'
$sdk = 'C:\Users\chait\android-dev\sdk'
$env:ANDROID_HOME = $sdk; $env:ANDROID_SDK_ROOT = $sdk
$sm = "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"
& $sm --sdk_root=$sdk --install "emulator" "system-images/android-37.0/google_apis/x86_64" 2>&1 | Select-String -NotMatch '^\s*\[' | Select -Last 4
$avd = "$sdk\cmdline-tools\latest\bin\avdmanager.bat"
"no" | & $avd create avd -n earth37 -k "system-images;android-37.0;google_apis;x86_64" -d pixel_8 --force 2>&1 | Select -Last 3
"no" | & $avd create avd -n earth37 -k "system-images/android-37.0/google_apis/x86_64" -d pixel_8 --force 2>&1 | Select -Last 3
Get-ChildItem "$env:USERPROFILE\.android\avd" -EA SilentlyContinue | Select -Expand Name
& "$sdk\emulator\emulator.exe" -accel-check 2>&1 | Select -Last 3
"SETUP DONE"
