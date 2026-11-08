package com.xplsh.onnshell.data

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.xplsh.onnshell.R
import java.util.Calendar

/**
 * Lobby music while the home screen is in front: "Lo-fi Lobby", or its slower late-night take from midnight to 4 am,
 * and the shop tune while the Store is open. All are original loops made for OnnShell.
 * It plays where game sound goes (TV or box speaker), waits out the boot chime, and never cuts off:
 * it fades out over half a second before it stops or another tune takes over (owner ask).
 */
object Lobby {
    /** Background level: about half as loud to the ear as the files' own level (-10 dB). */
    const val VOL = 0.32f
    const val FADE_MS = 500L
    private const val TAG = "Lobby"

    private var player: MediaPlayer? = null
    private var track = 0
    /** The Store is on screen: its own tune instead of the lobby. */
    private var store = false
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

    private fun wanted() = when { store -> R.raw.lobby_shop; isNight() -> R.raw.lobby_lofi_night; else -> R.raw.lobby_lofi }

    /** Start or resume (home came to the front), or change tune. Call on the main thread. */
    fun play(ctx: Context) {
        val a = ctx.applicationContext; app = a; front = true
        if (held || !Prefs.lobbyMusic(a)) { stop(); return }
        val want = wanted()
        val cur = player
        if (cur != null && want != track) { // another tune: the old one fades out first, then this runs again
            if (runCatching { cur.isPlaying }.getOrDefault(false)) { fadeOut(cur) { release(); if (front) play(a) }; return }
            release()
        }
        cancelFade() // a fade toward a pause or a stop: we are staying after all
        val p = player ?: MediaPlayer.create(a, want)?.apply { isLooping = true }?.also { player = it; track = want }
            ?: run { android.util.Log.w(TAG, "no player"); return }
        android.util.Log.i(TAG, "play track=$want")
        p.preferredDevice = Speaker.forGames(a)
        p.setVolume(VOL, VOL)
        if (!p.isPlaying) p.start()
        main.removeCallbacks(clock); main.postDelayed(clock, 60_000)
    }

    /** Home went to the back (an app, a game, the screen off): fade out, then pause. */
    fun pause() {
        front = false
        main.removeCallbacks(clock)
        val p = player ?: return
        android.util.Log.i(TAG, "pause")
        if (runCatching { p.isPlaying }.getOrDefault(false)) fadeOut(p) { runCatching { p.pause() } }
    }

    /** Fade out and let go of the player (the setting was switched off). */
    fun stop() {
        main.removeCallbacks(clock)
        val p = player ?: return
        if (runCatching { p.isPlaying }.getOrDefault(false)) fadeOut(p) { release() } else release()
    }

    private fun release() {
        cancelFade()
        runCatching { player?.release() }
        player = null
    }

    private var fade: Runnable? = null
    private fun cancelFade() { fade?.let { main.removeCallbacks(it) }; fade = null }
    private fun fadeOut(p: MediaPlayer, then: () -> Unit) {
        cancelFade()
        fade = fadeOut(main, p, VOL, FADE_MS) { fade = null; then() }
    }

    /**
     * Ramps [p] from [from] to silence over [ms] on [h]'s thread, then runs [then]. Returns the runnable, to cancel it.
     * Shared with the boot chime ([Speaker.chime]).
     */
    fun fadeOut(h: Handler, p: MediaPlayer, from: Float, ms: Long, then: () -> Unit): Runnable {
        val t0 = SystemClock.uptimeMillis()
        val r = object : Runnable {
            override fun run() {
                val k = 1f - (SystemClock.uptimeMillis() - t0).toFloat() / ms
                if (k <= 0f) { runCatching { p.setVolume(0f, 0f) }; then() }
                else { runCatching { p.setVolume(from * k, from * k) }; h.postDelayed(this, 20) }
            }
        }
        h.post(r)
        return r
    }

    /** The Store opened or closed: switch tunes now if the home screen is in front. */
    fun setStore(ctx: Context, on: Boolean) {
        if (store == on) return
        store = on
        if (front) play(ctx)
    }

    /** The Game sound setting changed: follow it now. */
    fun reroute(ctx: Context) { player?.preferredDevice = Speaker.forGames(ctx) }

    // Swaps day and night tracks at midnight and 4 am while the home screen just sits there.
    private val clock: Runnable = object : Runnable {
        override fun run() {
            val a = app ?: return
            if (!front || player == null) return
            if (wanted() != track) play(a) else main.postDelayed(this, 60_000)
        }
    }
}
