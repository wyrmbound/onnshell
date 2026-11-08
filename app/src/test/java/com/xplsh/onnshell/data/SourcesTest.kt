package com.xplsh.onnshell.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesTest {
    private val latest = """{"tag_name":"v2.0","published_at":"2026-11-09T10:00:00Z","assets":[
        {"name":"notes.txt","browser_download_url":"https://github.com/a/b/releases/download/v2.0/notes.txt","size":10},
        {"name":"Game-arm64.apk","browser_download_url":"https://github.com/a/b/releases/download/v2.0/Game-arm64.apk","size":5000000,
         "digest":"sha256:${"ab".repeat(32)}"},
        {"name":"Game-x86.apk","browser_download_url":"https://github.com/a/b/releases/download/v2.0/Game-x86.apk","size":6000000}]}"""

    @Test fun latestReleasePicksTheListingsApk() {
        val r = Direct.parseLatest(latest, "(?i)arm64.*[.]apk$", now = 500)!!
        assertEquals("https://github.com/a/b/releases/download/v2.0/Game-arm64.apk", r.url)
        assertEquals(5000000L, r.size); assertEquals("v2.0", r.tag); assertEquals("ab".repeat(32), r.sha256); assertEquals(500L, r.checked)
        assertEquals(isoMs("2026-11-09T10:00:00Z"), r.published)
        assertEquals("Game-arm64.apk", Direct.parseLatest(latest, null)!!.url.substringAfterLast('/')) // no pattern: the first .apk
        assertEquals("Game-arm64.apk", Direct.parseLatest(latest, "(((")!!.url.substringAfterLast('/')) // a broken pattern falls back
        assertNull(Direct.parseLatest(latest, "[.]ipa$"))
        assertNull(Direct.parseLatest("""{"tag_name":"v1","assets":[{"name":"g.apk","browser_download_url":"https://evil.example/g.apk"}]}""", null))
        assertNull(Direct.parseLatest("""{"message":"Not Found"}""", null))
    }

    @Test fun directAnswerWinsOnlyWhenNewerThanTheList() {
        val e = Entry("g", "G", "recomp", "https://github.com/a/b/releases/download/v1.0/Game-arm64.apk", 1, null, "sdl2", false, null, null, null,
            description = "", needsFiles = false, rating = Rating.MEDIUM, estimated = true, reports = 0, tag = "v1.0")
        val r = Direct.parseLatest(latest, null, now = 2000)!!
        assertTrue(Direct.newer(e, r, listSavedAt = 1000))   // asked after the list was last refreshed
        assertFalse(Direct.newer(e, r, listSavedAt = 3000))  // the list is fresher than the answer
        assertFalse(Direct.newer(e.copy(tag = "v2.0"), r, 1000))
        assertFalse(Direct.newer(e, null, 1000))
    }

    @Test fun onlyARealListReplacesTheSavedCopy() {
        assertTrue(Catalog.usable("""{"ports":[{"id":"a"}]}"""))
        assertFalse(Catalog.usable("""{"ports":[]}"""))          // an emptied index (taken down)
        assertFalse(Catalog.usable("<html>404</html>"))
        assertFalse(Catalog.usable("""{"message":"gone"}"""))
    }

    @Test fun newAndUpdatedAreAWeek() {
        val day = 24 * 3600 * 1000L; val now = 100 * day
        val e = Entry("g", "G", "recomp", "u", 1, null, "sdl2", false, null, null, null, description = "", needsFiles = false,
            rating = Rating.MEDIUM, estimated = true, reports = 0, addedAt = now - 3 * day, updatedAt = now - 1 * day)
        assertTrue(e.isNew(now)); assertFalse(e.isUpdated(now)) // new wins over updated
        assertTrue(e.copy(addedAt = now - 30 * day).isUpdated(now))
        assertFalse(e.copy(addedAt = 0, updatedAt = 0).isNew(now))
        assertEquals(0L, isoMs("")); assertEquals(0L, isoMs("yesterday"))
    }

    @Test fun dotnetIsNotOurSdl() {
        val p = GameInstaller.classify(listOf("lib/arm64-v8a/libSDL2.so", "lib/arm64-v8a/libmonodroid.so"))
        assertEquals(GameInstaller.Engine.DOTNET, p.engine); assertFalse(p.runnable)
        assertEquals("dotnet", Probes.engineName(p.engine))
    }
}
