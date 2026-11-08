package com.xplsh.onnshell.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreQueueTest {
    private fun e(id: String) = Entry(id, id, "recomp", "u/" + id, 1, null, "sdl2", false, null, null, null, description = "",
        needsFiles = false, rating = Rating.MEDIUM, estimated = true, reports = 0)

    @Test fun getsWaitTheirTurnAndAFailureDoesNotStopTheLine() {
        val gates = mapOf("a" to CompletableDeferred<Result<Unit>>(), "b" to CompletableDeferred(), "c" to CompletableDeferred(), "d" to CompletableDeferred())
        val started = mutableListOf<String>()
        val s = StoreState(CoroutineScope(Dispatchers.Unconfined), loader = { listOf(e("a"), e("b"), e("c"), e("d")) }, isInstalled = { false },
            installer = { entry, _ -> started += entry.id; gates.getValue(entry.id).await() })
        s.refresh()
        assertTrue(s.install(e("a"))); assertTrue(s.install(e("b"))); assertTrue(s.install(e("c"))); assertTrue(s.install(e("d")))
        s.install(e("b")) // asking twice does not queue it twice
        assertEquals("a", s.busy); assertEquals(listOf("b", "c", "d"), s.queue.toList()); assertEquals(listOf("a"), started)
        s.dequeue("c")
        gates.getValue("a").complete(Result.success(Unit))
        assertEquals("b", s.busy); assertEquals(true, s.installed["a"]); assertEquals(listOf("d"), s.queue.toList())
        gates.getValue("b").complete(Result.failure(RuntimeException("no space")))
        assertEquals("d", s.busy); assertEquals("no space", s.error); assertEquals(listOf("a", "b", "d"), started)
        gates.getValue("d").complete(Result.success(Unit))
        assertNull(s.busy); assertTrue(s.queue.isEmpty()); assertEquals(2, s.installs)
    }
}
