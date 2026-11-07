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
    private var thread: Thread? = null
    private var bridge: IBufferBridge? = null
    private val audio by lazy { AudioSink(intent.getIntExtra("rate", 32000), intent.getIntExtra("channels", 2)) }
    private val paint = android.graphics.Paint()

    // A fresh service tag per session: rebinding the same tag right after unbind raced inside Shizuku and the
    // new bridge process never received the offer. Different tags are independent services.
    private var session = 0
    private lateinit var args: Shizuku.UserServiceArgs
    private fun serviceArgs() = Shizuku.UserServiceArgs(ComponentName(packageName, BufferBridge::class.java.name))
        .daemon(false).processNameSuffix("bridge" + (session++)).debuggable(false).version(1)
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            bridge = IBufferBridge.Stub.asInterface(binder)
            Thread({ offer() }, "m4-offer").start()
        }
        override fun onServiceDisconnected(name: ComponentName) { bridge = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = SurfaceView(this)
        view.holder.addCallback(this)
        setContentView(view)
    }

    override fun surfaceCreated(holder: SurfaceHolder) { if (GameSession.suspended) resumeGame() else startSession() }

    // A second `am start` reaches the existing instance here (launchMode singleTask), so the old session's sockets
    // and bridge are torn down before the new one binds; stacking a second instance made 7331/7332 EADDRINUSE.
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent); setIntent(intent)
        // Coming back to a suspended game: surfaceCreated resumes it. Anything else is a fresh session.
        if (GameSession.suspended && !intent.getBooleanExtra("fresh", false)) return
        stop(); if (view.holder.surface?.isValid == true) startSession()
    }

    private fun startSession() {
        if (!ShellUser.ready()) { Log.e(TAG, "Shizuku ${ShellUser.status()}"); ShellUser.requestIfNeeded(); return }
        val w = intent.getIntExtra("w", 1280); val h = intent.getIntExtra("h", 720)
        // CPU usage bits make Arm gralloc pick a linear layout, which the renderer imports as-is.
        val usage = HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
            HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_CPU_WRITE_OFTEN
        buffers = Array(3) { HardwareBuffer.create(w, h, HardwareBuffer.RGBA_8888, 1, usage) }
        bitmaps = arrayOfNulls(buffers.size)
        thread = Thread({ serve() }, "m4-recv").also { it.start() }
        audio.start()

        val frames = intent.getIntExtra("frames", 600)
        // --ez launch false: an external renderer (m6, the game) connects to @onn-m4 instead of m4h.
        GameSession.external = !intent.getBooleanExtra("launch", true)
        if (!GameSession.external) renderer = ShellUser.start("cd $PB && PB_FRAMES=$frames LD_LIBRARY_PATH=$PB ./linker64 $PB/m4h $PB/libvulkan_panfrost.so 2>&1")
        Thread({
            renderer?.inputStream?.bufferedReader()?.forEachLine { Log.i(TAG, "m4h: $it") }
            Log.i(TAG, "m4h exited ${renderer?.waitFor()}")
        }, "m4h-log").start()
        // The renderer listens on @onn-m4 first; the bridge's connect retries for 5 s.
        args = serviceArgs()
        Shizuku.bindUserService(args, conn)
    }

    private fun offer() {
        val lib = applicationInfo.nativeLibraryDir + "/libbufbridge.so"
        val rc = try { bridge?.offer(buffers, "onn-m4", lib, applicationInfo.sourceDir) } catch (e: Exception) { Log.e(TAG, "offer: $e"); null }
        Log.i(TAG, "bridge offer -> $rc (lib $lib)")
    }

    /** Renderer -> us: 'M4RD' index frame. Us -> renderer: index once it is on screen. */
    private fun serve() {
        try {
            ServerSocket().use { ss ->
                server = ss
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 7331), 1)
                Log.i(TAG, "listening on 127.0.0.1:7331")
                ss.accept().use { s ->
                    val input = DataInputStream(s.getInputStream()); val out = DataOutputStream(s.getOutputStream())
                    val hdr = ByteArray(12); val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                    var frames = 0; val t0 = System.nanoTime()
                    while (true) {
                        input.readFully(hdr); bb.rewind()
                        val magic = bb.getInt(); val i = bb.getInt(); bb.getInt()
                        if (magic != 0x4452344d || i !in buffers.indices) throw IllegalStateException("bad ready msg")
                        val bmp = bitmaps[i] ?: Bitmap.wrapHardwareBuffer(buffers[i], ColorSpace.get(ColorSpace.Named.SRGB))!!.also { bitmaps[i] = it }
                        view.holder.lockHardwareCanvas()?.let { c ->
                            // GL renders bottom-up into the dma-buf; flip on the canvas, free on the GPU.
                            c.save(); c.scale(1f, -1f, c.width / 2f, c.height / 2f)
                            c.drawBitmap(bmp, null, Rect(0, 0, c.width, c.height), paint)
                            c.restore()
                            view.holder.unlockCanvasAndPost(c)
                        }
                        val ack = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(i).array()
                        out.write(ack); out.flush()
                        frames++
                        if (frames % 60 == 0) Log.i(TAG, String.format("%d frames shown, %.1f fps", frames, frames / ((System.nanoTime() - t0) / 1e9)))
                    }
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "stream ended: $e")
        } finally { server = null }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { }
    // Vita-style: leaving the game (PS/HOME, another app) freezes it instead of killing it; buffers, sockets and
    // the bridge stay alive, so coming back is a SIGCONT and the next frame lands in the same HardwareBuffers.
    override fun surfaceDestroyed(holder: SurfaceHolder) { if (!isFinishing && GameSession.external) suspendGame() else stop() }

    private fun suspendGame() {
        if (GameSession.suspended) return
        GameSession.signal("STOP"); GameSession.suspended = true
        Log.i(TAG, "game suspended")
    }
    private fun resumeGame() {
        GameSession.signal("CONT"); GameSession.suspended = false
        Log.i(TAG, "game resumed")
    }

    private fun stop() {
        if (GameSession.external) { GameSession.signal("KILL"); GameSession.external = false; GameSession.suspended = false }
        try { server?.close() } catch (_: Exception) { }
        thread?.join(2000); thread = null
        audio.stop()
        renderer?.destroy(); renderer = null
        // Ask the bridge process to exit before unbinding: a bind right after unbind(remove=true) raced in Shizuku
        // and the fresh service never got the offer.
        runCatching { bridge?.destroy() }; bridge = null
        runCatching { Shizuku.unbindUserService(args, conn, true) }
        bitmaps.forEach { it?.recycle() }; bitmaps = emptyArray()
        buffers.forEach { it.close() }; buffers = emptyArray()
    }

    // The game reads the controller through evdev, so Android must not act on the same presses here:
    // BUTTON_B maps to BACK and would finish the activity mid-game. Long-press BACK is the exit.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN && event.isLongPress) { finish(); return true }
        return true
    }

    override fun onDestroy() { stop(); super.onDestroy() }

    companion object { private const val TAG = "M4"; private const val PB = "/data/local/tmp/pb" }
}

/** The one external game session (launched with --ez launch false) and whether it is frozen. */
object GameSession {
    @Volatile var external = false
    @Volatile var suspended = false
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Signal the game (everything we run goes through the bundled linker64). By process name, not pkill -f: toybox's
     *  pkill -STOP -f matched and froze its own sh, which hung the HOME main thread (ANR). Off the main thread, in order. */
    fun signal(sig: String) = worker.execute { ShellUser.run("p=\$(pidof linker64); [ -n \"\$p\" ] && kill -$sig \$p") }
}
