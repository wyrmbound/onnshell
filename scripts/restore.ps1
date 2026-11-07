# Replay the ledger: run every restore command, newest change first, ending with
# clearing Device Owner. Returns the box to stock for everything OnnShell changed.
. "$PSScriptRoot\lib.ps1"
if (-not (Box shell "pm path $Pkg 2>/dev/null")) {
    Write-Host 'OnnShell is not installed, so its ledger and restore path are gone. See docs/RESTORE.md.'; exit 1
}
$lines = Box shell "grep -v '^#' $LedgerDir/restore.sh 2>/dev/null; true"
if (-not $lines) { Write-Host 'Ledger is empty, nothing to restore.'; exit 0 }
Write-Host 'Restore commands, newest change first:'
$lines | ForEach-Object { Write-Host $_ }
Ask "shell sh $LedgerDir/restore.sh"
Box shell sh "$LedgerDir/restore.sh"
Write-Host "Status: $(BoxCmd STATUS)"
Write-Host "Owners: $((Box shell dpm list-owners) -join ' ')"
PullLedger
