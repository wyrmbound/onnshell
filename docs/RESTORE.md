# Restore to stock

Every change OnnShell makes is in its ledger on the box, each with the command that undoes it:

- `/sdcard/Android/data/com.xplsh.onnshell/files/ledger.json`, open changes
- `/sdcard/Android/data/com.xplsh.onnshell/files/restore.sh`, their restore commands, newest first
- `/sdcard/Android/data/com.xplsh.onnshell/files/history.log`, every add and undo

The scripts copy the ledger to `ledger/ledger.json` in this repo after each run.

## Why the order matters

Packages hidden by Device Owner can only be unhidden by a Device Owner. The shell user cannot (`pm unhide` fails with "Neither user 2000 nor current process has android.permission.MANAGE_USERS"). So OnnShell must unhide everything before it gives up Device Owner, and it refuses to clear Device Owner while any hide is still in the ledger.

## Normal ways back (pick one)

1. `scripts/unprovision.sh` or `.ps1`: over ADB, unhides what OnnShell hid, drops the HOME lock, clears Device Owner, then runs any shell side restores (usage access).
2. `scripts/restore.sh` or `.ps1`: replays `restore.sh` from the ledger, newest change first, ending with clearing Device Owner.

There is no on-TV remove option anymore (the owner asked for it to go). The ADB paths above do the same thing.

After any of these, `adb shell dpm list-owners` shows "no owners", and the Google TV home is back. Android may ask which home app to use the first time HOME is pressed. OnnShell stays installed, uninstall it from Settings if wanted.

## After a power cut, Google TV is back

Seen once (2026-11-05): the box lost power right after an OnnShell update. The update was not on
disk yet, so after boot the package had no code, the Device Owner admin was no longer active (the
owner record survived), and the Google TV home came back. Fix, over network ADB:

1. Reinstall the same build: `adb install -r -t app/build/outputs/apk/fast/app-fast.apk`, then `adb shell sync`.
2. Re-activate the admin: `adb shell dpm set-active-admin --user 0 com.xplsh.onnshell/.admin.ShellAdmin`.
3. Re-apply the policies: `adb shell am broadcast -n com.xplsh.onnshell/.admin.CommandReceiver -a com.xplsh.onnshell.APPLY`
   (it re-hides the Google TV home and takes HOME again).
4. If Shizuku is down: `scripts/shizuku-start`.

The provision script now runs `sync` after every install so an update is on disk at once.

## If OnnShell itself is broken

1. Reinstall the same build: `adb install -r -t app/build/outputs/apk/fast/app-fast.apk` (it must be signed with the same key, or the update is refused). Device Owner and the ledger survive a reinstall.
2. Then use way 1 or 2 above.

## Last resort

The debug and fast builds are `testOnly`, so this always removes Device Owner over ADB:

```
adb shell dpm remove-active-admin com.xplsh.onnshell/.admin.ShellAdmin
```

Do this only after the hidden packages are visible again. If they are still hidden, they stay hidden until a Device Owner unhides them: run `scripts/provision` again (it needs `Accounts: 0`), then `scripts/unprovision`. A factory reset also brings everything back.
