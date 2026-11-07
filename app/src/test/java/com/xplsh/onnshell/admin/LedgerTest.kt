package com.xplsh.onnshell.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LedgerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun addIsIdempotentAndSurvivesReload() {
        val l = Ledger(tmp.root)
        l.add("owner", "a/.B", "undo owner", now = 1)
        l.add("hide", "pkg.x", "undo x", now = 2)
        l.add("hide", "pkg.x", "undo x again", now = 3)
        val reloaded = Ledger(tmp.root).entries()
        assertEquals(listOf("owner", "hide"), reloaded.map { it.kind })
        assertEquals("undo x", reloaded[1].restore)
    }

    @Test fun restoreScriptIsNewestFirstAndShrinksOnRemove() {
        val l = Ledger(tmp.root)
        l.add("owner", "o", "CLEAR_OWNER")
        val hide = l.add("hide", "p", "UNHIDE p")
        val lines = { tmp.root.resolve("restore.sh").readLines().filter { !it.startsWith("#") } }
        assertEquals(listOf("UNHIDE p", "CLEAR_OWNER"), lines())
        l.remove(hide)
        assertEquals(listOf("CLEAR_OWNER"), lines())
        assertTrue(tmp.root.resolve("history.log").readText().contains("undo hide p"))
    }
}
