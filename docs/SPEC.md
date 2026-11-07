# OnnShell SPEC

Custom "system" layer for the Walmart onn 4K Pro Gen 2. Replaces the Google TV experience without root and without unlocking the bootloader.

## 1. Target device

| Item | Value |
|---|---|
| Device | onn 4K Pro Streaming Device Gen 2 (2026), Google TV with Gemini |
| SoC | Amlogic S905X5M, 4x Cortex-A55 |
| GPU | Mali-G310, single shader core, about 50 GFLOPS fp32 |
| RAM / storage | 3 GB / 32 GB |
| Ports | HDMI, Ethernet, USB-A, USB-C (power/ADB) |
| Bootloader | Locked, no known unlock. Out of scope |
| Root | Not available. Out of scope |

Exact Android version, build fingerprint and input nodes are recorded by Phase 0 in `docs/DEVICE.md`.

## 2. Hard rules

1. NO EMULATION. No emulators, cores, frontends or references to them anywhere: code, docs, whitelist, server. Games are native Android APKs only (my own games, native ports built from decompilation projects using my own game data).
2. No root, no bootloader work, no `pm uninstall`.
3. Every system change is reversible and recorded in a ledger with a matching restore command.
4. Ask before every destructive or hard to reverse ADB action. Print the exact command and wait for "yes".
5. ADB and developer options are never blocked. Recovery over ADB must always be possible.
6. Kotlin, Gradle Kotlin DSL, Jetpack Compose for TV. Minimal dependencies. No Google Play Services dependency.
7. Package: `com.xplsh.onnshell`.
8. Everything renders at 1080p. No blur, no layered transparency, no video backgrounds, no heavy animation.
9. No em dashes or en dashes in any string, doc or comment. Use commas, periods or hyphens.
10. Work in phases. Commit and stop at the end of each phase with a report.

## 3. Current device state (as of setup)

- Google account was added during setup to reach developer options, then removed with "Remove and go offline" (no reset).
- Device Owner requires zero accounts at provisioning time. Phase 0 must confirm `Accounts: 0`.
- After Device Owner is set, a Google account may be added back (for Play Store). OnnShell must keep working with or without an account.

## 4. ADB transport

| Transport | Status | Notes |
|---|---|---|
| USB-C | Primary for first setup | Always works |
| Ethernet + `adb tcpip 5555` | Primary for daily dev | Needs one USB step after every reboot |
| Wireless debugging (Android 11+) | Secondary | Many firmwares only allow it with Wi-Fi connected, even if Ethernet is up. Phase 0 tests this |
| HDMI | Not possible | No data channel |

Router has a DHCP reservation for the box. Scripts read the box address from `scripts/.env` (`ONN_HOST=192.168.x.x`).

## 5. Architecture

```
onnshell/
  app/       Device Owner shell: HOME, UI, library, installer, admin, ledger
  inputd/    Shizuku UserService: DS4 touchpad reader and input injector
  server/    Python library server for PC or TrueNAS
  scripts/   discover, provision, unprovision, debloat, restore, install, shizuku-start
  docs/      SPEC.md, DEVICE.md (generated), RESTORE.md
```

Privilege model:
- Device Owner (via `dpm set-device-owner`): HOME lock, hide packages, silent install, permission grants, usage stats.
- Shizuku (shell uid): raw evdev reading and input injection for the touchpad.
- Nothing requires root.

## 6. Phases

### Phase 0: Discovery (read only)
`scripts/discover.sh` and `.ps1` write `docs/DEVICE.md` with:
- `getprop` (fingerprint, device, SDK, build), `dumpsys account`, `pm list packages -f`, `pm list packages -d`
- Current HOME resolver, `dumpsys SurfaceFlinger | grep -i gles`, Vulkan feature flags, display modes
- Network: active interface (Ethernet or Wi-Fi), whether Wi-Fi and Ethernet can be up at the same time, whether Wireless debugging can be enabled while on Ethernet only
- DS4 paired over Bluetooth: `getevent -lp`, the touchpad `/dev/input/eventX` node, ABS ranges, `BTN_LEFT` presence, node permissions
- Small static arm64 C test in `/data/local/tmp` that opens the touchpad node as shell user and tries `EVIOCGRAB`
Changes nothing. Stop and report.

### Phase 1: Device Owner
- `DeviceAdminReceiver` (`.admin.ShellAdmin`) with needed policies.
- `scripts/provision.sh`: `dpm set-device-owner com.xplsh.onnshell/.admin.ShellAdmin` (ask first).
- On success: `addPersistentPreferredActivity` for HOME, hide Google TV launcher and setup leftovers with `setApplicationHidden`, self grant runtime permissions with `setPermissionGrantState`, grant usage stats access.
- Ledger: every change written to local JSON plus `scripts/restore.sh`.
- `scripts/unprovision.sh` and an in-app "Remove OnnShell control" option (confirm dialog plus long-press combo): restore all hidden packages and original HOME, then `clearDeviceOwnerApp()`.

### Phase 2: Debloat
- Candidate list from `docs/DEVICE.md`: ads, telemetry, Google TV home, promos, Walmart extras. Each marked SAFE / RISKY / KEEP with a one line reason. Show me, wait for approval.
- Apply only with `pm disable-user --user 0` or `setApplicationHidden`. Auto append to `restore.sh`.
- Must keep: Bluetooth, input, settings, package installer, system UI essentials, CEC, display, Wi-Fi, Ethernet, anything Shizuku needs.
- Reboot test. If OnnShell is not reached after reboot, print restore guidance.

### Phase 3: Shell UI (HOME)
- Single activity, Compose for TV, cold start under 1 s.
- Pure black background, flat monochrome icons, one accent color selectable in settings, large type for TV distance.
- Rows:
  1. Games: native game APKs from the library, cached cover art
  2. Apps: whitelist I control (Jellyfin, Moonlight, my own apps). Everything else hidden
  3. System: Wi-Fi, Ethernet info, Bluetooth pairing (settings panels), controller test, input mode, storage, library sync, ADB status (address and port), about, remove OnnShell
  4. Power: sleep, reboot
- Spanish (Puerto Rico) and English, toggle in settings.
- Gamepad: A select, B back, X details, Y options, Start menu. PS button opens quick menu if interceptable, else Select+Start.
- Explicit intent launches. Focus restored on return.

### Phase 4: inputd (DS4 touchpad)
- Detect DS4 by VID 054C, PID 05C4 or 09CC. Handle hotplug and reconnect after sleep.
- Read touchpad node directly (evdev, multitouch protocol B). Use `EVIOCGRAB` if Phase 0 proved it works, so the built-in pointer does not double react.
- Modes:
  - POINTER (default everywhere): relative cursor with acceleration, drawn in a `TYPE_APPLICATION_OVERLAY` window. Pad click = tap. Two finger vertical swipe = scroll.
  - TOUCH (absolute): pad mapped to a configurable screen rectangle with aspect correction (pad is about 2:1). Real down/move/up touch events.
- Injection via `InputManager.injectInputEvent` through Shizuku binder wrapper. Fall back to `input` commands only if needed. Document latency of both.
- Per-app profiles: JSON by package name, auto switch on foreground change (`UsageStatsManager`).
- Toggle combo: Share + pad click, shows a 1 s mode overlay.
- Latency target under 16 ms evdev to injection, shown on a debug screen.

### Phase 4b: Shizuku after reboot
Shizuku without root must be restarted after every reboot. Implement in this priority order, based on Phase 0 results:
1. If Wireless debugging works on this box (Wi-Fi connected): start Shizuku on device via wireless debugging. OnnShell shows one full screen "Press A to enable controls" step at boot if any tap is unavoidable.
2. If the box runs Ethernet only and wireless debugging is unavailable: `scripts/shizuku-start.sh` on my PC or home server connects over the network and runs the Shizuku start command. Provide a small always-on helper (Docker container for TrueNAS or CasaOS) that detects the box coming online and starts Shizuku automatically, if network ADB is reachable after reboot.
3. Document which path is active in `docs/SPEC.md` and show status in System.
If inputd is not running, OnnShell still works fully with the gamepad. The touchpad is an enhancement, never a requirement to navigate.

**Active path (2026-11-05): path 2.** This box keeps network ADB on port 5555 across reboots (measured in Phase 2), so no USB step and no tap on the TV is needed. `scripts/shizuku-start` starts Shizuku over the network (and does nothing if it already runs); `scripts/shizuku-watch` repeats that every 30 s from a PC, and `server/shizuku-helper/Dockerfile` runs it always-on on TrueNAS or CasaOS. When Shizuku comes up, OnnShell starts inputd by itself. System > ADB status shows the Shizuku state, System > Input mode shows inputd.

**How Phase 4 was built, and why it differs from the text above:**
- POINTER mode is Android's own touchpad pointer (the DS4 pad is a `TOUCHPAD` class device, stock Android already moves a pointer, clicks and scrolls with two fingers). inputd leaves the pad alone in this mode instead of drawing a second pointer in an overlay.
- TOUCH mode is `inputd/inputd.c`, a small static native program run as the shell user through Shizuku. It grabs the pad (EVIOCGRAB) and replays the fingers on a virtual touchscreen created with `/dev/uinput` (the shell user is in group uhid). This replaces `InputManager.injectInputEvent`: no hidden APIs, and the latency is measured in microseconds, not milliseconds.
- inputd dies with OnnShell (Shizuku ends its processes), and closing its fds releases the grab, so the pad can never stay stuck.
- Per-app profiles are JSON by package name in OnnShell's settings (Y on an app, or ADB `PROFILE --es pkg P --ez touch true --es rect x,y,w,h`). The default rectangle is the full width with the pad's aspect ratio, centered.
- The Share + pad click toggle shows a short Android toast instead of an overlay window (no overlay permission needed).

### Phase 5: Library sync
- `server/`: Python 3 stdlib server with optional Dockerfile for TrueNAS. Scans a folder, serves `index.json` and files over LAN HTTP.
- Entry fields: id, title, type (`apk` or `data`), version, size, sha256, cover, data target path rules, required APK id.
- Shell: configurable server address (mDNS if cheap), full catalog, download on select, sha256 verify, silent APK install via `PackageInstaller` sessions, data placed per manifest (report clearly if a path is not writable), update detection, uninstall, resumable downloads.
- Storage budget with LRU eviction of data packs, never saves. USB drive support if mounted.
- LAN only. Optional shared token.

### Phase 6: Polish and hardening
- OnnShell visible under 10 s after Android splash.
- 3 crashes in 60 s triggers safe-mode HOME: Settings, restore, ADB info.
- Memory: shell under 150 MB RSS idle, inputd under 30 MB.
- `docs/RESTORE.md`: tested steps back to stock.
- Signed release build with local keystore. `scripts/install.sh` for install and update.

## 7. Report format after every phase

1. What changed (files, commands)
2. What was tested on the real device and results
3. What did not work on this firmware and the fallback chosen
4. Exact next step that needs my approval

## 8. Final verification checklist

- [ ] `Accounts: 0` confirmed before provisioning, Device Owner set successfully
- [ ] Box boots straight into OnnShell, Google TV UI never appears
- [ ] Optional Google account re-added without breaking OnnShell
- [ ] Ledger complete, `restore.sh` and unprovision return the box to stock
- [ ] ADB reachable over USB and Ethernet at all times
- [ ] DS4 pairs, navigates everything, reconnects after sleep
- [ ] POINTER mode works in every app
- [ ] TOUCH mode maps correctly in a profiled app, toggle combo works
- [ ] Per-app profiles switch automatically
- [ ] Shizuku restart path after reboot works as documented
- [ ] Library server runs on PC and TrueNAS, catalog shows in shell
- [ ] Silent install, update and uninstall from the library
- [ ] No emulators anywhere
- [ ] No em dashes or en dashes anywhere
- [ ] Cold start under 1 s, idle memory in budget, 60 fps UI at 1080p
