#!/bin/sh
# Phase 2: disable the approved packages in scripts/debloat.txt with "pm disable-user".
# Each one goes in the ledger first, with "pm enable" as its restore. Safe to run again.
. "$(dirname "$0")/lib.sh"
PKGS=$(grep -v '^#' scripts/debloat.txt | grep -v '^ *$')
DISABLED=$(a shell pm list packages -d)

echo "Will run, one per package:"
echo "  adb -s $SERIAL shell pm disable-user --user 0 <pkg>"
echo "$PKGS" | sed 's/^/    /'
ask "shell pm disable-user --user 0 <each package above>"

for p in $PKGS; do
  if echo "$DISABLED" | grep -qx "package:$p"; then echo "already disabled: $p"; continue; fi
  restore="pm enable $p && am broadcast -n $PKG/.admin.CommandReceiver -a $PKG.LEDGER_DONE --es target pkg:$p"
  box_cmd LEDGER_ADD "--es kind shell --es target pkg:$p --es restore '$restore'" >/dev/null
  out=$(a shell pm disable-user --user 0 "$p" 2>&1 || true)
  case "$out" in
    *"new state: disabled-user"*) echo "disabled: $p" ;;
    *) echo "FAILED: $p: $out"; box_cmd LEDGER_DONE "--es target pkg:$p" >/dev/null ;;
  esac
done
pull_ledger
