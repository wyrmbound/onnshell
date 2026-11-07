# Phase 1: build, install and make OnnShell the Device Owner, then apply its policies.
# Asks before "dpm set-device-owner". Safe to run again (skips what is already done).
. "$PSScriptRoot\lib.ps1"
$apk = 'app/build/outputs/apk/fast/app-fast.apk'

$owners = (Box shell "dpm list-owners 2>&1") -join ' '
if ($owners -notmatch [regex]::Escape($Pkg)) {
    $accounts = ((Box shell dumpsys account | Select-String -Pattern 'Accounts:' | Select-Object -First 1).Line -replace '\D', '')
    if ($accounts -ne '0') { Write-Host "Accounts: $accounts. Device Owner needs 0 accounts. Stopping."; exit 1 }
    if ($owners -notmatch 'no owners') { Write-Host "Another owner is set: $owners. Stopping."; exit 1 }
}

$null | .\gradlew.bat -q assembleFast
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
Box install -r -t $apk
Box shell sync # a power cut right after an update once left the package half written

if ($owners -match [regex]::Escape($Pkg)) {
    Write-Host 'Already Device Owner.'
} else {
    Ask "shell dpm set-device-owner $Admin"
    Box shell dpm set-device-owner $Admin
}

Write-Host "Apply: $(BoxCmd APPLY)"

# Usage access is an app op, not a runtime permission, so the shell grants it. Ledger first.
$restoreUsage = "appops set $Pkg GET_USAGE_STATS default && am broadcast -n $Pkg/.admin.CommandReceiver -a $Pkg.LEDGER_DONE --es target GET_USAGE_STATS"
Write-Host "Ledger: $(BoxCmd LEDGER_ADD "--es kind shell --es target GET_USAGE_STATS --es restore '$restoreUsage'")"
Box shell appops set $Pkg GET_USAGE_STATS allow

Box shell am start -n "$Pkg/.MainActivity" | Out-Null
Write-Host "Status: $(BoxCmd STATUS)"
PullLedger
