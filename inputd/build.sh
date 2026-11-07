#!/bin/sh
# Build inputd as a static aarch64 binary into the app's assets (the box has a 64-bit kernel;
# its 32-bit userspace does not matter for a static binary). Needs zig, or: python -m pip install ziglang
set -eu
cd "$(dirname "$0")"
OUT=../app/src/main/assets/onnshell-inputd
mkdir -p ../app/src/main/assets
if command -v zig >/dev/null 2>&1; then ZIG="zig"
elif python3 -m ziglang version >/dev/null 2>&1; then ZIG="python3 -m ziglang"
else ZIG="python -m ziglang"; fi
$ZIG cc -target aarch64-linux-musl -static -Os -s -Wall -o "$OUT" inputd.c
echo "Built $OUT"
