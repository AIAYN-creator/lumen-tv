# Runs a shell command on the Chromecast, reconnecting when wireless ADB drops.
# Usage: .\tv.ps1 "pm list packages -3"
param(
    [Parameter(Mandatory = $true)][string]$Command,
    [string]$Device = "192.168.0.18:5555",
    [int]$Tries = 8
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$marker = "__LUMEN_DONE__"

for ($i = 1; $i -le $Tries; $i++) {
    & $adb disconnect $Device *> $null
    & $adb connect $Device *> $null
    Start-Sleep -Milliseconds 500
    $out = & $adb -s $Device shell "$Command; echo $marker" 2>&1 | ForEach-Object { "$_" }
    if ($out -contains $marker) {
        $out | Where-Object { $_ -ne $marker }
        exit 0
    }
    Start-Sleep -Seconds 2
}
Write-Error "Couldn't reach $Device after $Tries tries"
exit 1
