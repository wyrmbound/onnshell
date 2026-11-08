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

    /** The boot chime, always on the box speaker. */
    fun chime(ctx: Context, volume: Float = 1f): AudioDeviceInfo? {
        val d = device(ctx)
        MediaPlayer.create(ctx, R.raw.onnshell_startup)?.apply {
            preferredDevice = d
            setVolume(volume, volume)
            setOnCompletionListener { it.release() }
            start()
        }
        return d
    }
}
