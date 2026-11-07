package com.xplsh.onnshell.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibIndexTest {
    private val sha = "a".repeat(64)

    @Test fun parseKeepsGoodItemsAndDropsBadOnes() {
        val json = """{"items":[
            {"id":"org.example.game","title":"Example","type":"apk","version":3,"size":10,"sha256":"$sha","file":"/files/org.example.game","name":"g.apk","cover":"/covers/org.example.game"},
            {"id":"pack","type":"data","version":1,"size":5,"sha256":"$sha","file":"/files/pack","target":"Android/data/{requires}/files","requires":"org.example.game"},
            {"id":"bad id; rm","type":"apk","version":1,"size":1,"sha256":"$sha","file":"/files/x"},
            {"id":"noTarget","type":"data","version":1,"size":1,"sha256":"$sha","file":"/files/noTarget"},
            {"id":"escape","type":"data","version":1,"size":1,"sha256":"$sha","file":"/files/escape","target":"../../system"},
            {"id":"short","type":"apk","version":1,"size":1,"sha256":"abc","file":"/files/short"}
        ]}"""
        val items = LibIndex.parse(json)
        assertEquals(listOf("org.example.game", "pack"), items.map { it.id })
        assertEquals("/covers/org.example.game", items[0].cover)
        assertTrue(items[0].game)
        assertEquals(emptyList<LibItem>(), LibIndex.parse("not json"))
    }

    @Test fun targetsAreRelativeAndPlain() {
        assertEquals("Android/data/org.x/files", LibIndex.resolveTarget("Android/data/{requires}/files", "org.x"))
        assertEquals("Games/Foo", LibIndex.resolveTarget("Games/Foo/", null))
        assertNull(LibIndex.resolveTarget("/Games/Foo", null)) // absolute paths are refused
        assertNull(LibIndex.resolveTarget("Android/data/{requires}", null))
        assertNull(LibIndex.resolveTarget("a/../b", null))
        assertNull(LibIndex.resolveTarget("x'; reboot", null))
        assertFalse(LibIndex.validTarget("/sdcard/abs"))
    }

    @Test fun zipEntriesCannotEscape() {
        assertTrue(LibIndex.safeEntry("data/level1.bin"))
        assertFalse(LibIndex.safeEntry("../evil"))
        assertFalse(LibIndex.safeEntry("a/../../evil"))
        assertFalse(LibIndex.safeEntry("/abs"))
        assertFalse(LibIndex.safeEntry("a\\b"))
        assertFalse(LibIndex.safeEntry("it's"))
    }

    @Test fun serverMustBeOnTheLan() {
        assertTrue(LibIndex.lanUrl("http://192.168.1.10:8765"))
        assertTrue(LibIndex.lanUrl("http://172.16.0.2:8765"))
        assertTrue(LibIndex.lanUrl("http://127.0.0.1:8765"))
        assertTrue(LibIndex.lanUrl("http://truenas.local:8765"))
        assertTrue(LibIndex.lanUrl("http://nas:8765"))
        assertFalse(LibIndex.lanUrl("http://8.8.8.8:8765"))
        assertFalse(LibIndex.lanUrl("http://example.com:8765"))
        assertFalse(LibIndex.lanUrl("ftp://192.168.1.10"))
        assertFalse(LibIndex.lanUrl("garbage"))
    }

    @Test fun evictsLeastRecentlyPlayedFirst() {
        val a = Pack("a", 1, "/r", listOf("f"), 10, installedAt = 100, requires = "app.a")
        val b = Pack("b", 1, "/r", listOf("f"), 10, installedAt = 200, requires = "app.b")
        val c = Pack("c", 1, "/r", listOf("f"), 10, installedAt = 50, requires = null)
        val order = LibIndex.evictOrder(listOf(a, b, c), mapOf("app.a" to 900L, "app.b" to 300L))
        assertEquals(listOf("c", "b", "a"), order.map { it.id })
    }

    @Test fun packsRoundTrip() {
        val p = listOf(Pack("a", 2, "/storage/emulated/0/G", listOf("x/1", "y"), 99, 5, null), Pack("b", 1, "/r", emptyList(), 0, 6, "app.b"))
        assertEquals(p, LibIndex.parsePacks(LibIndex.formatPacks(p)))
    }
}
