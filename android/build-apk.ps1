# Rebuilds the web bundle, syncs it into the WebView app's assets, and builds
# (optionally installs) the Android APK.
#
#   ./build-apk.ps1            # build web + apk
#   ./build-apk.ps1 -Install   # also install + launch on the Pixel 11
param([switch]$Install)

# Not "Stop": Windows PowerShell treats gradle/npm stderr warnings as errors.
# Failures are caught through $LASTEXITCODE instead.
$ErrorActionPreference = "Continue"
$root   = Split-Path -Parent $PSScriptRoot          # repo root (the web app)
$assets = Join-Path $PSScriptRoot "app\src\main\assets"
$adb    = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jbr-17.0.14"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$gradle = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.13-bin\*\gradle-8.13\bin\gradle.bat" |
    Select-Object -First 1 -ExpandProperty FullName

Write-Host "==> Building web bundle..."
Push-Location $root
npm run build:gh
if ($LASTEXITCODE -ne 0) { throw "web build failed" }
Pop-Location

Write-Host "==> Syncing dist -> android assets..."
New-Item -ItemType Directory -Force -Path $assets | Out-Null
Get-ChildItem -Path $assets | Remove-Item -Recurse -Force
Copy-Item (Join-Path $root "dist\*") $assets -Recurse -Force

Write-Host "==> Building APK..."
Push-Location $PSScriptRoot
& $gradle assembleDebug --console=plain
if ($LASTEXITCODE -ne 0) { throw "gradle build failed" }
Pop-Location

$apk = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
Write-Host "==> APK: $apk"

if ($Install) {
    # Target the Pixel 11 by model; its wireless-adb serial changes when re-paired.
    $serial = (& $adb devices -l | Select-String "model:Pixel_11" | Select-Object -First 1).Line -split '\s+' | Select-Object -First 1
    if (-not $serial) { throw "Pixel 11 not connected over adb" }
    Write-Host "==> Installing + launching on $serial..."
    & $adb -s $serial install -r $apk
    & $adb -s $serial shell am start -n "com.akismogh.videocompressor/.MainActivity"
}
