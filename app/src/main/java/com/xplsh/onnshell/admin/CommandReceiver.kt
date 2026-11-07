package com.xplsh.onnshell.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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
                "EFFECTS" -> { // UI setting, for performance tests: --ez lite true|false, applies live
                    val lite = intent.getBooleanExtra("lite", false)
                    com.xplsh.onnshell.data.Prefs.setLite(ctx, lite)
                    com.xplsh.onnshell.ui.Pad.lite = lite
                    "lite=$lite"
                }
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
