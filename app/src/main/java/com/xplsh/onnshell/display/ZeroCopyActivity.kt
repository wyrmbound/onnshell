package com.xplsh.onnshell.display

import android.app.Activity
import android.content.ComponentName
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.xplsh.onnshell.data.ShellUser
import rikka.shizuku.Shizuku
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * M4 / B2: zero-copy display. We own N HardwareBuffers; the BufferBridge user service (shell uid) hands
 * their dma-buf fds to the 64-bit renderer (m4h), which draws straight into them and tells us over
 * 127.0.0.1:7331 which buffer is ready; we draw it with Bitmap.wrapHardwareBuffer on a hardware canvas
 * (a GPU composite, no pixel copy) and ack. Needs Shizuku running and allowed.
 *
 *   adb shell am start -n com.xplsh.onnshell/.display.ZeroCopyActivity [--ei frames 600] [--ei w 1280 --ei h 720]
 */
class ZeroCopyActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var view: SurfaceView
    private var buffers: Array<HardwareBuffer> = emptyArray()
    private var bitmaps: Array<Bitmap?> = emptyArray()
    private var renderer: Process? = null
    private var server: ServerSocket? = null
    private var client: java.net.Socket? = null // the game's connection; closing only the ServerSocket leaves serve() blocked in readFully
    private var thread: Thread? = null
    private var bridge: IBufferBridge? = null
    // Per session, from the launch that started it (a relaunch brings new rate/channels).
    var audio: AudioSink? = null
        private set
    private val paint = android.graphics.Paint()

    // A never-reused service tag per session, counted across activity instances: a clear-task relaunch is a new
    // instance, and reusing "bridge0" while the old bridge0 was still dying handed us its dead binder.
    private var tag = -1
    private lateinit var args: Shizuku.UserServiceArgs
    private var conn: ServiceConnection? = null
    private fun serviceArgs(t: Int) = Shizuku.UserServiceArgs(ComponentName(packageName, BufferBridge::class.java.name))
        // tag() is what Shizuku keys services by; with only a new process suffix every session shared the default tag,
        // so a rebind matched the old unbound record (no onServiceConnected, or its dead binder).
        .daemon(false).tag("bridge$t").processNameSuffix("bridge$t").debuggable(false).version(1)
    /** Callbacks from an earlier session's bridge (late connect or death) must not touch this session. */
    private fun connection(t: Int) = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (t != tag) return
            bridge = IBufferBridge.Stub.asInterface(binder)
            Thread({ offer() }, "m4-offer").start()
        }
        override fun onServiceDisconnected(name: ComponentName) { if (t == tag) bridge = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Games read the pad through evdev, so Android sees no input and would sleep the box mid-game (10 min timer).
        // The flag only holds while this screen is in front; leaving the game gives the normal sleep timer back.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // ...but keeping it on never turns it on: a game started while the box slept ran on a dark TV with throttled acks
        setTurnScreenOn(true); setShowWhenLocked(true)
        view = SurfaceView(this)
        view.holder.addCallback(this)
        val root = android.widget.FrameLayout(this)
        root.addView(view)
        hud = PerfHud(root)
        setContentView(root)
        current = this
    }

    private lateinit var hud: PerfHud

    // ---- Picture in picture (owner ask, 2026-11-09): the game in a corner while another app plays full screen. ----
    // The corner window never gets key focus, so libonngrab (LD_PRELOAD in the run scripts) hands the pad to the game
    // alone: SIGUSR1 grabs the game's evdev devices, SIGUSR2 releases them. While grabbed, PS and Share are seen only by
    // the game, so libonngrab leaves flag files that the watcher below turns into "back to full screen" and "swap audio".
    private var shareDown = 0L
    private var pipWatch: Thread? = null

    /** Shrinks the running game into the corner. Only for a real game session (not a test pattern). */
    fun enterCorner() {
        if (!GameSession.external || isInPictureInPictureMode) return
        enteringCorner = true
        val ok = runCatching {
            enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build())
        }.onFailure { Log.w(TAG, "picture-in-picture refused: $it") }.getOrDefault(false)
        Log.i(TAG, "corner: $ok")
    }

    // The game's own corner window (FloatWindow): bigger than Android TV's picture-in-picture and placed where the
    // owner chose. Frames go to its surface while this activity waits in the back; Android's PiP is the fallback.
    private var float: FloatWindow? = null
    @Volatile private var floatHolder: SurfaceHolder? = null

    /** Hold Share, or the adb CORNER hook: the game keeps playing in its own corner window over the next app. */
    fun startFloat() {
        if (!GameSession.external || float != null || isInPictureInPictureMode) return
        if (!android.provider.Settings.canDrawOverlays(this)) {
            // OnnShell may grant itself "display over other apps" as the shell user; without it, Android's own corner window
            Thread({
                ShellUser.run("appops set $packageName SYSTEM_ALERT_WINDOW allow")
                runOnUiThread { if (android.provider.Settings.canDrawOverlays(this)) startFloat() else enterCorner() }
            }, "float-grant").start()
            return
        }
        val prefs = com.xplsh.onnshell.data.Prefs
        val app = applicationContext
        val win = FloatWindow(this, prefs.cornerSize(this), prefs.cornerPos(this), prefs.cornerAt(this)) { x, y -> prefs.setCornerAt(app, x, y) }
        runCatching { win.show() }.onFailure { Log.w(TAG, "corner window refused: $it"); enterCorner(); return }
        float = win; floatHolder = win.holder
        GameSession.inCorner = true
        GameSession.signal("USR1")
        audio?.muted = !prefs.cornerGameAudio(this)
        hud.show(false)
        watchCorner()
        moveTaskToBack(true) // HOME comes up behind the window; open Netflix or anything from there
        Log.i(TAG, "corner window ${prefs.cornerSize(this)}% at ${prefs.cornerPos(this)}, game audio ${audio?.muted == false}")
    }

    private fun endFloat() {
        val win = float ?: return
        float = null; floatHolder = null
        win.hide()
        GameSession.inCorner = false
        GameSession.signal("USR2")
        audio?.muted = false
        pipWatch?.interrupt(); pipWatch = null
        Log.i(TAG, "corner window closed")
    }

    /** Back to full screen: bringing this singleTask activity to the front takes it out of the corner. */
    fun leaveCorner() {
        startActivity(android.content.Intent(this, ZeroCopyActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("expand", true))
    }

    override fun onPictureInPictureModeChanged(inCorner: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inCorner, newConfig)
        enteringCorner = false
        GameSession.inCorner = inCorner
        GameSession.signal(if (inCorner) "USR1" else "USR2")
        audio?.muted = inCorner && !com.xplsh.onnshell.data.Prefs.cornerGameAudio(this)
        hud.show(!inCorner && com.xplsh.onnshell.data.Prefs.perfHud(this))
        if (inCorner) watchCorner() else { pipWatch?.interrupt(); pipWatch = null }
        Log.i(TAG, "corner mode $inCorner, game audio ${audio?.muted == false}")
    }

    private fun watchCorner() {
        if (pipWatch != null) return
        pipWatch = Thread({
            ShellUser.run("rm -f /data/local/tmp/onn-pip-exit /data/local/tmp/onn-pip-audio") // stale flags from an earlier session
            while (!Thread.currentThread().isInterrupted) {
                val got = ShellUser.output("cd /data/local/tmp && for f in onn-pip-exit onn-pip-audio; do [ -f \$f ] && rm \$f && echo \$f; done; true") ?: ""
                if ("onn-pip-audio" in got) audio?.let { it.muted = !it.muted; Log.i(TAG, "corner: game audio ${!it.muted}") }
                if ("onn-pip-exit" in got) runOnUiThread { leaveCorner() }
                try { Thread.sleep(300) } catch (_: InterruptedException) { break }
            }
        }, "pip-watch").also { it.start() }
    }
    // The overlay setting is read on every return, so a change made in OnnShell settings while the game waits applies at once
    override fun onResume() {
        super.onResume(); hud.show(com.xplsh.onnshell.data.Prefs.perfHud(this))
        // paused by onPause but the surface stayed (something drew over the game briefly): no surfaceCreated will come to thaw it
        if (GameSession.suspended && thread != null && view.holder.surface?.isValid == true) resumeGame()
    }
    override fun onStop() { hud.stop(); super.onStop() }

    override fun surfaceCreated(holder: SurfaceHolder) {
        endFloat()
        if (GameSession.suspended) resumeGame() else if (thread == null) startSession()
    }

    // A second `am start` reaches the existing instance here (launchMode singleTask), so the old session's sockets
    // and bridge are torn down before the new one binds; stacking a second instance made 7331/7332 EADDRINUSE.
    override fun onNewIntent(intent: android.content.Intent) {
        // Back from the corner window (PS, or the adb hook): the session keeps running, only the window grows
        if (intent.getBooleanExtra("expand", false)) { endFloat(); return }
        super.onNewIntent(intent); setIntent(intent)
        // Coming back to a suspended game: surfaceCreated resumes it. Anything else is a fresh session.
        if (GameSession.suspended && !intent.getBooleanExtra("fresh", false)) return
        stop(); if (view.holder.surface?.isValid == true) startSession()
    }

    private fun startSession() {
        if (!ShellUser.ready()) { Log.e(TAG, "Shizuku ${ShellUser.status()}"); ShellUser.requestIfNeeded(); return }
        closing = false
        val w = intent.getIntExtra("w", 1280); val h = intent.getIntExtra("h", 720)
        // CPU usage bits make Arm gralloc pick a linear layout, which the renderer imports as-is.
        val usage = HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
            HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_CPU_WRITE_OFTEN or
            (if (intent.getStringExtra("present") == "overlay") HardwareBuffer.USAGE_COMPOSER_OVERLAY else 0L)
        buffers = Array(3) { HardwareBuffer.create(w, h, HardwareBuffer.RGBA_8888, 1, usage) }
        bitmaps = arrayOfNulls(buffers.size)
        thread = Thread({ serve() }, "m4-recv").also { it.start() }
        // Default = what our SDL now always delivers under ONNM4 (48 kHz stereo); --ei rate/channels only for old tests
        audio = AudioSink(intent.getIntExtra("rate", 48000), intent.getIntExtra("channels", 2)).also {
            it.device = com.xplsh.onnshell.data.Speaker.forGames(this); it.start()
        }

        val frames = intent.getIntExtra("frames", 600)
        // --ez launch false: an external renderer (m6, the game) connects to @onn-m4 instead of m4h.
        GameSession.external = !intent.getBooleanExtra("launch", true)
        if (!GameSession.external) renderer = ShellUser.start("cd $PB && PB_FRAMES=$frames LD_LIBRARY_PATH=$PB ./linker64 $PB/m4h $PB/libvulkan_panfrost.so 2>&1")
        Thread({
            renderer?.inputStream?.bufferedReader()?.forEachLine { Log.i(TAG, "m4h: $it") }
            Log.i(TAG, "m4h exited ${renderer?.waitFor()}")
        }, "m4h-log").start()
        // The renderer listens on @onn-m4 first; the bridge's connect retries for 5 s.
        tag = GameSession.nextBridgeTag()
        args = serviceArgs(tag)
        conn = connection(tag).also { Shizuku.bindUserService(args, it) }
        Log.i(TAG, "session uses bridge$tag")
        // A tile launch names the game: start it here, on GameSession's worker, so any KILL queued by stop() runs first
        intent.getStringExtra("game")?.let { GameSession.start(it) }
    }

    private fun offer() {
        val lib = applicationInfo.nativeLibraryDir + "/libbufbridge.so"
        val rc = try { bridge?.offer(buffers, "onn-m4", lib, applicationInfo.sourceDir) } catch (e: Exception) { Log.e(TAG, "offer: $e"); null }
        Log.i(TAG, "bridge offer -> $rc (lib $lib)")
    }

    private val releaser = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var overlaySc: android.view.SurfaceControl? = null

    /** A child layer of the SurfaceView, scaled from the game's buffer size to the view; HWC scans it out directly. */
    private fun overlayLayer(): android.view.SurfaceControl? = runCatching {
        val parent = view.surfaceControl
        val w = buffers[0].width; val h = buffers[0].height
        val sc = android.view.SurfaceControl.Builder().setName("onn-game").setParent(parent).setBufferSize(w, h).setOpaque(true).build()
        android.view.SurfaceControl.Transaction()
            .setVisibility(sc, true)
            .setScale(sc, view.width.toFloat() / w, view.height.toFloat() / h)
            .apply()
        overlaySc = sc
        Log.i(TAG, "present: overlay ${w}x$h -> ${view.width}x${view.height}")
        sc
    }.onFailure { Log.e(TAG, "present: overlay unavailable, using canvas: $it") }.getOrNull()

    /** Renderer -> us: 'M4RD' (GL, bottom-up rows) or 'M4RV' (Vulkan WSI, top-down rows) index frame. Us -> renderer: index once it is on screen. */
    private fun serve() {
        val ackDelayMs = intent.getIntExtra("ackdelayms", 0)
        val noDraw = intent.getBooleanExtra("nodraw", false)
        // TEST-ONLY until proven: --es present overlay hands each buffer to HWC (the DEVICE overlay it already is)
        // instead of a HWUI canvas copy, and acks only when the display releases it. No GPU reads our pages.
        val overlay = intent.getStringExtra("present") == "overlay"
        try {
            ServerSocket().use { ss ->
                server = ss
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 7331), 1)
                Log.i(TAG, "listening on 127.0.0.1:7331")
                ss.accept().use { s ->
                    client = s
                    if (GameSession.external) Thread({ GameSession.findPid() }, "game-pid").start()
                    val input = DataInputStream(s.getInputStream()); val out = DataOutputStream(s.getOutputStream())
                    val hdr = ByteArray(12); val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                    var frames = 0; val t0 = System.nanoTime()
                    val sendAck: (Int) -> Unit = { idx ->
                        val a = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(idx).array()
                        synchronized(out) { out.write(a); out.flush() }
                    }
                    val game = if (overlay) overlayLayer() else null
                    while (true) {
                        input.readFully(hdr); bb.rewind()
                        val magic = bb.getInt(); val i = bb.getInt(); bb.getInt()
                        // The third word stays the sender's frame counter; the tag says the row order
                        val vulkan = magic == M4RV
                        if ((magic != M4RD && !vulkan) || i !in buffers.indices) throw IllegalStateException("bad ready msg")
                        if (game != null) {
                            // The release callback fires once HWC is done scanning buffer i; only then may the game draw into it again.
                            android.view.SurfaceControl.Transaction()
                                .setBuffer(game, buffers[i], null) { fence -> releaser.execute {
                                    runCatching { fence.awaitForever() }; fence.close()
                                    runCatching { sendAck(i) }
                                } }
                                .setBufferTransform(game, if (vulkan) 0 else android.view.SurfaceControl.BUFFER_TRANSFORM_MIRROR_VERTICAL)
                                .apply()
                            frames++; GameSession.frames.incrementAndGet()
                            if (frames % 60 == 0) Log.i(TAG, String.format("%d frames shown, %.1f fps (overlay)", frames, frames / ((System.nanoTime() - t0) / 1e9)))
                            continue
                        }
                        val bmp = bitmaps[i] ?: Bitmap.wrapHardwareBuffer(buffers[i], ColorSpace.get(ColorSpace.Named.SRGB))!!.also { bitmaps[i] = it }
                        // TEST-ONLY: --ez nodraw true acks without the HWUI canvas draw (panel stays lit, screen shows nothing). Never set on the tile path.
                        val target = floatHolder ?: view.holder
                        if (!noDraw) target.lockHardwareCanvas()?.let { c ->
                            // GL renders bottom-up into the dma-buf: flip on the canvas, free on the GPU. Vulkan is already top-down.
                            c.save(); if (!vulkan) c.scale(1f, -1f, c.width / 2f, c.height / 2f)
                            c.drawBitmap(bmp, null, Rect(0, 0, c.width, c.height), paint)
                            c.restore()
                            target.unlockCanvasAndPost(c)
                        }
                        // TEST-ONLY: --ei ackdelayms N holds each ack N ms, capping any game at about 1000/N fps. Never set on the tile path.
                        if (ackDelayMs > 0) Thread.sleep(ackDelayMs.toLong())
                        sendAck(i)
                        frames++; GameSession.frames.incrementAndGet()
                        if (frames % 60 == 0) Log.i(TAG, String.format("%d frames shown, %.1f fps", frames, frames / ((System.nanoTime() - t0) / 1e9)))
                    }
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "stream ended: $e")
            // The game exited or crashed, or a test pattern (m4h) finished (not us stopping): leave the game screen, back to
            // HOME. A finished test used to stay up showing its last frame, and BACK cannot close this screen.
            if (!closing) runOnUiThread { if (!isFinishing) finish() }
        } finally { server = null; client = null }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { }
    // Vita-style: leaving the game (PS/HOME, another app) freezes it instead of killing it; buffers, sockets and
    // the bridge stay alive, so coming back is a SIGCONT and the next frame lands in the same HardwareBuffers.
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // TEST-ONLY: --ez nosuspend true keeps the game rendering with the panel off (frames acked without drawing). Never set on the tile path.
        if (!isFinishing && GameSession.external && intent.getBooleanExtra("nosuspend", false)) return
        if (float != null) return // the game moved to its corner window and keeps running
        if (!isFinishing && GameSession.external) suspendGame() else stop()
    }

    // Leaving the game (PS/HOME, another app) pauses it at once: onPause comes as the switch starts, while the surface
    // used to go only after the HOME animation, and the game's buffered sound kept playing over the menu meanwhile.
    override fun onPause() {
        super.onPause()
        GameSession.leftAt = android.os.SystemClock.uptimeMillis()
        if (!isFinishing && GameSession.external && float == null && !enteringCorner && !isInPictureInPictureMode) suspendGame()
    }
    private var enteringCorner = false

    /** The PS button was pressed (inputd says so before Android's HOME arrives): freeze a full-screen game at once. */
    fun pauseForHome() {
        if (!isFinishing && GameSession.external && float == null && !enteringCorner && !isInPictureInPictureMode) suspendGame()
    }

    private fun suspendGame() {
        if (GameSession.suspended) return
        audio?.hold(true) // silence now: what the track already buffered is dropped, not played out
        GameSession.signal("STOP"); GameSession.suspended = true
        Log.i(TAG, "game suspended")
    }
    private fun resumeGame() {
        // The game died while we were away (crash, DEVICE LOST, killed by a test): nothing to thaw, clear the flag
        // instead of bouncing "resumed/suspended" forever and refusing every new launch.
        if (ShellUser.run("pidof linker64 >/dev/null") != 0) {
            Log.w(TAG, "suspended game is gone; ending the session"); GameSession.suspended = false; stop(); finish(); return
        }
        if (server == null) { // this instance never owned the session (process or activity was recreated): nothing to thaw into
            Log.w(TAG, "suspended game has no display session here; ending it"); GameSession.suspended = false; stop(); finish(); return
        }
        GameSession.signal("CONT"); GameSession.suspended = false
        audio?.hold(false)
        Log.i(TAG, "game resumed")
    }

    @Volatile private var closing = false

    private fun stop() {
        closing = true
        endFloat()
        if (GameSession.external) { GameSession.signal("KILL"); GameSession.external = false; GameSession.suspended = false }
        try { client?.close() } catch (_: Exception) { }
        try { server?.close() } catch (_: Exception) { }
        thread?.join(2000); thread = null
        overlaySc?.let { sc -> runCatching { android.view.SurfaceControl.Transaction().reparent(sc, null).apply(); sc.release() } }; overlaySc = null
        audio?.stop(); audio = null
        renderer?.destroy(); renderer = null
        // Ask the bridge process to exit before unbinding: a bind right after unbind(remove=true) raced in Shizuku
        // and the fresh service never got the offer.
        runCatching { bridge?.destroy() }; bridge = null
        conn?.let { c -> runCatching { Shizuku.unbindUserService(args, c, true) } }; conn = null
        tag = -1
        bitmaps.forEach { it?.recycle() }; bitmaps = emptyArray()
        buffers.forEach { it.close() }; buffers = emptyArray()
    }

    // The game reads the controller through evdev, so Android must not act on the same presses here:
    // BUTTON_B maps to BACK and would finish the activity mid-game. Long-press BACK is the exit.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN && event.isLongPress) { finish(); return true }
        // Hold Share about a second: the game moves to the corner (the press itself still reaches the game through evdev)
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) shareDown = event.eventTime
            if (event.action == KeyEvent.ACTION_UP && shareDown > 0 && event.eventTime - shareDown >= 800) startFloat()
            if (event.action == KeyEvent.ACTION_UP) shareDown = 0
        }
        return true
    }

    override fun onDestroy() { pipWatch?.interrupt(); if (GameSession.inCorner) { GameSession.inCorner = false; GameSession.signal("USR2") }; current = null; stop(); GameSession.endedAt = android.os.SystemClock.uptimeMillis(); super.onDestroy() }

    companion object {
        private const val TAG = "M4"; private const val PB = "/data/local/tmp/pb"
        private const val M4RD = 0x4452344d // "M4RD" little-endian
        private const val M4RV = 0x5652344d // "M4RV"
        /** The live game screen, for the adb corner hooks. */
        @Volatile var current: ZeroCopyActivity? = null
    }
}

/** The one external game session (launched with --ez launch false) and whether it is frozen. */
object GameSession {
    @Volatile var external = false
    @Volatile var suspended = false
    /** When the last game screen closed. HOME ignores the pad briefly after it, so gameplay presses still in flight
     *  (d-pad + A) cannot walk the home screen into Power > Restart > Yes; that rebooted the box several times. */
    @Volatile var endedAt = 0L
    /** When the game screen last went to the back (uptime ms): the HOME that follows means "come home", not "resume". */
    @Volatile var leftAt = 0L
    /** The game is in the picture-in-picture corner (its pad is grabbed). */
    @Volatile var inCorner = false
    /** Frames presented, for the performance overlay's fps. */
    val frames = java.util.concurrent.atomic.AtomicLong()
    private val bridgeTags = java.util.concurrent.atomic.AtomicInteger()
    fun nextBridgeTag() = bridgeTags.getAndIncrement()
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Signal the game (everything we run goes through the bundled linker64). By process name, not pkill -f: toybox's
     *  pkill -STOP -f matched and froze its own sh, which hung the HOME main thread (ANR). Off the main thread, in order. */
    /** Start an installed 64-bit game (/data/local/tmp/games/<id>, from apkinstall) through apkrun, as the shell user. */
    fun start(id: String) {
        if (!Regex("[a-z0-9][a-z0-9-]*").matches(id)) { Log.e("M4", "bad game id $id"); return } // goes into a shell line
        worker.execute {
            // One game at a time: a leftover game (suspended by a screen that no longer exists, so stop() never killed it) is
            // ended first. A frozen process ignores TERM until thawed, so KILL; waiting alone hung every later launch.
            ShellUser.run("p=\$(pidof linker64); [ -n \"\$p\" ] && kill -KILL \$p; while pidof linker64 >/dev/null; do sleep 0.1; done; " +
                // a game built for the box itself (Mario) brings its own run.sh; APK ports go through run_apk.sh
                "G=/data/local/tmp/games/$id; if [ -f \$G/run.sh ]; then set -- \$G/run.sh; else set -- /data/local/tmp/games/run_apk.sh \$G; fi; " +
                "nohup sh \"\$@\" </dev/null >/dev/null 2>&1 &")
            Log.i("M4", "started game $id")
        }
    }

    private var shell: java.io.Writer? = null
    /** The running game's pid, found once it connects; checked by name before use, so a reused pid is never signalled. */
    @Volatile var pid: Int = 0
    fun findPid() { pid = ShellUser.output("pidof linker64")?.trim()?.split(" ")?.firstOrNull()?.toIntOrNull() ?: 0 }
    /** Through one long-lived shell (started once, fed on stdin): a new process per signal cost a Shizuku round trip. */
    fun signal(sig: String) = worker.execute {
        val p = pid
        val line = if (p > 0) "[ \"\$(cat /proc/$p/comm 2>/dev/null)\" = linker64 ] && kill -$sig $p || { p=\$(pidof linker64); [ -n \"\$p\" ] && kill -$sig \$p; }"
            else "p=\$(pidof linker64); [ -n \"\$p\" ] && kill -$sig \$p"
        if (sig == "KILL") pid = 0
        val w = shell ?: ShellUser.start("exec sh >/dev/null 2>&1")?.outputStream?.bufferedWriter()?.also { shell = it }
        if (w == null || runCatching { w.write(line + "\n"); w.flush() }.isFailure) { shell = null; ShellUser.run(line) }
    }
}
