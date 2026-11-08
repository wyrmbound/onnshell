# OnnShell

OnnShell is a custom home layer for the onn 4K Pro Gen 2 (Amlogic S905X5M, Android 14) that replaces the Google TV experience without root and without unlocking the bootloader. It runs as a Device Owner app that becomes the HOME screen, hides the stock launcher and promos in a fully reversible way, and gives a fast, flat, gamepad first UI at 1080p. A Shizuku based service (`inputd`) turns the DualShock 4 touchpad into a pointer or touch surface, and a small Python server on the LAN feeds a library of native Android game APKs and their data. Every change to the box is logged in a ledger with a matching restore command, and ADB always stays available for recovery. The full spec is in [docs/SPEC.md](docs/SPEC.md), and `scripts/discover` writes a device report for your own box to `docs/DEVICE.md` (git-ignored).

## Install

You need an onn 4K Pro Gen 2 with ADB debugging on (Settings > System > About, tap the build seven times, then
Developer options), **no Google account on the box** (Device Owner needs zero accounts: remove it under Accounts, or
set the box up skipping sign-in), `adb` on your computer, and the box's IP address.

1. Get the APK: download `onnshell.apk` from the latest [release](../../releases), or build it yourself with
   `./gradlew assembleFast` (Android SDK + JDK 17 or newer; the APK lands in `app/build/outputs/apk/fast/`).
2. Tell the scripts where the box is: `echo ONN_HOST=192.168.1.50 > scripts/.env` (your box's IP).
3. Install and provision. It installs the APK, asks once before `dpm set-device-owner`, applies the policies and makes
   OnnShell the HOME screen. From Git Bash, WSL or Linux:
   ```bash
   APK=path/to/onnshell.apk sh scripts/provision.sh
   ```
   From PowerShell:
   ```powershell
   .\scripts\provision.ps1 -Apk path\to\onnshell.apk
   ```
   Without `APK` the script builds the app first.
4. Optional: `scripts/debloat` hides the stock launcher and promos (every change is ledgered, `scripts/restore` undoes
   it). For the touchpad and native games, install [Shizuku](https://github.com/RikkaApps/Shizuku/releases) and run
   `scripts/shizuku-start`; OnnShell restarts it after every boot.
5. Back to stock: `scripts/unprovision`, or follow [docs/RESTORE.md](docs/RESTORE.md).

The release APK is signed with a debug key and marked test-only, so a plain `adb install` needs `-t`; the scripts
pass it.

Part of [onn-native-gpu](https://github.com/wyrmbound/onn-native-gpu): its zero-copy display (`display/`) is how native GPU games reach the screen.

License: MIT for the code (see `LICENSE`); the bundled fonts (Outfit, Bebas Neue, Fredoka, IBM Plex Mono, Nunito) are OFL (`docs/licenses/`); the startup sound is AI-generated and not covered by MIT (`docs/licenses/startup-sound.txt`).
