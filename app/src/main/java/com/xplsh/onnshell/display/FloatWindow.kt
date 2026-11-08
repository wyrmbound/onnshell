package com.xplsh.onnshell.display

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * The game's own corner window (owner ask, 2026-11-09: Android TV's picture-in-picture is fixed at about 384x216).
 * A 16:9 overlay over whatever app plays full screen, at a size and corner from OnnShell settings. It never takes focus,
 * so the app behind it keeps the TV remote; the pad goes to the game through libonngrab.
 *
 * Draggable with the DS4 touchpad (owner ask): the touchpad drives Android's pointer, which libonngrab leaves alone.
 * Press the pad over the window and slide; the frame lights up under the pointer. Clicks outside go to the app behind.
 * Where it was left is kept ([at], reported through [onMoved]) until a corner preset is picked again.
 *
 * Added through the application context: a window tied to the game activity would hide when that activity goes to the back.
 */
class FloatWindow(
    ctx: Context, private val sizePct: Int, private val corner: Int,
    private val at: Pair<Int, Int>? = null, private val onMoved: (Int, Int) -> Unit = { _, _ -> },
) {
    private val app = ctx.applicationContext
    private val wm = app.getSystemService(WindowManager::class.java)
    private val surface = SurfaceView(app)
    private val frame = FrameLayout(app).apply {
        setBackgroundColor(IDLE) // a thin dark frame, so the game reads as a window over bright video
        setPadding(BORDER, BORDER, BORDER, BORDER)
        addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
    private lateinit var lp: WindowManager.LayoutParams
    val holder: SurfaceHolder get() = surface.holder

    fun show() {
        val screen = wm.currentWindowMetrics.bounds
        val (w, h) = size(screen.width(), sizePct)
        lp = WindowManager.LayoutParams(
            w + 2 * BORDER, h + 2 * BORDER,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // not focusable (implies not touch-modal): only presses on the window itself reach it
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        ).apply {
            title = "OnnShell game"
            if (at != null) {
                gravity = Gravity.TOP or Gravity.START
                x = clamp(at.first, screen.width() - width); y = clamp(at.second, screen.height() - height)
            } else {
                gravity = GRAVITY[corner.coerceIn(GRAVITY.indices)]
                val margin = screen.width() * 3 / 100 // clear of the TV's overscan edge
                x = margin; y = margin
            }
        }
        listOf(frame, surface).forEach { it.setOnTouchListener(drag) }
        frame.setOnHoverListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> frame.setBackgroundColor(HOVER)
                MotionEvent.ACTION_HOVER_EXIT -> frame.setBackgroundColor(IDLE)
            }
            false
        }
        wm.addView(frame, lp)
    }

    fun hide() { runCatching { wm.removeView(frame) } }

    private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0

    @SuppressLint("ClickableViewAccessibility") // nothing to click: the window only moves
    private val drag = View.OnTouchListener { _, e ->
        val screen = wm.currentWindowMetrics.bounds
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // switch to absolute top-left coordinates, wherever the preset put the window
                val loc = IntArray(2); frame.getLocationOnScreen(loc)
                lp.gravity = Gravity.TOP or Gravity.START; lp.x = loc[0]; lp.y = loc[1]
                startX = lp.x; startY = lp.y; downX = e.rawX; downY = e.rawY
                frame.setBackgroundColor(HOVER)
            }
            MotionEvent.ACTION_MOVE -> {
                lp.x = clamp(startX + (e.rawX - downX).toInt(), screen.width() - lp.width)
                lp.y = clamp(startY + (e.rawY - downY).toInt(), screen.height() - lp.height)
                runCatching { wm.updateViewLayout(frame, lp) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { frame.setBackgroundColor(IDLE); onMoved(lp.x, lp.y) }
        }
        true
    }

    companion object {
        private const val BORDER = 4
        private const val IDLE = 0xE6141418.toInt() // plain ints: android.graphics.Color is a stub in unit tests
        private const val HOVER = 0xFFFFFFFF.toInt()
        /** Bottom right (the default), bottom left, top right, top left. */
        val GRAVITY = intArrayOf(Gravity.BOTTOM or Gravity.END, Gravity.BOTTOM or Gravity.START, Gravity.TOP or Gravity.END, Gravity.TOP or Gravity.START)
        /** The presets: percent of the screen width. */
        val SIZES = intArrayOf(25, 33, 45)

        /** Window size for a screen [width] at [pct] percent, 16:9 like the TV, rounded down to even pixels. */
        fun size(width: Int, pct: Int): Pair<Int, Int> {
            val w = (width * pct / 100) and 1.inv()
            return w to ((w * 9 / 16) and 1.inv())
        }

        /** Keeps a window edge on screen: 0..[max]. */
        fun clamp(v: Int, max: Int) = v.coerceIn(0, max.coerceAtLeast(0))
    }
}
