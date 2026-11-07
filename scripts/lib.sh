# Shared setup for the .sh scripts. Source it: . "$(dirname "$0")/lib.sh"
# Finds the box (ONN_HOST in scripts/.env, else the single USB device) and stops if it is not listed.
set -eu
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: do not rewrite /data/... paths
cd "$(dirname "$0")/.."
[ -f scripts/.env ] && . ./scripts/.env
ADB="${ADB:-adb}"
PKG=com.xplsh.onnshell
ADMIN=$PKG/.admin.ShellAdmin
LEDGER_DIR=/sdcard/Android/data/$PKG/files

if [ -n "${ONN_HOST:-}" ]; then
  SERIAL="$ONN_HOST:${ONN_PORT:-5555}"
  "$ADB" connect "$SERIAL" >/dev/null 2>&1 || true
else
  SERIAL=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')
fi
if ! "$ADB" devices | awk -v s="$SERIAL" '$1==s && $2=="device" {f=1} END {exit !f}'; then
  echo "Box not listed in 'adb devices' (wanted '$SERIAL'). Stopping."; exit 1
fi
echo "Box: $SERIAL"

# stdin is /dev/null so adb never eats the "yes" meant for ask() when input is piped
a() { "$ADB" -s "$SERIAL" "$@" </dev/null; }

# box_cmd ACTION [extras]: call OnnShell's CommandReceiver, print its result data
box_cmd() {
  a shell "am broadcast -n $PKG/.admin.CommandReceiver -a $PKG.$1 ${2:-}" | sed -n 's/.*data="\(.*\)"$/\1/p'
}

# ask "command": print the exact adb command and wait for "yes"
ask() {
  echo "About to run on the box:"
  echo "  adb -s $SERIAL $1"
  printf 'Type yes to continue: '
  read -r ans || ans=""
  [ "$ans" = "yes" ] || { echo "Cancelled."; exit 1; }
}

pull_ledger() {
  mkdir -p ledger
  a pull "$LEDGER_DIR/ledger.json" ledger/ledger.json >/dev/null 2>&1 && echo "Ledger copied to ledger/ledger.json" || true
}
