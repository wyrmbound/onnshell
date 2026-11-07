# OnnShell

OnnShell is a custom home layer for the onn 4K Pro Gen 2 (Amlogic S905X5M, Android 14) that replaces the Google TV experience without root and without unlocking the bootloader. It runs as a Device Owner app that becomes the HOME screen, hides the stock launcher and promos in a fully reversible way, and gives a fast, flat, gamepad first UI at 1080p. A Shizuku based service (`inputd`) turns the DualShock 4 touchpad into a pointer or touch surface, and a small Python server on the LAN feeds a library of native Android game APKs and their data. Every change to the box is logged in a ledger with a matching restore command, and ADB always stays available for recovery. The full spec is in [docs/SPEC.md](docs/SPEC.md), and `scripts/discover` writes a device report for your own box to `docs/DEVICE.md` (git-ignored).

Part of [onn-native-gpu](https://github.com/wyrmbound/onn-native-gpu): its zero-copy display (`display/`) is how native GPU games reach the screen.

License: MIT for the code (see `LICENSE`); the Outfit font is OFL (`docs/licenses/`); the startup sound is AI-generated and not covered by MIT (`docs/licenses/startup-sound.txt`).
