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

    /**
     * Flight recorder: Wi-Fi dies sometimes and only a reboot fixes it, which wipes logcat. Once per boot, keep the
     * previous boot's log as onnlog.prev and record main/system/crash/kernel to a rotating file (6 x 4 MB).
     * Read after the fact with: adb shell ls /data/local/tmp/onnlog.prev
     */
    private const val RECORDER =
        "B=\$(cat /proc/sys/kernel/random/boot_id); D=/data/local/tmp/onnlog; " +
            "if [ \"\$(cat \$D/boot_id 2>/dev/null)\" != \"\$B\" ]; then rm -rf \$D.prev; [ -d \$D ] && mv \$D \$D.prev; " +
            "mkdir -p \$D; echo \$B > \$D/boot_id; " +
            "setsid nohup logcat -b main,system,crash,kernel -v time -f \$D/log -r 4096 -n 6 </dev/null >/dev/null 2>&1 & fi"

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
                // socket timeout: an unanswered "Allow USB debugging?" prompt must not block us (and `busy`) forever
                Dadb.create("127.0.0.1", 5555, AdbKeyPair.read(priv, pub), 5_000, 30_000).use { adb ->
                    runCatching { adb.shell(RECORDER) }
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
