# Phase 2 debloat candidates

Source: `pm list packages -f` in `docs/DEVICE.md`, plus `dumpsys package` for unclear ones.
Method for everything approved: `pm disable-user --user 0 <pkg>`, ledger first, restore is `pm enable <pkg>`
(the shell can undo it, unlike a Device Owner hide). Nothing is applied until approved.

## SAFE (recommended)

| Package | What it is | Reason |
|---|---|---|
| com.google.android.apps.tv.dreamx | Google TV ambient screensaver | Shows promos and photo feeds. The basic screensaver (com.android.dreams.basic) stays |
| com.google.android.katniss | Google Assistant / Gemini voice search | Needs an account, listens for the mic button. The mic button stops doing anything |
| android.autoinstalls.config.soc.amlogic | Play Auto Install config | Tells Play to push partner apps onto the box |
| com.droidlogic.launcher.provider | onn / Walmart launcher customization | Partner rows and promos for the Google TV home, which is already hidden |
| com.onn.tv.setup.customization | onn setup customization | Only used during first setup |
| com.google.android.partnersetup | Partner setup, RLZ ping | Sends promo attribution pings. Play Store works without it |
| com.google.android.feedback | Google Feedback | Sends bug reports to Google |
| com.android.tv.feedbackconsent | Feedback consent screen | Telemetry consent UI only |
| com.gretzky.hwinfo | Factory hardware info | Only a factory boot receiver |
| com.google.android.syncadapters.calendar | Calendar sync | No account to sync |
| com.android.printspooler | Print service | No printing on a TV box |
| com.android.dynsystem | Dynamic System Updates | Developer GSI loader, not used |
| com.android.cameraextensions | Camera extensions proxy | No camera |

## RISKY (only if you want them gone, say which)

| Package | What it is | Risk |
|---|---|---|
| com.google.android.youtube.tvmusic | YouTube Music | Preinstalled promo, but you may use it |
| com.google.android.play.games | Play Games | Not needed for native APKs, but some Play games want it |
| com.onn.updatenotification | onn update notices | Hides firmware update notices. An OTA could also change things OnnShell relies on |
| com.google.android.tv.axel | Google TV remote setup (OOBE, remote button setup) | May be needed to re-pair or reconfigure a Google TV remote |
| com.android.adservices.api | Android ad services (Privacy Sandbox) | Mainline module, disabling it can break apps that call it |
| com.android.ondevicepersonalization.services, com.android.federatedcompute.services | On-device ad personalization and federated learning | Mainline modules, same risk |
| com.android.healthconnect.controller, com.android.health.connect.backuprestore | Health Connect | Useless on a TV, but mainline |
| com.android.devicelockcontroller | Device Lock (financed devices) | Mainline |

## KEEP

| Package | Reason |
|---|---|
| com.google.android.apps.tv.launcherx, com.google.android.tungsten.setupwraith | Already hidden in Phase 1 by Device Owner, leave as is |
| com.android.vending, com.google.android.gms, com.google.android.gsf | Play Store and Play Services, streaming apps and the optional account need them |
| com.google.android.apps.mediashell | Chromecast built in |
| com.google.android.tv.remote.service | Phone remote app control |
| com.google.android.tv.dfuservice | Remote firmware updates |
| com.onn.bluetoothconnect | Pairing the onn remote |
| com.gretzky.GlobalKey | Remote shortcut buttons (YouTube, Netflix keys), OnnShell may use them in Phase 3 |
| com.netflix.tokenmanager | Netflix sign in |
| com.droidlogic.* (tv.settings, mictoggle, connecteddevices, overlays) | Settings, CEC, display, mic switch |
| com.android.bluetooth, com.android.inputdevices, com.android.systemui, com.android.tv.settings, com.google.android.packageinstaller, com.google.android.permissioncontroller, com.android.shell, com.android.networkstack*, com.android.wifi.*, providers | Spec must keep list (Bluetooth, input, settings, installer, system UI, Wi-Fi, Ethernet, Shizuku) |
| com.google.android.inputmethod.latin, com.google.android.tts, com.google.android.marvin.talkback, com.google.android.webview | Keyboard, speech, accessibility, WebView |
| Your own streaming apps | Not bloat. Which ones show on the Apps row is the Phase 3 whitelist |

## Result (2026-11-05)

- Applied: all 21 packages report `disabled-user`. Ledger has 27 open entries.
- Reboot test passed: boot went FallbackHome (normal unlock screen), then OnnShell at 17:57:22, 1.5 s after the screen came on. The Google TV home never showed. Device Owner, both hides and all 21 disables survived, and Wi-Fi reconnected to "Web" by itself.
- ADB over the network survived the reboot: `service.adb.tcp.port` is 5555 again after boot, with no USB step and wireless debugging off. This matters for Phase 4b.
- Wi-Fi after forgetting the Xiaomi repeater: 5 minute ping test, the only misses were the reboot itself.