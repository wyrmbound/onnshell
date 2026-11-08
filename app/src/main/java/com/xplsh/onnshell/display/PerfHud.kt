package com.xplsh.onnshell.display

import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.xplsh.onnshell.data.ShellUser

/**
 * The in-game performance overlay (owner ask, 2026-11-09): fps, SoC temperature, CPU clock, the game's memory and the
 * box's free memory, plus the chip. Toggled in OnnShell settings (Prefs.perfHud) or over ADB (HUD --ez on true|false).
 * fps counts frames this screen actually presented; the rest is read as the shell user every [SLOW_MS], because the
 * thermal service and the game's /proc entry are not readable by an app.
 */
class PerfHud(private val parent: FrameLayout) {
    private val text = TextView(parent.context).apply {
        setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.MONOSPACE
        setBackgroundColor(Color.argb(150, 0, 0, 0)); setPadding(18, 10, 18, 10)
        visibility = android.view.View.GONE
    }
    @Volatile private var running = false
    private var thread: Thread? = null

    init {
        parent.addView(text, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
            .apply { setMargins(24, 24, 0, 0) })
    }

    fun show(on: Boolean) {
        text.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        if (on && !running) start() else if (!on) stop()
    }

    private fun start() {
        running = true
        thread = Thread({
            var last = GameSession.frames.get(); var lastT = System.nanoTime(); var slow = Stats(); var slowAt = 0L
            while (running) {
                val now = System.nanoTime(); val f = GameSession.frames.get()
                val fps = (f - last) / ((now - lastT) / 1e9); last = f; lastT = now
                if (now - slowAt >= SLOW_MS * 1_000_000L) { slow = sample(); slowAt = now }
                val line = format(fps, slow, Build.SOC_MODEL)
                text.post { text.text = line }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }, "perf-hud").also { it.start() }
    }

    fun stop() { running = false; thread?.interrupt(); thread = null }

    /** One shell round trip: SoC temperature, game RSS, CPU0 clock, free and total memory. */
    private fun sample(): Stats {
        val out = ShellUser.output(
            "dumpsys thermalservice | grep -o -m1 'mValue=[0-9.]*, mType=0, mName=soc_thermal' | cut -d= -f2 | cut -d, -f1; " +
                "p=\$(pidof linker64 | cut -d' ' -f1); [ -n \"\$p\" ] && grep VmRSS /proc/\$p/status | tr -s ' ' | cut -d' ' -f2 || echo; " +
                "cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq; " +
                "grep -E '^(MemAvailable|MemTotal):' /proc/meminfo | tr -s ' ' | cut -d' ' -f2"
        ) ?: return Stats()
        return parse(out)
    }

    data class Stats(val tempC: Float? = null, val gameKb: Long? = null, val cpuKhz: Long? = null, val totalKb: Long? = null, val availKb: Long? = null)

    companion object {
        private const val SLOW_MS = 3000L

        /** The sample's lines in order: temperature, game RSS kB, CPU kHz, MemTotal kB, MemAvailable kB (meminfo lists Total first). */
        fun parse(out: String): Stats {
            val l = out.lines().map { it.trim() }
            return Stats(l.getOrNull(0)?.toFloatOrNull(), l.getOrNull(1)?.toLongOrNull(), l.getOrNull(2)?.toLongOrNull(), l.getOrNull(3)?.toLongOrNull(), l.getOrNull(4)?.toLongOrNull())
        }

        fun format(fps: Double, s: Stats, soc: String): String {
            fun gb(kb: Long) = "%.1f".format(kb / 1048576.0)
            val top = listOfNotNull("%.0f fps".format(fps), s.tempC?.let { "%.0f°C".format(it) }, s.cpuKhz?.let { "CPU %.2f GHz".format(it / 1e6) })
            val mid = listOfNotNull(s.gameKb?.let { "game ${it / 1024} MB" }, if (s.availKb != null && s.totalKb != null) "free ${gb(s.availKb)}/${gb(s.totalKb)} GB" else null)
            return listOf(top.joinToString("  "), mid.joinToString("  "), "$soc · Mali-G310 · PanVK").filter { it.isNotBlank() }.joinToString("\n")
        }
    }
}
