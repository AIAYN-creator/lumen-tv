# Keeps the Chromecast's Bluetooth stack from rotting.
#
# The BLE remote stops accepting reconnections after the stack has been up for
# weeks: it crashed once by itself on 30 Sep and had to be cycled by hand on
# 19 Oct, 18 days into the same boot. Restarting the stack fixes it in four
# seconds; unplugging the Chromecast is not a fix, it is a reboot.
#
# So this restarts the stack before it rots, and only while nobody is using
# the TV. The BLE remote reconnects on the next keypress anyway, which is what
# it already does after half an hour idle, so an idle restart costs nothing.
#
#   .\bt-watchdog.ps1            # what a scheduled task runs: act only if due
#   .\bt-watchdog.ps1 -Status    # report and change nothing
#   .\bt-watchdog.ps1 -Force     # cycle right now, whatever the clock says
param(
    [string]$Device = "192.168.0.18:5555",
    # Days the stack may stay up before it is cycled. It rotted at 18.
    [int]$MaxDays = 7,
    [switch]$Force,
    [switch]$Status
)

$ErrorActionPreference = "Stop"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$logFile = Join-Path $PSScriptRoot "bt-watchdog.log"

function Write-Log([string]$message) {
    $line = "{0}  {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $message
    Add-Content -Path $logFile -Value $line -Encoding UTF8
    Write-Output $line
}

# Wireless ADB drops to "offline" after idle, so reconnect before every run.
function Connect-Tv {
    for ($i = 1; $i -le 6; $i++) {
        & $adb disconnect $Device *> $null
        & $adb connect $Device *> $null
        Start-Sleep -Milliseconds 600
        $state = (& $adb -s $Device get-state 2>$null | Out-String).Trim()
        if ($state -eq "device") { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

# Filter with plain `grep`, never `head` or `grep -m1`: those close the pipe
# early, dumpsys then writes "Broken pipe" to stderr, and PowerShell turns a
# native command's stderr into a terminating error even though it worked.
function Invoke-Tv([string]$command) {
    (& $adb -s $Device shell $command 2>$null | Out-String)
}

if (-not (Connect-Tv)) {
    # The TV being unplugged or the network being down is not a failure worth
    # shouting about: the task runs again in an hour.
    Write-Log "sin conexión con $Device, se reintentará"
    exit 0
}

$btDump = Invoke-Tv "dumpsys bluetooth_manager | grep -E '^  state:|time since enabled:'"
$enabled = $btDump -match "state:\s*ON"

# "time since enabled: 00:05:39.373" — the hours field keeps growing past 24.
$hours = 0
if ($btDump -match "time since enabled:\s*(\d+):(\d+):(\d+)") { $hours = [int]$Matches[1] }
$days = [math]::Round($hours / 24, 1)

$foreground = Invoke-Tv "dumpsys activity activities | grep ResumedActivity"
# Lumen's home is the permanent dashboard, so it being in front means the TV is
# idle. Anything else — Prime, Spotify, GeoGuesser — means somebody is there.
$idle = $foreground -match "dev\.lumen\.tv/\.MainActivity"

if ($Status) {
    Write-Output "Bluetooth: $(if ($enabled) { 'ON' } else { 'OFF' }), encendido desde hace $days días"
    Write-Output "En primer plano: $(if ($idle) { 'Lumen (inactivo)' } else { 'otra app, hay alguien' })"
    Write-Output "Se reiniciaría: $(if ($enabled -and $days -ge $MaxDays -and $idle) { 'sí' } else { 'no' })"
    exit 0
}

$due = $days -ge $MaxDays
if (-not $Force) {
    if (-not $enabled) { Write-Log "el Bluetooth está apagado, no se toca"; exit 0 }
    if (-not $due) { exit 0 }
    # Only while the dashboard is in front. If somebody is watching Prime or
    # playing GeoGuesser, this waits for the next run rather than cutting the
    # remote for ten seconds.
    if (-not $idle) { Write-Log "toca reiniciar ($days días) pero la TV está en uso, se deja para luego"; exit 0 }
}

Write-Log "reiniciando el Bluetooth tras $days días encendido"
Invoke-Tv "cmd bluetooth_manager disable" | Out-Null
Start-Sleep -Seconds 4
Invoke-Tv "cmd bluetooth_manager enable" | Out-Null
Start-Sleep -Seconds 6

$after = Invoke-Tv "dumpsys bluetooth_manager | grep -E '^  state:'"
if ($after -match "state:\s*ON") {
    Write-Log "Bluetooth reiniciado y encendido"
    exit 0
}

# Worth shouting about: the stack went down and did not come back.
Write-Log "ERROR: el Bluetooth no volvió a encenderse"
exit 1
