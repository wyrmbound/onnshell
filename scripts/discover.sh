#!/bin/sh
# Phase 0 discovery. Read only. Writes docs/DEVICE.md.
# The only file pushed is the evdev probe, and it is removed before exit.
. "$(dirname "$0")/lib.sh"
REMOTE=/data/local/tmp/onnshell-evprobe
BIN=build/onnshell-evprobe

if [ ! -f "$BIN" ]; then
  mkdir -p build
  if command -v zig >/dev/null 2>&1; then ZIG="zig"
  elif python3 -m ziglang version >/dev/null 2>&1; then ZIG="python3 -m ziglang"
  else ZIG="python -m ziglang"; fi
  $ZIG cc -target aarch64-linux-musl -static -Os -s -o "$BIN" scripts/evprobe.c
fi

cleanup() { a shell rm -f "$REMOTE" >/dev/null 2>&1 || true; }
trap cleanup EXIT
a push "$BIN" "$REMOTE" >/dev/null
a shell chmod 755 "$REMOTE"
"$ADB" -s "$SERIAL" shell sh < scripts/probe.sh > docs/DEVICE.md
cleanup
if a shell ls "$REMOTE" >/dev/null 2>&1; then
  echo "WARNING: $REMOTE is still on the box"; exit 1
fi
echo "Probe removed from box. Wrote docs/DEVICE.md"
