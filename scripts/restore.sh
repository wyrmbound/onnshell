#!/bin/sh
# Replay the ledger: run every restore command, newest change first, ending with
# clearing Device Owner. Returns the box to stock for everything OnnShell changed.
. "$(dirname "$0")/lib.sh"
if ! a shell pm path "$PKG" >/dev/null 2>&1; then
  echo "OnnShell is not installed, so its ledger and restore path are gone. See docs/RESTORE.md."; exit 1
fi
lines=$(a shell "grep -v '^#' $LEDGER_DIR/restore.sh 2>/dev/null" || true)
[ -n "$lines" ] || { echo "Ledger is empty, nothing to restore."; exit 0; }
echo "Restore commands, newest change first:"
echo "$lines"
ask "shell sh $LEDGER_DIR/restore.sh"
a shell sh "$LEDGER_DIR/restore.sh"
echo "Status: $(box_cmd STATUS)"
echo "Owners: $(a shell dpm list-owners | tr '\n' ' ')"
pull_ledger
