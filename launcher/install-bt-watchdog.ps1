# Registers (or removes) the scheduled task that runs bt-watchdog.ps1.
#
# The task runs once a day, early, and does nothing almost every time: the
# watchdog only acts when the Bluetooth stack has been up for a week and the
# TV is sitting on its dashboard. See bt-watchdog.ps1 for why this exists.
#
#   .\install-bt-watchdog.ps1           # register
#   .\install-bt-watchdog.ps1 -Remove   # undo
param(
    [switch]$Remove
)

$ErrorActionPreference = "Stop"
$taskName = "Lumen BT watchdog"
$script = Join-Path $PSScriptRoot "bt-watchdog.ps1"

if ($Remove) {
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
    Write-Output "Tarea '$taskName' eliminada."
    exit 0
}

if (-not (Test-Path $script)) { throw "No encuentro bt-watchdog.ps1 junto a este script" }

$action = New-ScheduledTaskAction -Execute "powershell.exe" `
    -Argument "-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$script`""

# Daily at 04:30, when the house is asleep and the TV is on its dashboard.
# With StartWhenAvailable, a PC that was off at that hour runs the check on
# the next boot instead; the watchdog then skips it if somebody is watching.
$trigger = New-ScheduledTaskTrigger -Daily -At "04:30"

$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable `
    -DontStopOnIdleEnd -ExecutionTimeLimit (New-TimeSpan -Minutes 5) -Hidden

Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger `
    -Settings $settings -Description "Reinicia el Bluetooth del Chromecast antes de que se atasque." `
    -Force | Out-Null

Write-Output "Tarea '$taskName' registrada: cada día a las 04:30, oculta, sin pedir nada."
Write-Output "Próxima ejecución: $((Get-ScheduledTaskInfo -TaskName $taskName).NextRunTime)"
Write-Output "Comprobar:  Get-ScheduledTask -TaskName '$taskName'"
Write-Output "Quitar:     .\install-bt-watchdog.ps1 -Remove"
