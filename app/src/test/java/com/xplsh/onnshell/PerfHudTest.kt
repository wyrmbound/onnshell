package com.xplsh.onnshell

import com.xplsh.onnshell.display.PerfHud
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerfHudTest {
    @Test fun parseReadsTheShellSample() {
        val s = PerfHud.parse("52.7\n433152\n1800000\n3010252\n1490836\n")
        assertEquals(52.7f, s.tempC); assertEquals(433152L, s.gameKb); assertEquals(1800000L, s.cpuKhz)
        assertEquals(3010252L, s.totalKb); assertEquals(1490836L, s.availKb)
    }

    @Test fun noGameLeavesAGapNotAShift() {
        val s = PerfHud.parse("52.7\n\n1800000\n3010252\n1490836\n")
        assertNull(s.gameKb); assertEquals(1800000L, s.cpuKhz); assertEquals(1490836L, s.availKb)
    }

    @Test fun formatShowsWhatIsKnown() {
        assertEquals("60 fps  53°C  CPU 1.80 GHz\ngame 423 MB  free 1.4/2.9 GB\nS905X5M · Mali-G310 · PanVK",
            PerfHud.format(59.6, PerfHud.parse("52.7\n433152\n1800000\n3010252\n1490836"), "S905X5M"))
        assertEquals("30 fps\nS905X5M · Mali-G310 · PanVK", PerfHud.format(30.0, PerfHud.Stats(), "S905X5M"))
    }
}
