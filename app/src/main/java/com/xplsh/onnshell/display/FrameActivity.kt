package com.xplsh.onnshell.display

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.xplsh.onnshell.data.ShellUser

/**
 * M3 display surface: shows frames streamed by the 64-bit renderer (m3h) over 127.0.0.1:7331 and,
 * when Shizuku is usable, starts that renderer itself as the shell user, so no PC is needed.
 *
 *   adb shell am start -n com.xplsh.onnshell/.display.FrameActivity [--ei frames 600] [--ez launch false]
 *
 * Back finishes the activity and ends the renderer (Shizuku kills the process when we stop it).
 */
class FrameActivity : Activity() {
    private var renderer: Process? = null
    private lateinit var view: FrameReceiverView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = FrameReceiverView(this)
        setContentView(view)
    }

    override fun onResume() {
        super.onResume()
        view.start() // no-op before surfaceCreated; needed after a pause that kept the surface
        if (!intent.getBooleanExtra("launch", true) || renderer != null) return
        val frames = intent.getIntExtra("frames", 600)
        // Give the receiver a moment to bind before the renderer connects.
        val cmd = "sleep 1; cd $PB && PB_FRAMES=$frames LD_LIBRARY_PATH=$PB ./linker64 $PB/m3h $PB/libvulkan_panfrost.so 2>&1"
        renderer = ShellUser.start(cmd)
        if (renderer == null) { Log.w(TAG, "Shizuku not usable (${ShellUser.status()}); start m3h from adb"); return }
        Thread({
            renderer?.inputStream?.bufferedReader()?.forEachLine { Log.i(TAG, "m3h: $it") }
            Log.i(TAG, "m3h exited ${renderer?.waitFor()}")
        }, "m3h-log").start()
    }

    override fun onPause() {
        super.onPause()
        renderer?.destroy()
        renderer = null
        view.stop() // the launcher process outlives this activity; free 7331 now, not at GC
    }

    companion object {
        private const val TAG = "M3"
        private const val PB = "/data/local/tmp/pb"
    }
}
