#!/bin/sh
# Start Shizuku on the box over network ADB (needed after every reboot; no root).
# Same steps as Shizuku's own start.sh: copy the starter out of the app and run it as shell.
. "$(dirname "$0")/lib.sh"
SZ=moe.shizuku.privileged.api
a shell "pm path $SZ" >/dev/null 2>&1 || { echo "Shizuku is not installed on the box."; exit 1; }
# Already up: leave it. Restarting it would cut OnnShell's link and the touchpad for a moment.
if a shell "ps -A -o USER,NAME | grep -q 'shell.*shizuku_server'"; then echo "Shizuku is already running."; exit 0; fi
a shell 'LIB=$(dumpsys package '"$SZ"' | grep -m1 legacyNativeLibraryDir | sed "s/.*legacyNativeLibraryDir=//")
cp "$LIB/arm/libshizuku.so" /data/local/tmp/shizuku_starter && chmod 700 /data/local/tmp/shizuku_starter && /data/local/tmp/shizuku_starter'
sleep 2
if a shell "ps -A -o USER,NAME | grep -q 'shell.*shizuku_server'"; then echo "Shizuku is running."; else echo "Shizuku did not start."; exit 1; fi

# Grant OnnShell Shizuku's permission. Ledger first, restore is pm revoke. Shizuku 13 also keeps its
# own allow list, so the first time it still asks once on the TV ("Allow all the time").
PERM=moe.shizuku.manager.permission.API_V23
if ! a shell "dumpsys package $PKG | grep -q '$PERM: granted=true'"; then
  RESTORE="pm revoke $PKG $PERM && am broadcast -n $PKG/.admin.CommandReceiver -a $PKG.LEDGER_DONE --es target shizuku-access"
  box_cmd LEDGER_ADD "--es kind shell --es target shizuku-access --es restore '$RESTORE'" >/dev/null
  a shell pm grant "$PKG" "$PERM" && echo "OnnShell may now use Shizuku."
fi
