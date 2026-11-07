# Phase 0 discovery. Read only. Writes docs/DEVICE.md.
# The only file pushed is the evdev probe, and it is removed before exit.
. "$PSScriptRoot\lib.ps1"
$remote = '/data/local/tmp/onnshell-evprobe'
$bin = 'build/onnshell-evprobe'

if (-not (Test-Path $bin)) {
    New-Item -ItemType Directory -Force build | Out-Null
    if (Get-Command zig -ErrorAction SilentlyContinue) { zig cc -target aarch64-linux-musl -static -Os -s -o $bin scripts/evprobe.c }
    else { python -m ziglang cc -target aarch64-linux-musl -static -Os -s -o $bin scripts/evprobe.c }
    if (-not (Test-Path $bin)) { throw "Could not build $bin. Install zig (or: python -m pip install ziglang)." }
}

try {
    Box push $bin $remote | Out-Null
    Box shell chmod 755 $remote
    # cmd redirection keeps bytes exact (PowerShell 5.1 would re-encode stdin and stdout)
    cmd /c "`"$Adb`" -s $Serial shell sh < scripts\probe.sh > docs\DEVICE.md"
} finally {
    Box shell "rm -f $remote" | Out-Null
}
# stderr is silenced on the box side: PowerShell 5.1 turns native stderr into errors
Box shell "ls $remote >/dev/null 2>&1"
if ($LASTEXITCODE -eq 0) { Write-Host "WARNING: $remote is still on the box"; exit 1 }
Write-Host "Probe removed from box. Wrote docs/DEVICE.md"
