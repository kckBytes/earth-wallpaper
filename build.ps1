# Builds Earth.apk with the raw SDK tools (no Gradle): aapt2 -> javac -> d8 -> zipalign -> apksigner.
$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot
$dev  = "$env:USERPROFILE\android-dev"
$env:JAVA_HOME = "$dev\jdk"
$env:Path = "$dev\jdk\bin;$env:Path"
$bt  = "$dev\sdk\build-tools\37.0.0"
$jar = "$dev\sdk\platforms\android-37.0\android.jar"
$out = "$root\build"

function run([string]$exe, [string[]]$a) {
    & $exe @a
    if ($LASTEXITCODE -ne 0) { Write-Host "FAILED: $exe (exit $LASTEXITCODE)"; exit 1 }
}

Remove-Item $out -Recurse -Force -EA SilentlyContinue
New-Item -ItemType Directory "$out\gen", "$out\classes", "$out\dex" -Force | Out-Null

"[1/6] resources"
run "$bt\aapt2.exe" @('compile', '--dir', "$root\app\res", '-o', "$out\res.zip")
run "$bt\aapt2.exe" @('link', '-o', "$out\base.apk", '-I', $jar,
    '--manifest', "$root\app\AndroidManifest.xml", '--custom-package', 'com.chai.earthwall', '-A', "$root\app\assets", '--java', "$out\gen",
    '--min-sdk-version', '30', '--target-sdk-version', '36', '--version-code', '3', '--version-name', '1.2',
    '-0', 'jpg', "$out\res.zip")

"[2/6] javac"
$src = Get-ChildItem "$root\app\src", "$out\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
run "$dev\jdk\bin\javac.exe" (@('--release', '11', '-cp', $jar, '-encoding', 'UTF-8', '-d', "$out\classes") + $src)

"[3/6] d8"
$classes = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
run "$bt\d8.bat" (@('--release', '--min-api', '30', '--lib', $jar, '--output', "$out\dex") + $classes)

"[4/6] package"
run "python" @('-c', "import zipfile,shutil,sys; shutil.copy(sys.argv[1],sys.argv[2]); z=zipfile.ZipFile(sys.argv[2],'a',zipfile.ZIP_DEFLATED); z.write(sys.argv[3],'classes.dex'); z.close()",
    "$out\base.apk", "$out\unsigned.apk", "$out\dex\classes.dex")
run "$bt\zipalign.exe" @('-p', '-f', '4', "$out\unsigned.apk", "$out\aligned.apk")

"[5/6] sign"
$ks = "$root\earth.keystore"
if (-not (Test-Path $ks)) {
    run "$dev\jdk\bin\keytool.exe" @('-genkeypair', '-keystore', $ks, '-alias', 'earth', '-keyalg', 'RSA',
        '-keysize', '2048', '-validity', '10000', '-storepass', 'earthwall', '-keypass', 'earthwall',
        '-dname', 'CN=Earth Wallpaper')
}
run "$bt\apksigner.bat" @('sign', '--ks', $ks, '--ks-pass', 'pass:earthwall', '--out', "$root\Earth.apk", "$out\aligned.apk")

"[6/6] verify"
run "$bt\apksigner.bat" @('verify', "$root\Earth.apk")
"BUILT Earth.apk  $([math]::Round((Get-Item "$root\Earth.apk").Length / 1MB, 1)) MB"
