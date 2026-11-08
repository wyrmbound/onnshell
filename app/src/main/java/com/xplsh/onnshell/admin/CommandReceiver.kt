package com.xplsh.onnshell.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

/**
 * ADB control path, only the shell can call it (manifest requires DUMP):
 *   adb shell am broadcast -n com.xplsh.onnshell/.admin.CommandReceiver -a com.xplsh.onnshell.<ACTION> [extras]
 * The result is printed by "am broadcast" as the result data.
 */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        resultData = try {
            when (intent.action?.removePrefix("com.xplsh.onnshell.")) {
                "STATUS" -> Policy.status(ctx) + " " + com.xplsh.onnshell.data.Inputd.let {
                    "inputd=${it.running} pad=${it.pad} touch=${it.touch} app=${it.app.ifEmpty { "?" }} err=${it.error.ifEmpty { "none" }}"
                }
                "PROFILE" -> { // touchpad profile: --es pkg P --ez touch true|false [--es rect x,y,w,h]
                    val pkg = intent.getStringExtra("pkg") ?: return fail("missing --es pkg")
                    com.xplsh.onnshell.data.Inputd.setProfile(ctx, pkg, intent.getBooleanExtra("touch", false), intent.getStringExtra("rect"))
                }
                "PAD_IDLE" -> { com.xplsh.onnshell.data.Inputd.setIdle(intent.getIntExtra("secs", 3600)); "pad idle after ${intent.getIntExtra("secs", 3600)} s" } // --ei secs N, until inputd restarts
                "SPEAKER_TEST" -> { // boot chime on the box speaker: --ef vol 0..1 (default 0.3)
                    val d = com.xplsh.onnshell.data.Speaker.chime(ctx, intent.getFloatExtra("vol", 0.3f))
                    "speaker=${d?.let { "${it.productName} id ${it.id}" } ?: "none (default output)"}"
                }
                "SEND_FILES" -> { // upload page test: --ez on true|false (the screen normally opens and closes it)
                    val d = com.xplsh.onnshell.data.FileDrop
                    if (intent.getBooleanExtra("on", true)) d.start() ?: "no home network" else { d.stop(); "stopped" }
                }
                "LOBBY" -> { // lobby music test: [--ez night true|false] forces a track, --ez clock true follows the clock again
                    val l = com.xplsh.onnshell.data.Lobby
                    if (intent.getBooleanExtra("clock", false)) l.forceNight = null
                    else if (intent.hasExtra("night")) l.forceNight = intent.getBooleanExtra("night", false)
                    android.os.Handler(android.os.Looper.getMainLooper()).post { if (l.front) { l.stop(); l.play(ctx) } } // only while home is in front
                    "lobby night=${l.isNight()} force=${l.forceNight} on=${com.xplsh.onnshell.data.Prefs.lobbyMusic(ctx)}"
                }
                "EFFECTS" -> { // UI setting, for performance tests: --ez lite true|false, applies live
                    val lite = intent.getBooleanExtra("lite", false)
                    com.xplsh.onnshell.data.Prefs.setLite(ctx, lite)
                    com.xplsh.onnshell.ui.Pad.lite = lite
                    "lite=$lite"
                }
                // Store installer without the UI (tests, and games sent from the PC by URL):
                // --es id I --es url U [--es sha256 S] [--el size N]; progress and result go to logcat tag GameInstaller
                "STORE_INSTALL" -> {
                    val id = intent.getStringExtra("id") ?: return fail("missing --es id")
                    val url = intent.getStringExtra("url") ?: return fail("missing --es url")
                    val sha = intent.getStringExtra("sha256"); val size = intent.getLongExtra("size", 0L)
                    val app = ctx.applicationContext
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        val r = com.xplsh.onnshell.data.GameInstaller.install(app, id, url, sha, size) { f, what ->
                            android.util.Log.i("GameInstaller", "$id ${(f * 100).toInt()}% $what")
                        }
                        android.util.Log.i("GameInstaller", "$id result: ${r.exceptionOrNull() ?: "ok"}")
                    }
                    "started $id (watch: logcat -s GameInstaller)"
                }
                // Store UI hooks for tests, same paths as the pad: STORE_OPEN (shows the tab), STORE_FOCUS --es id, STORE_GET --es id
                // Opens a 64-bit game like its HOME tile (game files check included): --es id I
                "OPEN_GAME" -> com.xplsh.onnshell.data.StoreState.current?.let { it.launchRequest = intent.getStringExtra("id") ?: return fail("missing --es id"); "open requested" } ?: "no home yet"
                "STORE_OPEN" -> com.xplsh.onnshell.data.StoreState.current?.let { it.openRequest = true; "opening" } ?: "no store yet (is HOME up?)"
                "STORE_FOCUS" -> com.xplsh.onnshell.data.StoreState.current?.let { it.focusRequest = intent.getStringExtra("id") ?: return fail("missing --es id"); "focus requested" } ?: "no store yet"
                "STORE_GET" -> {
                    val id = intent.getStringExtra("id") ?: return fail("missing --es id")
                    val s = com.xplsh.onnshell.data.StoreState.current ?: return fail("no store yet (is HOME up?)")
                    val e = s.entry(id) ?: return fail("unknown id $id (open the Store once so the catalog is loaded)")
                    if (s.install(e)) "installing $id" else "busy with ${s.busy}"
                }
                // --es id I (64-bit runner game) or --es pkg P (installed app; result in logcat tag GameInstaller)
                "STORE_UNINSTALL" -> intent.getStringExtra("pkg")?.let { pkg ->
                    val app = ctx.applicationContext
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        android.util.Log.i("GameInstaller", "uninstall $pkg: ${com.xplsh.onnshell.data.GameInstaller.uninstallPackage(app, pkg).exceptionOrNull() ?: "ok"}")
                    }
                    "uninstalling $pkg"
                } ?: run { com.xplsh.onnshell.data.GameInstaller.uninstall(ctx, intent.getStringExtra("id") ?: return fail("missing --es id or --es pkg")); "removed" }
                // Theme for tests (screenshots per look): --es id slate|ink|mono. Takes effect when HOME is recreated.
                "THEME" -> {
                    val id = intent.getStringExtra("id") ?: return fail("missing --es id")
                    com.xplsh.onnshell.data.Prefs.setThemeId(ctx, id); "theme=$id"
                }
                // In-game performance overlay: --ez on true|false. The game screen reads it when it comes to the front.
                "HUD" -> { val on = intent.getBooleanExtra("on", true); com.xplsh.onnshell.data.Prefs.setPerfHud(ctx, on); "hud=$on" }
                // Picture in picture, for tests: CORNER moves the running game to the corner, CORNER_EXIT brings it back
                "CORNER" -> com.xplsh.onnshell.display.ZeroCopyActivity.current?.let { a -> a.runOnUiThread { a.startFloat() }; "corner requested" } ?: "no game screen"
                "CORNER_EXIT" -> com.xplsh.onnshell.display.ZeroCopyActivity.current?.let { a -> a.runOnUiThread { a.leaveCorner() }; "full screen requested" } ?: "no game screen"
                "APPLY" -> Policy.apply(ctx)
                "UNPROVISION" -> Policy.unprovision(ctx)
                "UNHIDE" -> Policy.undo(ctx, "hide", intent.getStringExtra("pkg") ?: return fail("missing --es pkg"))
                "CLEAR_HOME" -> Policy.undo(ctx, "home", null)
                "CLEAR_OWNER" -> Policy.undo(ctx, "owner", null)
                "LEDGER_ADD" -> {
                    val kind = intent.getStringExtra("kind") ?: "shell"
                    val target = intent.getStringExtra("target") ?: return fail("missing --es target")
                    val restore = intent.getStringExtra("restore") ?: return fail("missing --es restore")
                    Policy.ledger(ctx).add(kind, target, restore)
                    "recorded: $kind $target"
                }
                "LEDGER_DONE" -> { // a shell change was undone by the shell
                    val target = intent.getStringExtra("target") ?: return fail("missing --es target")
                    val l = Policy.ledger(ctx)
                    l.entries().filter { it.target == target && it.kind == "shell" }.forEach(l::remove)
                    "cleared: $target"
                }
                else -> "unknown action ${intent.action}"
            }
        } catch (e: Exception) {
            "error: $e"
        }
    }

    private fun fail(msg: String) { resultData = "error: $msg" }
}
