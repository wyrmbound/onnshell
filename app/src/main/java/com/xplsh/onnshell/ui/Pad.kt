package com.xplsh.onnshell.ui

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableSharedFlow

/** ARRIVE: a HOME intent reached us from another app (just show Home). LAST: PS pressed while already home (back to the last app or the suspended game). */
enum class Cmd { DETAILS, OPTIONS, MENU, HOME, ARRIVE, LAST, TAB_PREV, TAB_NEXT }

/**
 * Gamepad state shared between MainActivity (which sees raw events) and the UI.
 * A select and B back are remapped to DPAD_CENTER and BACK in MainActivity.
 */
object Pad {
    val cmds = MutableSharedFlow<Cmd>(extraBufferCapacity = 8)

    /** Controller test mode: every pad event is recorded and none is acted on. */
    var testing by mutableStateOf(false)

    /** Time of the last key, so idle animations can stop drawing (a TV sits on HOME for hours). */
    var lastInput by mutableLongStateOf(System.currentTimeMillis())

    /** Lite effects, live: the settings toggle and the ADB EFFECTS command both flip it. */
    var lite by mutableStateOf(false)
    val held = mutableStateListOf<String>()
    var last by mutableStateOf("")
    val axes = mutableStateMapOf<String, Float>()

    fun record(e: KeyEvent) {
        val name = KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (name !in held) { held += name; last = name }
            KeyEvent.ACTION_UP -> held -= name
        }
    }

    fun record(e: MotionEvent) {
        listOf(
            "LX" to MotionEvent.AXIS_X, "LY" to MotionEvent.AXIS_Y,
            "RX" to MotionEvent.AXIS_Z, "RY" to MotionEvent.AXIS_RZ,
            "L2" to MotionEvent.AXIS_LTRIGGER, "R2" to MotionEvent.AXIS_RTRIGGER,
            "HX" to MotionEvent.AXIS_HAT_X, "HY" to MotionEvent.AXIS_HAT_Y,
        ).forEach { (k, a) -> axes[k] = e.getAxisValue(a) }
    }
}
