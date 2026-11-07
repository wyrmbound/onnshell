# Remove OnnShell control, same as the in-app option: unhide what it hid, drop the HOME lock,
# clear Device Owner (last), then run any shell side restores left in the ledger.
. "$PSScriptRoot\lib.ps1"
Ask "shell am broadcast -n $Pkg/.admin.CommandReceiver -a $Pkg.UNPROVISION"
$result = BoxCmd UNPROVISION
if (-not $result) { $result = 'no answer, is OnnShell installed? See docs/RESTORE.md' }
Write-Host "Unprovision: $result"
if ($result -notlike 'unprovisioned*') { exit 1 }
$left = Box shell "grep -v '^#' $LedgerDir/restore.sh 2>/dev/null; true"
if ($left) {
    Write-Host 'Running shell restores:'
    Box shell sh "$LedgerDir/restore.sh"
}
Write-Host "Owners: $((Box shell dpm list-owners) -join ' ')"
PullLedger
