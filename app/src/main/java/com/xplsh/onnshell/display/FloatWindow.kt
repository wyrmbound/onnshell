package com.xplsh.onnshell.display

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * The game's own corner window (owner ask, 2026-11-09: Android TV's picture-in-picture is fixed at about 384x216).
 * A 16:9 overlay over whatever app plays full screen, at a size and corner from OnnShell settings. It never takes focus
 * or touches, so the app behind it keeps the TV remote; the pad goes to the game through libonngrab.
 * Added through the application context: a window tied to the game activity would hide when that activity goes to the back.
 */
class FloatWindow(ctx: Context, private val sizePct: Int, private val corner: Int) {
    private val app = ctx.applicationContext
    private val wm = app.getSystemService(WindowManager::class.java)
    private val surface = SurfaceView(app)
    private val frame = FrameLayout(app).apply {
        setBackgroundColor(Color.argb(230, 20, 20, 24)) // a thin dark frame, so the game reads as a window over bright video
        setPadding(BORDER, BORDER, BORDER, BORDER)
        addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
    val holder: SurfaceHolder get() = surface.holder

    fun show() {
        val screen = wm.currentWindowMetrics.bounds
        val (w, h) = size(screen.width(), sizePct)
        val lp = WindowManager.LayoutParams(
            w + 2 * BORDER, h + 2 * BORDER,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = GRAVITY[corner.coerceIn(GRAVITY.indices)]
            val margin = screen.width() * 3 / 100 // clear of the TV's overscan edge
            x = margin; y = margin
            title = "OnnShell game"
        }
        wm.addView(frame, lp)
    }

    fun hide() { runCatching { wm.removeView(frame) } }

    companion object {
        private const val BORDER = 4
        /** Bottom right (the default), bottom left, top right, top left. */
        val GRAVITY = intArrayOf(Gravity.BOTTOM or Gravity.END, Gravity.BOTTOM or Gravity.START, Gravity.TOP or Gravity.END, Gravity.TOP or Gravity.START)
        /** The presets: percent of the screen width. */
        val SIZES = intArrayOf(25, 33, 45)

        /** Window size for a screen [width] at [pct] percent, 16:9 like the TV, rounded down to even pixels. */
        fun size(width: Int, pct: Int): Pair<Int, Int> {
            val w = (width * pct / 100) and 1.inv()
            return w to ((w * 9 / 16) and 1.inv())
        }
    }
}
