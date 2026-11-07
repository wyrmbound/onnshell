#!/bin/sh
# Phase 4b helper: keep Shizuku running on the box. Shizuku stops on every reboot; this box keeps
# network ADB on port 5555 across reboots, so a PC or home server can start it again with no USB
# and no tap on the TV. Every 30 s: if the box answers and Shizuku is down, start it.
# Run it in a terminal, as a service, or in Docker (server/shizuku-helper).
cd "$(dirname "$0")" || exit 1
INTERVAL="${WATCH_INTERVAL:-30}"
last=""
while :; do
  msg=$(sh ./shizuku-start.sh 2>&1 | tail -1)
  # Only log changes, so a long running helper does not fill its log with the same line
  if [ "$msg" != "$last" ]; then echo "$(date '+%Y-%m-%d %H:%M:%S') $msg"; last="$msg"; fi
  sleep "$INTERVAL"
done
