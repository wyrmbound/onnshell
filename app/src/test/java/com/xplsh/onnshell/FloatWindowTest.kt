package com.xplsh.onnshell

import com.xplsh.onnshell.display.FloatWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatWindowTest {
    @Test fun presetsAre16by9AndEven() {
        for (pct in FloatWindow.SIZES) for (screen in intArrayOf(1920, 3840, 1280)) {
            val (w, h) = FloatWindow.size(screen, pct)
            assertTrue("even $w x $h", w % 2 == 0 && h % 2 == 0)
            assertTrue("16:9 $w x $h", kotlin.math.abs(w * 9 - h * 16) <= 32)
        }
    }

    @Test fun mediumOn1080pIsBiggerThanTvPip() {
        assertEquals(632 to 354, FloatWindow.size(1920, 33)) // Android TV's own PiP here: 384x216
        assertEquals(864 to 486, FloatWindow.size(1920, 45))
    }
}
