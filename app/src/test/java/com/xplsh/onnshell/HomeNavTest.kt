package com.xplsh.onnshell

import com.xplsh.onnshell.ui.Shelf
import com.xplsh.onnshell.ui.Tile
import com.xplsh.onnshell.ui.rowTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeNavTest {
    private fun shelf(vararg ids: String) = Shelf("s", ids.map { Tile(it, it) {} }, cards = true)
    private val shelves = listOf(shelf("g1", "g2", "g3"), shelf(), shelf("a1", "a2", "a3", "a4"), shelf("s1"))
    private val reqs = shelves.flatMap { it.tiles }.associate { it.id to it.id }

    @Test fun downKeepsTheColumnAndSkipsEmptyRows() = assertEquals("a2", rowTarget(shelves, 0, 1, 1, reqs))
    @Test fun downClampsToAShorterRow() = assertEquals("s1", rowTarget(shelves, 2, 3, 1, reqs))
    @Test fun upSkipsEmptyRowsToo() = assertEquals("g3", rowTarget(shelves, 2, 3, -1, reqs))
    @Test fun nothingPastTheEnds() { assertNull(rowTarget(shelves, 0, 0, -1, reqs)); assertNull(rowTarget(shelves, 3, 0, 1, reqs)) }
}
