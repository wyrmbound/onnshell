package com.xplsh.onnshell.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.xplsh.onnshell.MainActivity

/** Device Owner changes. Every change goes in the ledger first, then happens. */
object Policy {
    private const val PKG = "com.xplsh.onnshell"

    /** Hidden in Phase 1: the Google TV home and the setup wizard (also a HOME candidate). */
    val HIDE = listOf(
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tungsten.setupwraith",
    )

    /** Kinds the app can undo itself. Anything else ("shell") is undone by restore.sh. */
    private val APP_KINDS = setOf("owner", "home", "hide")

    fun cmd(action: String, extra: String = "") =
        "am broadcast -n $PKG/.admin.CommandReceiver -a $PKG.$action$extra"

    fun ledger(ctx: Context) = Ledger(ctx.getExternalFilesDir(null) ?: ctx.filesDir)
    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)
    private fun admin(ctx: Context) = ComponentName(ctx, ShellAdmin::class.java)
    fun isOwner(ctx: Context) = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    @Synchronized
    fun apply(ctx: Context): String {
        if (!isOwner(ctx)) return "not device owner"
        // Wi-Fi was found off after a reboot (2026-11-06), which cut adb and Shizuku's self-start; owners may switch it on
        runCatching {
            val wm = ctx.getSystemService(android.net.wifi.WifiManager::class.java)
            @Suppress("DEPRECATION") if (!wm.isWifiEnabled) wm.setWifiEnabled(true)
        }
        val dpm = dpm(ctx)
        val admin = admin(ctx)
        val l = ledger(ctx)

        l.add("owner", admin.flattenToShortString(), cmd("CLEAR_OWNER"))

        val home = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        l.add("home", "$PKG/.MainActivity", cmd("CLEAR_HOME"))
        dpm.addPersistentPreferredActivity(admin, home, ComponentName(ctx, MainActivity::class.java))

        for (p in HIDE) {
            if (dpm.isApplicationHidden(admin, p)) continue
            val e = l.add("hide", p, cmd("UNHIDE", " --es pkg $p"))
            if (!dpm.setApplicationHidden(admin, p, true)) l.remove(e) // not installed
        }
        return status(ctx)
    }

    /** Undo one ledger entry the app owns. Returns false for shell kinds or failures. */
    @Synchronized
    fun undo(ctx: Context, e: Ledger.Entry): Boolean {
        if (e.kind !in APP_KINDS) return false
        val dpm = dpm(ctx)
        val admin = admin(ctx)
        val owner = isOwner(ctx)
        val done = when (e.kind) {
            // Only a device owner can unhide, so a hide entry is never dropped without one
            "hide" -> owner && (dpm.setApplicationHidden(admin, e.target, false) || !dpm.isApplicationHidden(admin, e.target))
            "home" -> { if (owner) dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName); true }
            // Never clear the owner while something it hid is still hidden
            else -> ledger(ctx).entries().none { it.kind == "hide" } && run { // owner
                if (owner) dpm.clearDeviceOwnerApp(ctx.packageName)
                if (dpm.isAdminActive(admin)) dpm.removeActiveAdmin(admin)
                true
            }
        }
        if (done) ledger(ctx).remove(e)
        return done
    }

    /** Undo the newest open entry of a kind (and target, if given). */
    fun undo(ctx: Context, kind: String, target: String?): String {
        val e = ledger(ctx).entries().lastOrNull { it.kind == kind && (target == null || it.target == target) }
            ?: return "nothing to undo for $kind ${target ?: ""}".trim()
        return if (undo(ctx, e)) "undone: $kind ${e.target}" else "FAILED: $kind ${e.target}"
    }

    /**
     * Remove OnnShell control: undo every app owned change, newest first, so the
     * device owner is cleared last, after everything it hid is visible again.
     */
    @Synchronized
    fun unprovision(ctx: Context): String {
        // Stop at the first failure: clearing the owner with a package still hidden would strand it
        val failed = ledger(ctx).entries().reversed().firstOrNull { it.kind in APP_KINDS && !undo(ctx, it) }
        val left = ledger(ctx).entries().filter { it.kind !in APP_KINDS }
        return buildString {
            append(if (failed == null) "unprovisioned" else "FAILED at ${failed.kind} ${failed.target}, device owner kept")
            if (left.isNotEmpty()) append(". Shell changes left for restore.sh: ").append(left.joinToString { it.target })
        }
    }

    /** Screen off now (force-lock policy). */
    // These run from buttons and screens: if the admin is not active (seen after a power cut) they
    // must fail quietly, never crash HOME.
    fun sleep(ctx: Context) { runCatching { dpm(ctx).lockNow() } }

    /** Device Owner reboot. Not a system change, nothing to ledger. */
    fun reboot(ctx: Context) { runCatching { dpm(ctx).reboot(admin(ctx)) } }

    fun status(ctx: Context): String = runCatching {
        val dpm = dpm(ctx)
        val admin = admin(ctx)
        val owner = isOwner(ctx)
        val hidden = if (owner) HIDE.filter { dpm.isApplicationHidden(admin, it) } else emptyList()
        "owner=$owner hidden=$hidden ledger=${ledger(ctx).entries().size}"
    }.getOrElse { "owner=error ($it)" }
}
