# Publishes a new version of the golden Earth app so shared copies offer "Update".
#   .\tools\publish.ps1 -Notes "What changed, in one line"
# 1. bumps the version in build.ps1 (code +1, name 1.x -> 1.x+1) and builds Earth.apk
# 2. puts Earth.apk + version.json on the public earth-live repo's `dist` branch (replaced each time, no history kept)
#    version.json points at the APK by its exact commit, so a cached old file can never be mixed up with a new one
# 3. commits the version bump and pushes the golden branch and main
param([Parameter(Mandatory = $true)][string]$Notes)
$ErrorActionPreference = 'Continue'   # git prints warnings on stderr; failures are checked by exit code
$root = Split-Path $PSScriptRoot -Parent
$repo = 'https://github.com/kckBytes/earth-live.git'

$bp = Join-Path $root 'build.ps1'
$b = [IO.File]::ReadAllText($bp)
$m = [regex]::Match($b, "'--version-code', '(\d+)', '--version-name', '(\d+)\.(\d+)'")
if (-not $m.Success) { throw "version line not found in build.ps1" }
$code = [int]$m.Groups[1].Value + 1
$name = "$($m.Groups[2].Value).$([int]$m.Groups[3].Value + 1)"
[IO.File]::WriteAllText($bp, $b.Replace($m.Value, "'--version-code', '$code', '--version-name', '$name'"))
"== Earth $name (version $code)"

& $bp
$apk = Join-Path $root 'Earth.apk'
if (-not (Test-Path $apk) -or (Get-Item $apk).LastWriteTime -lt (Get-Date).AddMinutes(-5)) { throw "build failed" }
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()

$dist = Join-Path $env:TEMP 'earth-dist'
Remove-Item $dist -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $dist | Out-Null
Copy-Item $apk (Join-Path $dist 'Earth.apk')
git -C $dist init -q
git -C $dist checkout -q -b dist
git -C $dist add Earth.apk
git -C $dist commit -q -m "Earth $name ($code): app file"
if ($LASTEXITCODE -ne 0) { throw "commit failed (git user.name/email set?)" }
$apkCommit = (git -C $dist rev-parse HEAD).Trim()
$json = [ordered]@{
    versionCode = $code; versionName = $name
    apk = "https://raw.githubusercontent.com/kckBytes/earth-live/$apkCommit/Earth.apk"
    sha256 = $sha; notes = $Notes
    published = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
} | ConvertTo-Json
[IO.File]::WriteAllText((Join-Path $dist 'version.json'), $json, (New-Object Text.UTF8Encoding $false))
git -C $dist add version.json
git -C $dist commit -q -m "Earth $name ($code): version.json"
git -C $dist push -q -f $repo dist 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "push to earth-live failed" }
"== published to earth-live/dist"

git -C $root add build.ps1
git -C $root commit -q -m "Release Earth $name ($code): $Notes"
git -C $root push -q origin HEAD 2>&1 | Out-Null
git -C $root push -q origin HEAD:main 2>&1 | Out-Null
"== Earth $name is live. Shared copies will offer the update within 6 h (or when Settings is opened)."
