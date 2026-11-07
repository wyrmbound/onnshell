# Phase 4b helper: keep Shizuku running on the box. Shizuku stops on every reboot; this box keeps
# network ADB on port 5555 across reboots, so a PC or home server can start it again with no USB
# and no tap on the TV. Every 30 s: if the box answers and Shizuku is down, start it.
param([int]$Interval = 30)
$last = ''
while ($true) {
    $msg = (powershell -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot\shizuku-start.ps1" 2>&1 | Select-Object -Last 1) -join ''
    # Only log changes, so a long running helper does not fill its log with the same line
    if ($msg -ne $last) { Write-Host "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $msg"; $last = $msg }
    Start-Sleep $Interval
}
