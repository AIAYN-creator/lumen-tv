# Builds Lumen (optimised release build), installs it on the Chromecast, pre-compiles it,
# syncs the wallpapers and goes back to the home screen.
# Usage: .\deploy.ps1            (build + install)
#        .\deploy.ps1 -NoBuild   (install the last build)
param(
    [string]$Device = "192.168.0.18:5555",
    [switch]$NoBuild
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$root = $PSScriptRoot
$apk = "$root\app\build\outputs\apk\release\app-release.apk"

# The Chromecast's wireless ADB goes "offline" when idle: reconnect and retry.
function Invoke-Adb([string[]]$AdbArgs) {
    for ($i = 1; $i -le 6; $i++) {
        & $adb disconnect $Device *> $null
        & $adb connect $Device *> $null
        $out = & $adb -s $Device @AdbArgs 2>&1
        if ($LASTEXITCODE -eq 0) { return $out }
        Start-Sleep -Seconds 2
    }
    throw "adb $($AdbArgs -join ' ') failed: $out"
}

if (-not $NoBuild) {
    $env:JAVA_HOME = "$env:ProgramFiles\Android\Android Studio\jbr"
    & "$root\gradlew.bat" -p "$root" assembleRelease -q
    if ($LASTEXITCODE -ne 0) { throw "Build failed" }
}

Invoke-Adb @("install", "-r", $apk) | Out-Null
# Compile ahead of time now instead of waiting for the device to do it overnight.
Invoke-Adb @("shell", "cmd package compile -m speed -f dev.lumen.tv") | Out-Null

# Sync ..\wallpapers (photos for the background) to the app's folder on the Chromecast.
$wallpapers = Join-Path (Split-Path $root) "wallpapers"
$photos = Get-ChildItem $wallpapers -File -ErrorAction SilentlyContinue | Where-Object { $_.Extension -match '^\.(jpe?g|png|webp)$' }
if ($photos) {
    $target = "/sdcard/Android/data/dev.lumen.tv/files/wallpapers"
    Invoke-Adb @("shell", "rm -rf $target; mkdir -p $target") | Out-Null
    foreach ($photo in $photos) { Invoke-Adb @("push", $photo.FullName, "$target/") | Out-Null }
    Write-Host "Synced $($photos.Count) wallpaper(s)"
}

# Sync ..\ambientes (<scene>.mp4 videos for the Ambientes app). Only pushes what changed size,
# since the videos are big.
$ambient = Join-Path (Split-Path $root) "ambientes"
$videos = Get-ChildItem $ambient -File -Filter *.mp4 -ErrorAction SilentlyContinue
if ($videos) {
    $target = "/sdcard/Android/data/dev.lumen.tv/files/ambientes"
    $remote = (Invoke-Adb @("shell", "mkdir -p $target; ls -l $target")) -join "`n"
    foreach ($video in $videos) {
        if ($remote -notmatch "\s$($video.Length)\s.*$([regex]::Escape($video.Name))") {
            Invoke-Adb @("push", $video.FullName, "$target/") | Out-Null
        }
    }
    Write-Host "Ambientes: $($videos.Count) video(s) in sync"
}

# GeoGuesser: the Mapillary token lives in ..\mapillary-token.txt (created by you, never committed).
$token = Join-Path (Split-Path $root) "mapillary-token.txt"
if (Test-Path $token) {
    Invoke-Adb @("push", $token, "/sdcard/Android/data/dev.lumen.tv/files/mapillary_token.txt") | Out-Null
    Write-Host "Mapillary token copied"
}

# GeoGuesser admin code (..\lumen-admin.txt): the phone that enters it can remove players.
$admin = Join-Path (Split-Path $root) "lumen-admin.txt"
if (Test-Path $admin) {
    Invoke-Adb @("push", $admin, "/sdcard/Android/data/dev.lumen.tv/files/lumen_admin.txt") | Out-Null
    Write-Host "Admin code copied"
}

Invoke-Adb @("shell", "am force-stop dev.lumen.tv; input keyevent KEYCODE_HOME") | Out-Null

# Android drops an app's accessibility service when the app is force-stopped (as above and by
# the compile step), so switch the phone remote and the media listener back on every time.
Invoke-Adb @("shell", "settings put secure enabled_accessibility_services dev.lumen.tv/dev.lumen.tv.remote.RemoteService; settings put secure accessibility_enabled 1; cmd notification allow_listener dev.lumen.tv/dev.lumen.tv.media.MediaListener") | Out-Null
Write-Host "Lumen installed on $Device"
