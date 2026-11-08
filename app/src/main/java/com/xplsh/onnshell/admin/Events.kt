package com.xplsh.onnshell.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import com.xplsh.onnshell.data.ShellUser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Box events that logcat loses across a reboot (who rebooted, when Wi-Fi went off), appended to
 * <external files>/events.log. Pull it with: adb pull /sdcard/Android/data/com.xplsh.onnshell/files/events.log
 */
object Events {
    private const val TAG = "Events"
    private const val MAX = 256 * 1024

    @Synchronized
    fun log(ctx: Context, msg: String) {
        Log.w(TAG, msg)
        runCatching {
            val f = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "events.log")
            if (f.length() > MAX) f.writeText(f.readText().takeLast(MAX / 2))
            f.appendText("${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} $msg\n")
        }
    }

    private var wifiWatch: BroadcastReceiver? = null

    /**
     * Something turns Wi-Fi off now and then (not OnnShell, not the vendor suspend hook, whose property is unset), and
     * the setting persists, so the box comes back from a reboot with no adb and no Shizuku self-start. Log each time it
     * goes off with what we can see of the cause, then switch it back on: this box is only reachable over Wi-Fi.
     */
    @Synchronized
    fun watchWifi(ctx: Context) {
        if (wifiWatch != null) return
        val app = ctx.applicationContext
        wifiWatch = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1) != WifiManager.WIFI_STATE_DISABLED) return
                val awake = app.getSystemService(PowerManager::class.java).isInteractive
                log(app, "wifi DISABLED (screen ${if (awake) "on" else "off"})")
                Thread({
                    // The shell user can read what the app cannot: the Wi-Fi API caller log and the top activity.
                    if (ShellUser.ready()) runCatching {
                        val p = ShellUser.start("dumpsys wifi | grep -iE 'setWifiEnabled|API name|toggl' | tail -8; " +
                            "dumpsys activity activities | grep -m2 -E 'topResumedActivity|mResumedActivity'")
                        p?.inputStream?.bufferedReader()?.readText()?.lines()?.filter { it.isNotBlank() }
                            ?.forEach { log(app, "  wifi-off context: ${it.trim()}") }
                        p?.waitFor()
                    }
                    Thread.sleep(3000)
                    val wm = app.getSystemService(WifiManager::class.java)
                    @Suppress("DEPRECATION") // Device Owner apps may still toggle Wi-Fi
                    if (!wm.isWifiEnabled) log(app, "wifi re-enable -> ${runCatching { wm.setWifiEnabled(true) }.getOrDefault(false)}")
                }, "wifi-watch").start()
            }
        }
        app.registerReceiver(wifiWatch, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))
    }
}
