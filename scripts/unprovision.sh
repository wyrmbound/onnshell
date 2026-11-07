#!/bin/sh
# Remove OnnShell control, same as the in-app option: unhide what it hid, drop the HOME lock,
# clear Device Owner (last), then run any shell side restores left in the ledger.
. "$(dirname "$0")/lib.sh"
ask "shell am broadcast -n $PKG/.admin.CommandReceiver -a $PKG.UNPROVISION"
result=$(box_cmd UNPROVISION)
echo "Unprovision: ${result:-no answer, is OnnShell installed? See docs/RESTORE.md}"
case "$result" in
  unprovisioned*)
    if a shell "grep -qv '^#' $LEDGER_DIR/restore.sh 2>/dev/null"; then
      echo "Running shell restores:"
      a shell sh "$LEDGER_DIR/restore.sh"
    fi ;;
  *) exit 1 ;;
esac
echo "Owners: $(a shell dpm list-owners | tr '\n' ' ')"
pull_ledger
