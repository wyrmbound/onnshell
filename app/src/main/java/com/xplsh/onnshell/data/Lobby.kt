package com.xplsh.onnshell.data

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.xplsh.onnshell.R
import java.util.Calendar

/**
 * Lobby music while the home screen is in front: "Lo-fi Lobby", or its slower late-night take from midnight to 4 am.
 * Both are original loops made for OnnShell. It plays where game sound goes (TV or box speaker) and waits out the boot chime.
 */
object Lobby {
    private var player: MediaPlayer? = null
    private var night = false
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    /** True while the boot chime plays; the music starts when it ends. */
    var held = false
    /** ADB test hook (LOBBY --ez night): force the night (true) or day (false) track; null follows the clock. */
    var forceNight: Boolean? = null
    /** Whether the home screen is in front (between play and pause). */
    var front = false
        private set

    fun isNight(hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) = forceNight ?: (hour in 0..3)

    /** Start or resume (home came to the front). Call on the main thread. */
    fun play(ctx: Context) {
        val a = ctx.applicationContext; app = a; front = true
        if (held || !Prefs.lobbyMusic(a)) { stop(); return }
        val n = isNight()
        android.util.Log.i(TAG, "play night=$n has=${player != null}")
        val p = player?.takeIf { n == night } ?: run {
            stop(); night = n
            MediaPlayer.create(a, if (n) R.raw.lobby_lofi_night else R.raw.lobby_lofi)?.apply { isLooping = true }
        } ?: run { android.util.Log.w(TAG, "no player"); return }
        player = p
        p.preferredDevice = Speaker.forGames(a)
        if (!p.isPlaying) p.start()
        main.removeCallbacks(clock); main.postDelayed(clock, 60_000)
    }

    /** Home went to the back (an app, a game, the screen off). */
    fun pause() {
        front = false
        android.util.Log.i(TAG, "pause")
        main.removeCallbacks(clock)
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
    }

    fun stop() {
        main.removeCallbacks(clock)
        runCatching { player?.release() }
        player = null
    }

    /** The Game sound setting changed: follow it now. */
    private const val TAG = "Lobby"

    fun reroute(ctx: Context) { player?.preferredDevice = Speaker.forGames(ctx) }

    // Swaps day and night tracks at midnight and 4 am while the home screen just sits there.
    private val clock: Runnable = object : Runnable {
        override fun run() {
            val a = app ?: return
            if (player?.isPlaying != true) return
            if (isNight() != night) play(a) else main.postDelayed(this, 60_000)
        }
    }
}
