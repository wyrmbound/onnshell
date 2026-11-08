package com.xplsh.onnshell

import android.media.MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.xplsh.onnshell.admin.Policy
import com.xplsh.onnshell.data.Inputd
import rikka.shizuku.Shizuku
import com.xplsh.onnshell.ui.Cmd
import com.xplsh.onnshell.ui.Pad
import com.xplsh.onnshell.ui.Shell

/** The HOME activity. Maps the gamepad (A select, B back, X details, Y options, Start menu, PS home). */
class MainActivity : ComponentActivity() {
    private var resumes by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pad.lite = com.xplsh.onnshell.data.Prefs.lite(this)
        // Idempotent, no-op unless device owner. Off the main thread for cold start. Never allowed to
        // crash HOME: after a power cut the admin can be inactive and every policy call throws.
        Thread { runCatching { Policy.apply(this) } }.start()
        onBackPressedDispatcher.addCallback(this) {} // HOME root has nowhere to go back to
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Shell(resumes) } }
        playStartupJingle()
        // Shizuku is started by us after boot (ShizukuBoot); start the touchpad daemon as soon as it is up
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        com.xplsh.onnshell.data.ShizukuBoot.ensure(this)
    }

    /** Boot chime on the box speaker: once per boot, and only when HOME comes up soon after boot, not on a later cold restart. */
    private fun playStartupJingle() {
        if (jinglePlayed || SystemClock.elapsedRealtime() > 90_000L) return
        jinglePlayed = true
        runCatching { com.xplsh.onnshell.data.Speaker.chime(this) }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        Inputd.start(this)
        Thread { com.xplsh.onnshell.data.GameFiles.grantStorage(packageName) }.start() // the file picker reads the drives
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        super.onDestroy()
    }

    /** HOME pressed (remote, or PS through inputd) while OnnShell is already up: back to the main screen. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Android pauses a resumed activity before onNewIntent, so "paused" can't tell us where we were.
        // Stopped can: behind another app we were stopped (came home -> ARRIVE); already in front we were
        // only paused (PS at home -> LAST, back to the last app or the suspended game).
        if (intent.hasCategory(android.content.Intent.CATEGORY_HOME)) {
            // "started" alone misreads PS in a game: Android starts HOME just before this call, so it looked like PS at
            // home and resumed the game it had just frozen. The game screen notes when it was left; that HOME is an arrival.
            val fromGame = SystemClock.uptimeMillis() - com.xplsh.onnshell.display.GameSession.leftAt < 1000
            val ok = Pad.cmds.tryEmit(if (started && !fromGame) Cmd.LAST else Cmd.ARRIVE)
            android.util.Log.i("M4", "home intent: started=$started suspended=${com.xplsh.onnshell.display.GameSession.suspended} emitted=$ok")
        }
    }

    private var started = false
    override fun onStart() { super.onStart(); started = true }
    override fun onStop() { started = false; super.onStop() }

    override fun onResume() {
        super.onResume()
        resumes++
        // Normally granted from ADB (scripts/shizuku-start); this is the fallback dialog on the TV
        com.xplsh.onnshell.data.ShellUser.requestIfNeeded()
        com.xplsh.onnshell.data.ShizukuBoot.ensure(this) // no-op while Shizuku runs; retries a missed "Allow" prompt
        Inputd.start(this) // no-op while it runs
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        // Just back from a game: drop leftover gameplay presses (see GameSession.endedAt)
        if (justBackFromGame() && code != KeyEvent.KEYCODE_HOME) {
            if (e.action == KeyEvent.ACTION_DOWN) swallowed += code
            return true
        }
        // ...and the release of a press that began inside that window (X/Y/Options/PS act on release)
        if (e.action == KeyEvent.ACTION_UP && swallowed.remove(code)) return true
        Pad.lastInput = System.currentTimeMillis()
        if (Pad.testing) {
            // Exit: remote Back, or Share (SELECT) and Options (START) together
            val combo = e.action == KeyEvent.ACTION_DOWN &&
                ((code == KeyEvent.KEYCODE_BUTTON_START && "BUTTON_SELECT" in Pad.held) ||
                    (code == KeyEvent.KEYCODE_BUTTON_SELECT && "BUTTON_START" in Pad.held))
            if (combo || (code == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP)) {
                Pad.held.clear()
                Pad.testing = false
            } else Pad.record(e)
            return true
        }
        val cmd = when (code) {
            KeyEvent.KEYCODE_BUTTON_X -> Cmd.DETAILS
            KeyEvent.KEYCODE_BUTTON_Y -> Cmd.OPTIONS
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> Cmd.MENU
            KeyEvent.KEYCODE_BUTTON_L1 -> Cmd.TAB_PREV // shoulders flip the home tabs
            KeyEvent.KEYCODE_BUTTON_R1 -> Cmd.TAB_NEXT
            // PS is HOME everywhere: inputd presses HOME for other apps, here it closes any panel
            KeyEvent.KEYCODE_BUTTON_MODE -> Cmd.HOME
            KeyEvent.KEYCODE_BUTTON_SELECT -> return true // only used in combos
            else -> null
        }
        if (cmd != null) {
            if (e.action == KeyEvent.ACTION_UP) Pad.cmds.tryEmit(cmd)
            return true
        }
        val mapped = when (code) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_DPAD_CENTER
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BACK
            else -> return super.dispatchKeyEvent(e)
        }
        return super.dispatchKeyEvent(
            KeyEvent(e.downTime, e.eventTime, e.action, mapped, e.repeatCount, e.metaState, e.deviceId, e.scanCode, e.flags, e.source)
        )
    }

    private val swallowed = mutableSetOf<Int>()
    private fun justBackFromGame() = SystemClock.uptimeMillis() - com.xplsh.onnshell.display.GameSession.endedAt < 2000

    // In POINTER mode the DS4 touchpad drives Android's mouse pointer and its click is a tap; guard those too.
    override fun dispatchTouchEvent(e: MotionEvent): Boolean = if (justBackFromGame()) true else super.dispatchTouchEvent(e)

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (Pad.testing && e.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            Pad.record(e)
            return true
        }
        return super.dispatchGenericMotionEvent(e)
    }

    companion object { private var jinglePlayed = false }
}
