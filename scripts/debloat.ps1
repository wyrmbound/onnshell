# Phase 2: disable the approved packages in scripts/debloat.txt with "pm disable-user".
# Each one goes in the ledger first, with "pm enable" as its restore. Safe to run again.
. "$PSScriptRoot\lib.ps1"
$pkgs = Get-Content scripts/debloat.txt | Where-Object { $_ -and $_ -notmatch '^\s*#' } | ForEach-Object { $_.Trim() }
$disabled = Box shell pm list packages -d

Write-Host 'Will run, one per package:'
Write-Host "  adb -s $Serial shell pm disable-user --user 0 <pkg>"
$pkgs | ForEach-Object { Write-Host "    $_" }
Ask 'shell pm disable-user --user 0 <each package above>'

foreach ($p in $pkgs) {
    if ($disabled -contains "package:$p") { Write-Host "already disabled: $p"; continue }
    $restore = "pm enable $p && am broadcast -n $Pkg/.admin.CommandReceiver -a $Pkg.LEDGER_DONE --es target pkg:$p"
    BoxCmd LEDGER_ADD "--es kind shell --es target pkg:$p --es restore '$restore'" | Out-Null
    $out = (Box shell "pm disable-user --user 0 $p 2>&1") -join ' '
    if ($out -match 'new state: disabled-user') { Write-Host "disabled: $p" }
    else { Write-Host "FAILED: ${p}: $out"; BoxCmd LEDGER_DONE "--es target pkg:$p" | Out-Null }
}
PullLedger
