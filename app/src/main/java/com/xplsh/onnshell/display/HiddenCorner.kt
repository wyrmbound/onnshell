package com.xplsh.onnshell.display

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import com.xplsh.onnshell.data.Inputd
import com.xplsh.onnshell.data.Prefs
import com.xplsh.onnshell.data.ShellUser
import rikka.shizuku.Shizuku

/**
 * Picture in picture for regular Android games (owner ask, 2026-11-11). Hold Share in a game: it moves to a hidden
 * screen ([HiddenScreen], shell side) drawn into the same corner window 64-bit games use ([FloatWindow]); inputd hands
 * the controller to the hidden screen and the remote stays with the TV. PS brings the game back full screen.
 * The hidden screen is the TV's own size and density, so the game sees no change and does not restart.
 */
object HiddenCorner {
    private const val TAG = "HiddenCorner"
    /** The input port (evdev phys) of inputd's controller copy; Android ties it to the hidden screen. */
    const val PORT = "onnshell-hidden"
    private val main = Handler(Looper.getMainLooper())
    private var win: FloatWindow? = null
    private var conn: ServiceConnection? = null
    private var service: IHiddenScreen? = null
    private var pkg: String? = null
    @Volatile var active = false; private set
    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName("com.xplsh.onnshell", HiddenScreen::class.java.name))
            .daemon(false).tag("hidden").processNameSuffix("hidden").debuggable(false).version(1)
    }

    /** Puts [game]'s task in the corner. Main thread. */
    fun enter(ctx: Context, game: String) {
        val app = ctx.applicationContext
        if (win != null || game == app.packageName || !ShellUser.ready()) { Log.i(TAG, "not now: ${win != null} $game ${ShellUser.status()}"); return }
        if (!android.provider.Settings.canDrawOverlays(app)) {
            Thread({ ShellUser.run("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow"); main.post { if (android.provider.Settings.canDrawOverlays(app)) enter(app, game) } }, "hidden-grant").start()
            return
        }
        pkg = game
        val w = FloatWindow(app, Prefs.cornerSize(app), Prefs.cornerPos(app), Prefs.cornerAt(app)) { x, y -> Prefs.setCornerAt(app, x, y) }
        val metrics = app.resources.displayMetrics
        val (sw, sh) = app.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds.let { it.width() to it.height() }
        w.holder.setFixedSize(sw, sh) // the compositor scales the full-size game into the window
        var surfaceReady = false
        fun tryStart() {
            val s = service ?: return
            if (!surfaceReady) return
            Thread({
                val r = runCatching { s.enter(w.holder.surface, game, sw, sh, metrics.densityDpi, PORT) }.getOrElse { "err $it" }
                Log.i(TAG, "enter $game: $r")
                main.post { if (r == "ok") started(app, game) else exit(app) }
            }, "hidden-enter").start()
        }
        w.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { surfaceReady = true; tryStart() }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, ww: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) { if (active) main.post { exit(app) } }
        })
        runCatching { w.show() }.onFailure { Log.w(TAG, "corner window refused: $it"); return }
        win = w
        conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { service = IHiddenScreen.Stub.asInterface(binder); main.post { tryStart() } }
            override fun onServiceDisconnected(name: ComponentName) { service = null; main.post { exit(app) } }
        }.also { Shizuku.bindUserService(args, it) }
    }

    private fun started(app: Context, game: String) {
        active = true
        Thread({
            // the game can leave the hidden screen without us (opened again from home, or it quit): close the corner then
            while (active) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                val s = service ?: break
                if (active && runCatching { !s.alive() }.getOrDefault(true)) { main.post { if (active) exit(app) }; break }
            }
        }, "hidden-watch").start()
        Inputd.mirror(true)
        if (!Prefs.cornerGameAudio(app)) service?.let { s -> Thread { runCatching { s.setMuted(true) } }.start() }
        // the home screen in front on the TV, which also gives the TV back the remote's focus
        app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(app.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Back to full screen (also after a failed start: the game is put back in front). Main thread. */
    fun exit(ctx: Context) {
        val app = ctx.applicationContext
        val w = win ?: return
        win = null; active = false
        Inputd.mirror(false)
        val s = service; val c = conn; service = null; conn = null
        Thread({
            Log.i(TAG, "leave: ${runCatching { s?.leave() }.getOrElse { "err $it" }}")
            main.post { w.hide() }
            c?.let { runCatching { Shizuku.unbindUserService(args, it, true) } }
        }, "hidden-leave").start()
        Log.i(TAG, "corner closed")
    }
}
