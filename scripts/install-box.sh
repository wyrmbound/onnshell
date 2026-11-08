#!/bin/bash
# Installs an OnnShell build on the box and makes sure the home screen comes back.
# Why: installing over the running home app can race its restart; on 2026-11-11 Android killed the new process
# mid-start ("start not valid, killing pid") and left the TV black with no home app until it was launched by hand.
# Usage: ONN=<box ip:port> scripts/install-box.sh [apk]   (default apk: the fast build)
set -u
export MSYS_NO_PATHCONV=1
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
S="${ONN:?set ONN to the box address, e.g. ONN=host:5555}"
APK="${1:-app/build/outputs/apk/fast/app-fast.apk}"
a() { "$ADB" -s "$S" "$@"; }
up() { [ -n "$(a shell pidof com.xplsh.onnshell | tr -d '\r')" ] && a shell 'dumpsys window displays' | grep -q "mCurrentFocus=Window{[^}]*com.xplsh.onnshell/"; }
a connect "$S" >/dev/null 2>&1
# With the TV off the box sleeps within seconds and shows no window at all, which would look like a dead home screen:
# keep it awake for the check, then put the owner's setting back.
SO=$(a shell settings get global stay_on_while_plugged_in | tr -d '')
a shell 'svc power stayon true; input keyevent KEYCODE_WAKEUP'
trap 'a shell "settings put global stay_on_while_plugged_in ${SO:-0}"' EXIT
a install -r -t "$APK" | tail -1 || exit 1
ok=0
for i in $(seq 1 20); do
    sleep 1
    # three steady seconds, so a window from the old process going away does not count
    if up; then ok=$((ok + 1)); else ok=0; fi
    [ "$ok" -ge 3 ] && { echo "home is up (${i} s)"; exit 0; }
    # after 4 s with no home window, start it ourselves (the home intent, so Android picks OnnShell as it would)
    [ "$ok" = 0 ] && { [ "$i" = 5 ] || [ "$i" = 12 ]; } && a shell 'am start -W -a android.intent.action.MAIN -c android.intent.category.HOME' >/dev/null
done
echo "HOME DID NOT COME BACK: check the TV" >&2; exit 1
