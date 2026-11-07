package com.xplsh.onnshell.data

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Commands as the shell user, through Shizuku. Everything here is a no-op when Shizuku is not usable. */
object ShellUser {
    private fun running() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    private fun allowed() = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    /** Shizuku server is up and OnnShell may use it. */
    fun ready() = running() && allowed()

    /** "running", "not allowed" or "not running", for the ADB status screen. */
    fun status() = when {
        !running() -> "not running"
        !allowed() -> "not allowed"
        else -> "running"
    }

    /** Asks Shizuku for access (a dialog on the TV) when it runs but OnnShell is not allowed yet. */
    fun requestIfNeeded() {
        if (running() && !allowed() && runCatching { !Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)) {
            runCatching { Shizuku.requestPermission(7) }
        }
    }

    /**
     * Starts [cmd] with sh -c as the shell user and returns the running process (its stdin and stdout
     * are pipes to us), or null when Shizuku is not usable. Shizuku ends the process if OnnShell dies.
     * newProcess is private in the Shizuku API (it prefers bound services), so it is reached by
     * reflection; proguard-rules.pro keeps it.
     */
    fun start(cmd: String): Process? {
        if (!ready()) return null
        return runCatching {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
            ).apply { isAccessible = true }
            m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        }.getOrNull()
    }

    /** Runs [cmd] as the shell user and waits. Returns the exit code, or null when Shizuku is not usable. */
    fun run(cmd: String): Int? = runCatching { start(cmd)?.waitFor() }.getOrNull()
}
