package com.xplsh.onnshell.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xplsh.onnshell.R

/**
 * Runs inputd (inputd/inputd.c) as the shell user through Shizuku and keeps it in step with the app
 * on screen: each app's profile picks POINTER (Android's own pointer) or TOUCH (pad as touchscreen).
 * Without Shizuku nothing runs and the pad simply stays a stock pointer.
 */
object Inputd {
    var running by mutableStateOf(false); private set
    var pad by mutableStateOf<String?>(null); private set
    var touch by mutableStateOf(false); private set
    var app by mutableStateOf(""); private set
    var latency by mutableStateOf(""); private set
    var error by mutableStateOf(""); private set

    private const val BIN = "/data/local/tmp/onnshell-inputd"
    private var proc: Process? = null

    /**
     * Our own liveness flag. Never call Process.isAlive on a Shizuku process: its exitValue throws
     * IllegalArgumentException instead of IllegalThreadStateException, so isAlive crashes.
     */
    @Volatile private var alive = false
    private var padW = 0
    private var padH = 0
    private var screenW = 1920
    private var screenH = 1080
    private val main = Handler(Looper.getMainLooper())

    /** Starts inputd if Shizuku is usable and it is not already running. Safe to call often. */
    @Synchronized
    fun start(ctx: Context) {
        if (alive) return
        val app = ctx.applicationContext
        // This runs in the HOME process: any failure becomes a status line, never a crash of the home screen
        Thread {
            runCatching { launch(app) }.onFailure { error = it.toString(); alive = false }
        }.start()
    }

    private fun launch(app: Context) {
        run {
            synchronized(this) {
                if (alive || !ShellUser.ready()) return@run
                // Copy the binary in as the shell user (app files are not readable by shell). Write then
                // rename, so a copy still running elsewhere does not make it "text file busy".
                val cp = ShellUser.start("pkill -x onnshell-inputd; cat > $BIN.new && chmod 755 $BIN.new && mv -f $BIN.new $BIN") ?: return@run
                runCatching { cp.outputStream.use { o -> app.assets.open("onnshell-inputd").use { it.copyTo(o) } } }
                if (cp.waitFor() != 0) { error = "could not copy inputd"; return@run }
                // Usage access (which app is on screen) was granted in provision and is in the ledger, but
                // a hard power cut can lose app op changes Android had not saved yet. Set it again; harmless if set.
                ShellUser.run("appops set ${app.packageName} GET_USAGE_STATS allow")
                val p = ShellUser.start("exec $BIN") ?: return@run
                proc = p
                alive = true
                app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.mode?.let {
                    screenW = it.physicalWidth
                    screenH = it.physicalHeight
                }
                Thread { readLoop(app, p) }.start()
                send("screen $screenW $screenH")
                watchForeground(app, p)
            }
        }
    }

    private fun send(line: String) {
        val o = proc?.outputStream ?: return
        runCatching { synchronized(o) { o.write("$line\n".toByteArray()); o.flush() } }
    }

    private fun readLoop(ctx: Context, p: Process) {
        runCatching {
            p.inputStream.bufferedReader().forEachLine { line ->
                val w = line.split(" ")
                when (w[0]) {
                    "ready" -> { running = true; setIdleMins(Prefs.padIdleMins(ctx)) }
                    // PS pressed: freeze the game now. Android's HOME follows about 300 ms later (it waits for a possible
                    // double press), and until then the game kept playing, sound included (owner report, 2026-11-09).
                    "home" -> com.xplsh.onnshell.display.ZeroCopyActivity.current?.let { a -> a.runOnUiThread { a.pauseForHome() } }
                    "pad" -> {
                        pad = w.getOrNull(1)
                        padW = w.getOrNull(2)?.toIntOrNull() ?: 0
                        padH = w.getOrNull(3)?.toIntOrNull() ?: 0
                        apply(ctx, app) // the rect depends on the pad's size
                    }
                    "nopad" -> pad = null
                    "idle" -> w.getOrNull(1)?.let { mac -> Thread { padOff(ctx, mac) }.start() }
                    "mode" -> touch = w.getOrNull(1) == "touch"
                    "toggle" -> {
                        touch = w.getOrNull(1) == "touch"
                        main.post {
                            Toast.makeText(ctx, ctx.getString(if (touch) R.string.touchpad_touch else R.string.touchpad_pointer), Toast.LENGTH_SHORT).show()
                        }
                    }
                    "lat" -> latency = ctx.getString(R.string.input_latency, w.getOrNull(1) ?: "?", w.getOrNull(2) ?: "?", w.getOrNull(3) ?: "?")
                    "err" -> error = line.removePrefix("err ")
                }
            }
        }
        running = false
        pad = null
        if (proc === p) {
            proc = null
            alive = false
        }
    }

    /**
     * The pad sat untouched for an hour: drop its Bluetooth link so it turns itself off (a DS4 does).
     * Only the shell user may disconnect a device (BLUETOOTH_PRIVILEGED), so [PadOff] runs as shell from our own APK.
     */
    private fun padOff(ctx: Context, mac: String) {
        if (!Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}").matches(mac)) { error = "idle: odd address $mac"; return }
        val r = ShellUser.output("CLASSPATH='${ctx.applicationInfo.sourceDir}' app_process /system/bin ${PadOff::class.java.name} $mac 2>&1")
        android.util.Log.i("Inputd", "idle pad $mac: ${r?.trim() ?: "failed"}")
    }

    /** ADB test hook: report the pad idle after [secs] instead of the setting. */
    fun setIdle(secs: Int) = send("idle $secs")
    /** The Settings value: turn the pad off after [mins] idle; 0 = never (inputd has no "never", so about 68 years). */
    fun setIdleMins(mins: Int) = setIdle(if (mins <= 0) Int.MAX_VALUE else mins * 60)

    /** Sends the profile of [pkg]: its rectangle, then its mode. */
    fun apply(ctx: Context, pkg: String) {
        val prof = Prefs.profiles(ctx)[pkg]
        val rect = prof?.rect?.takeIf { Profiles.valid(it, screenW, screenH) }
            ?: Profiles.defaultRect(screenW, screenH, padW, padH)
        send("rect ${rect.joinToString(" ")}")
        send(if (prof?.touch == true) "mode touch" else "mode pointer")
    }

    /**
     * Follows the app on screen (UsageStats, granted in Phase 1) and applies its profile when it changes.
     * A Share + pad click toggle lasts until the next app change.
     */
    private fun watchForeground(ctx: Context, p: Process) = Thread {
        runCatching { followForeground(ctx, p) }.onFailure { error = it.toString() }
    }.start()

    private fun followForeground(ctx: Context, p: Process) {
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        var since = System.currentTimeMillis() - 10_000
        val e = UsageEvents.Event()
        while (alive && proc === p) {
            val now = System.currentTimeMillis()
            var top: String? = null
            runCatching {
                val events = usm.queryEvents(since, now)
                while (events.hasNextEvent()) {
                    events.getNextEvent(e)
                    if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) top = e.packageName
                }
            }
            since = now
            top?.let { if (it != app) { app = it; apply(ctx, it) } }
            Thread.sleep(700)
        }
    }

    /** Saves [pkg]'s mode and applies it right away if that app is on screen. */
    fun setTouch(ctx: Context, pkg: String, on: Boolean) {
        val old = Prefs.profiles(ctx)[pkg]
        Prefs.setProfile(ctx, pkg, if (on || old?.rect != null) Profile(on, old?.rect) else null)
        if (pkg == app) apply(ctx, pkg)
    }

    /** ADB: set or clear a profile, rect as "x,y,w,h" (checked against the screen). */
    fun setProfile(ctx: Context, pkg: String, on: Boolean, rect: String?): String {
        val r = rect?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
        if (r != null && !Profiles.valid(r, screenW, screenH)) return "error: rect must be x,y,w,h inside ${screenW}x$screenH"
        Prefs.setProfile(ctx, pkg, Profile(on, r))
        if (pkg == app) apply(ctx, pkg)
        return "profile $pkg touch=$on rect=${r ?: "default"}"
    }
}
