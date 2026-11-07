# Build inputd as a static aarch64 binary into the app's assets (the box has a 64-bit kernel;
# its 32-bit userspace does not matter for a static binary). Needs zig, or: python -m pip install ziglang
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$out = '../app/src/main/assets/onnshell-inputd'
New-Item -ItemType Directory -Force ../app/src/main/assets | Out-Null
if (Get-Command zig -ErrorAction SilentlyContinue) { zig cc -target aarch64-linux-musl -static -Os -s -Wall -o $out inputd.c }
else { python -m ziglang cc -target aarch64-linux-musl -static -Os -s -Wall -o $out inputd.c }
if ($LASTEXITCODE -ne 0) { throw 'inputd build failed' }
Write-Host "Built $out"
