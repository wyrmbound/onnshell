package com.xplsh.onnshell.data

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import com.xplsh.onnshell.R

/** The box's own small speaker (the onn 4K Pro has one), as an output next to the TV. */
object Speaker {
    /** The built-in speaker, or null: Android then picks the output itself, normally the TV over HDMI. */
    fun device(ctx: Context): AudioDeviceInfo? = ctx.getSystemService(AudioManager::class.java)
        .getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    /** Where game sound goes: the speaker when the setting is on, else the TV. */
    fun forGames(ctx: Context): AudioDeviceInfo? = if (Prefs.boxSpeaker(ctx)) device(ctx) else null

    /** The boot chime, always on the box speaker; [done] runs when it has ended. */
    fun chime(ctx: Context, volume: Float = 1f, done: () -> Unit = {}): AudioDeviceInfo? {
        val d = device(ctx)
        val p = MediaPlayer.create(ctx, R.raw.onnshell_startup)
        if (p == null) { done(); return d }
        p.preferredDevice = d
        p.setVolume(volume, volume)
        // the last half second fades out instead of cutting off, like all OnnShell music
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val fade = Runnable { Lobby.fadeOut(h, p, volume, Lobby.FADE_MS) { } }
        p.setOnCompletionListener { h.removeCallbacks(fade); it.release(); done() }
        p.start()
        h.postDelayed(fade, (p.duration - Lobby.FADE_MS).coerceAtLeast(0))
        return d
    }
}
