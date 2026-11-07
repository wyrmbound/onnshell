# Shared setup for the .ps1 scripts. Dot-source it: . "$PSScriptRoot\lib.ps1"
# Finds the box (ONN_HOST in scripts/.env, else the single USB device) and stops if it is not listed.
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$cfg = @{}
$envFile = Join-Path $PSScriptRoot '.env'
if (Test-Path $envFile) {
    Get-Content $envFile | Where-Object { $_ -match '^\s*([A-Z_]+)=(.*)$' } | ForEach-Object { $cfg[$Matches[1]] = $Matches[2].Trim() }
}
$Adb = if ($cfg.ADB) { $cfg.ADB } else { 'adb' }
$Pkg = 'com.xplsh.onnshell'
$Admin = "$Pkg/.admin.ShellAdmin"
$LedgerDir = "/sdcard/Android/data/$Pkg/files"

if ($cfg.ONN_HOST) {
    $port = if ($cfg.ONN_PORT) { $cfg.ONN_PORT } else { '5555' }
    $Serial = "$($cfg.ONN_HOST):$port"
    & $Adb connect $Serial | Out-Null
} else {
    $Serial = (& $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" } | Select-Object -First 1) -replace "\t.*", ''
}
if (-not (& $Adb devices | Where-Object { $_ -eq "$Serial`tdevice" })) {
    Write-Host "Box not listed in 'adb devices' (wanted '$Serial'). Stopping."; exit 1
}
Write-Host "Box: $Serial"

# Empty stdin, so adb never eats the "yes" meant for Ask when input is piped
function Box { $null | & $Adb -s $Serial @args }

# BoxCmd ACTION [extras]: call OnnShell's CommandReceiver, return its result data
function BoxCmd([string]$Action, [string]$Extra = '') {
    $out = $null | & $Adb -s $Serial shell "am broadcast -n $Pkg/.admin.CommandReceiver -a $Pkg.$Action $Extra"
    $m = [regex]::Match(($out -join "`n"), 'data="(.*)"')
    if ($m.Success) { $m.Groups[1].Value } else { '' }
}

# Ask "command": print the exact adb command and wait for "yes" (reads stdin, so it can be piped)
function Ask([string]$Cmd) {
    Write-Host "About to run on the box:"
    Write-Host "  adb -s $Serial $Cmd"
    Write-Host -NoNewline 'Type yes to continue: '
    $ans = [Console]::In.ReadLine()
    if ($ans -ne 'yes') { Write-Host 'Cancelled.'; exit 1 }
}

function PullLedger {
    New-Item -ItemType Directory -Force ledger | Out-Null
    # Device side check first: PowerShell 5.1 turns native stderr into errors
    $has = Box shell "[ -f $LedgerDir/ledger.json ] && echo yes"
    if ($has -eq 'yes') { Box pull "$LedgerDir/ledger.json" ledger/ledger.json | Out-Null; Write-Host 'Ledger copied to ledger/ledger.json' }
}
