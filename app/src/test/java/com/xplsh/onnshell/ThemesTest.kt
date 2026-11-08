package com.xplsh.onnshell

import androidx.compose.ui.unit.dp
import com.xplsh.onnshell.ui.Focus
import com.xplsh.onnshell.ui.Grid
import com.xplsh.onnshell.ui.Tabs
import com.xplsh.onnshell.ui.Themes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemesTest {
    @Test fun byIdFallsBackToSlate() {
        assertEquals(Themes.SLATE, Themes.byId("nope"))
        assertEquals(Themes.SLATE, Themes.byId(null))
        assertEquals(Themes.INK, Themes.byId("ink"))
    }

    @Test fun idsAreDistinct() { assertEquals(3, Themes.ALL.map { it.id }.toSet().size) }

    @Test fun layoutsMatchTheMockups() {
        assertEquals(Tabs.TOP_PILLS, Themes.SLATE.tabs); assertEquals(Grid.HERO_ROW, Themes.SLATE.grid); assertEquals(Focus.RING, Themes.SLATE.focus)
        assertEquals(Tabs.LEFT_RAIL, Themes.INK.tabs); assertEquals(Grid.BUBBLES, Themes.INK.grid); assertTrue(Themes.INK.ambient)
        assertEquals(Tabs.MASTHEAD, Themes.MONO.tabs); assertEquals(Grid.STRIPS, Themes.MONO.grid); assertEquals(0.dp, Themes.MONO.tileRadius)
    }
}
