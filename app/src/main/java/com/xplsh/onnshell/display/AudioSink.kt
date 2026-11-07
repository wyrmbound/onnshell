package com.xplsh.onnshell.display

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Plays raw PCM arriving on 127.0.0.1:7332 through AudioTrack. The game side needs no code:
 * SDL's "disk" audio driver writes real-time PCM to a FIFO that the shell's `nc` forwards here.
 * shortcut: format is fixed by the caller (sm64ex = 32000 Hz S16 stereo), no header; add one when a second game needs another rate.
 */
class AudioSink(private val rate: Int = 32000, private val channels: Int = 2) {
    private var server: ServerSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        thread = Thread({ serve() }, "audio-sink").also { it.start() }
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) { }
        thread?.join(2000); thread = null
    }

    private fun serve() {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        try {
            ServerSocket().use { ss ->
                server = ss
                ss.setReuseAddress(true)
                ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 7332), 1)
                Log.i(TAG, "listening on 127.0.0.1:7332 ($rate Hz, $channels ch)")
                while (true) {
                    val client = ss.accept()
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(min * 2).setTransferMode(AudioTrack.MODE_STREAM).build()
                    track.play()
                    val buf = ByteArray(min)
                    var total = 0L
                    client.use { c ->
                        val input = c.getInputStream()
                        while (true) {
                            val n = input.read(buf); if (n <= 0) break
                            track.write(buf, 0, n); total += n  // blocking write paces the sender
                        }
                    }
                    track.stop(); track.release()
                    Log.i(TAG, "client done, $total bytes")
                }
            }
        } catch (e: Exception) {
            if (server != null && !server!!.isClosed) Log.e(TAG, "audio sink", e)
        }
    }

    companion object { private const val TAG = "M4A" }
}
