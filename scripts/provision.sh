#!/bin/sh
# Phase 1: build, install and make OnnShell the Device Owner, then apply its policies.
# Asks before "dpm set-device-owner". Safe to run again (skips what is already done).
. "$(dirname "$0")/lib.sh"
# APK=path/to/onnshell.apk skips the build (for example the APK from the GitHub release)
APK=${APK:-}

owners=$(a shell dpm list-owners 2>&1)
if ! echo "$owners" | grep -q "$PKG"; then
  accounts=$(a shell dumpsys account | grep -m1 'Accounts:' | tr -dc '0-9')
  [ "$accounts" = "0" ] || { echo "Accounts: $accounts. Device Owner needs 0 accounts. Stopping."; exit 1; }
  echo "$owners" | grep -q "no owners" || { echo "Another owner is set: $owners. Stopping."; exit 1; }
fi

if [ -z "$APK" ]; then APK=app/build/outputs/apk/fast/app-fast.apk; ./gradlew -q assembleFast </dev/null; fi
[ -f "$APK" ] || { echo "APK not found: $APK"; exit 1; }
a install -r -t "$APK"
a shell sync # a power cut right after an update once left the package half written

if echo "$owners" | grep -q "$PKG"; then
  echo "Already Device Owner."
else
  ask "shell dpm set-device-owner $ADMIN"
  a shell dpm set-device-owner "$ADMIN"
fi

echo "Apply: $(box_cmd APPLY)"

# Usage access is an app op, not a runtime permission, so the shell grants it. Ledger first.
RESTORE_USAGE="appops set $PKG GET_USAGE_STATS default && am broadcast -n $PKG/.admin.CommandReceiver -a $PKG.LEDGER_DONE --es target GET_USAGE_STATS"
echo "Ledger: $(box_cmd LEDGER_ADD "--es kind shell --es target GET_USAGE_STATS --es restore '$RESTORE_USAGE'")"
a shell appops set "$PKG" GET_USAGE_STATS allow

a shell am start -n "$PKG/.MainActivity" >/dev/null
echo "Status: $(box_cmd STATUS)"
pull_ledger
