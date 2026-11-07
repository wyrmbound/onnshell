package com.xplsh.onnshell.data

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Starts Shizuku after boot without the PC: connect to the box's own adbd on 127.0.0.1:5555 (adb over TCP stays
 * on across reboots) and run Shizuku's starter as the shell user, the same steps as scripts/shizuku-start.ps1.
 * First run only: the TV asks "Allow USB debugging?" for OnnShell's key; "Always allow" makes it silent after that.
 */
object ShizukuBoot {
    private const val TAG = "ShizukuBoot"
    @Volatile private var busy = false

    private const val STARTER =
        "LIB=\$(dumpsys package moe.shizuku.privileged.api | grep -m1 legacyNativeLibraryDir | sed 's/.*legacyNativeLibraryDir=//'); " +
            "cp \"\$LIB/arm/libshizuku.so\" /data/local/tmp/shizuku_starter && chmod 700 /data/local/tmp/shizuku_starter && " +
            "/data/local/tmp/shizuku_starter"

    /** No-op when Shizuku already runs or a start is in flight. Never on the main thread, never throws. */
    fun ensure(ctx: Context) {
        if (busy || runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return
        busy = true
        val app = ctx.applicationContext
        Thread {
            runCatching { start(app) }.onFailure { Log.w(TAG, "start failed: $it") }
            busy = false
        }.start()
    }

    private fun start(ctx: Context) {
        val priv = File(ctx.filesDir, "adbkey"); val pub = File(ctx.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        // adbd may not be listening yet right after boot: retry for about a minute
        repeat(12) { attempt ->
            val ok = runCatching {
                Dadb.create("127.0.0.1", 5555, AdbKeyPair.read(priv, pub)).use { adb ->
                    val r = adb.shell(STARTER)
                    Log.i(TAG, "starter exit ${r.exitCode}: ${r.allOutput.trim().lines().lastOrNull()}")
                }
            }.onFailure { Log.i(TAG, "attempt ${attempt + 1}: $it") }.isSuccess
            if (ok && runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return
            if (ok) { Thread.sleep(2000); if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return }
            Thread.sleep(5000)
        }
    }
}
