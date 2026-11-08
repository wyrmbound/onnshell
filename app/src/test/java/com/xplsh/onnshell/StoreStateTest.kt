package com.xplsh.onnshell

import com.xplsh.onnshell.data.Entry
import com.xplsh.onnshell.data.Filter
import com.xplsh.onnshell.data.Rating
import com.xplsh.onnshell.data.StoreState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreStateTest {
    private fun entry(id: String, rating: Rating = Rating.PLAYABLE) = Entry(
        id = id, name = id, type = "source-port", url = "https://x/$id.apk", size = 1, sha256 = null, engine = "sdl2", has32 = false,
        pkg = null, cover = null, hero = null, description = "", needsFiles = false, rating = rating, estimated = true, reports = 0,
    )
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test fun installIsIgnoredWhileBusy() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val s = StoreState(scope, { emptyList() }, { false }, { _, _ -> gate.await(); Result.success(Unit) })
        assertTrue(s.install(entry("a")))
        assertFalse(s.install(entry("b")))
        assertEquals("a", s.busy)
        gate.complete(Unit)
        assertNull(s.busy); assertEquals(true, s.installed["a"]); assertEquals(1, s.installs)
    }

    @Test fun visibleSortsInstalledThenRating() {
        val s = StoreState(scope, { listOf(entry("z", Rating.MEDIUM), entry("m", Rating.EXCELLENT), entry("b", Rating.NOT_RECOMMENDED)) }, { it == "b" }, { _, _ -> Result.success(Unit) })
        s.refresh()
        assertEquals(listOf("b", "m", "z"), s.visible.map { it.id })
    }

    @Test fun filtersWork() {
        val s = StoreState(scope, { listOf(entry("z", Rating.MEDIUM), entry("m", Rating.EXCELLENT), entry("b", Rating.PLAYABLE)) }, { it == "b" }, { _, _ -> Result.success(Unit) })
        s.refresh()
        s.filter = Filter.INSTALLED; assertEquals(listOf("b"), s.visible.map { it.id })
        s.filter = Filter.EXCELLENT; assertEquals(listOf("m"), s.visible.map { it.id })
        s.filter = Filter.PLAYABLE; assertEquals(listOf("b", "m"), s.visible.map { it.id })
    }

    @Test fun refreshFailureKeepsEntriesAndReportsError() {
        var fail = false
        val s = StoreState(scope, { if (fail) throw java.io.IOException("Store needs the network") else listOf(entry("a")) }, { false }, { _, _ -> Result.success(Unit) })
        s.refresh(); assertEquals(1, s.entries.size)
        fail = true; s.refresh()
        assertEquals(1, s.entries.size); assertEquals("Store needs the network", s.error)
    }

    @Test fun failedInstallClearsBusy() = runBlocking {
        val s = StoreState(scope, { emptyList() }, { false }, { _, _ -> Result.failure(IllegalStateException("engine x does not run here yet")) })
        s.install(entry("a"))
        assertNull(s.busy); assertEquals("engine x does not run here yet", s.error); assertEquals(0, s.installs)
    }

    @Test fun searchMatchesNameOrIdLoosely() {
        val e = entry("nfsmw-android").copy(name = "NFS Most Wanted Android")
        assertTrue(StoreState.matches(e, "most wanted")); assertTrue(StoreState.matches(e, "MOSTWANTED"))
        assertTrue(StoreState.matches(e, "nfsmw")); assertTrue(StoreState.matches(e, "")); assertFalse(StoreState.matches(e, "tyrian"))
        val s = StoreState(scope, { listOf(e, entry("opentyrian2000")) }, { false }, { _, _ -> Result.success(Unit) })
        s.refresh(); s.query = "tyr"
        assertEquals(listOf("opentyrian2000"), s.visible.map { it.id })
    }
}
