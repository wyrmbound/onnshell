package com.xplsh.onnshell

import com.xplsh.onnshell.data.Catalog
import com.xplsh.onnshell.data.Rating
import com.xplsh.onnshell.data.Survey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    private fun port(id: String, type: String, url: String, great: Int = 0, broken: Int = 0, abi: String = "arm64-v8a", desc: String = "", pkg: String = "") = """
        {"id":"$id","name":"${id.uppercase()}","type":"$type","forks":[{"repo":"x/$id","package":"$pkg",
          "release":{"tag":"1","asset":{"name":"a.apk","url":"$url","size":1234,"sha256":"ab"}},
          "reports":[{"device":"d","great":$great,"playable":0,"broken":$broken}]}],
         "requirements":{"abi":["$abi"]},"art":{"cover":"https://img/$id.png"},"description":"$desc"}
    """
    private val fixture = """{"schema_version":1,"ports":[
        ${port("a-port", "source-port", "https://x/a.apk", great = 3)},
        ${port("b-port", "recomp", "https://x/b.apk", abi = "armeabi-v7a", pkg = "com.b", desc = "Requires your PC game files.")},
        ${port("c-port", "source-port", "https://x/c.apk", broken = 1)},
        ${port("d-port", "emulator", "https://x/d.apk")}
    ]}"""
    private val survey = mapOf(
        "https://x/a.apk" to Survey("sdl2", false), "https://x/b.apk" to Survey("unity", true),
        "https://x/c.apk" to Survey("sdl3", false), "https://x/d.apk" to Survey("sdl2", false),
    )

    @Test fun parseReadsTheReleaseAsset() {
        val e = Catalog.parse(fixture, survey)
        assertEquals(4, e.size)
        assertEquals("https://x/a.apk", e[0].url); assertEquals("sdl2", e[0].engine); assertEquals(1234L, e[0].size); assertEquals("ab", e[0].sha256)
        assertEquals("https://img/a-port.png", e[0].cover); assertNull(e[0].pkg); assertEquals("com.b", e[1].pkg)
    }

    @Test fun listableKeepsOnlyRunnableAllowedTypes() {
        val l = Catalog.listable(Catalog.parse(fixture, survey))
        assertEquals(listOf("a-port", "b-port", "c-port"), l.map { it.id }) // c is sdl3 (our runner), d is an emulator
    }

    @Test fun ratingTiers() {
        assertEquals(Rating.EXCELLENT to true, Catalog.rate(3, 1, 0, "sdl2", false))
        assertEquals(Rating.PLAYABLE to true, Catalog.rate(1, 1, 1, "sdl2", false))
        assertEquals(Rating.NOT_RECOMMENDED to true, Catalog.rate(0, 0, 2, "sdl2", false))
        assertEquals(Rating.MEDIUM to true, Catalog.rate(0, 0, 0, "sdl2", false))
        assertEquals(Rating.PLAYABLE to true, Catalog.rate(0, 0, 0, "unity", true))
    }

    @Test fun ratingsComeFromReports() {
        val e = Catalog.parse(fixture, survey).associateBy { it.id }
        assertEquals(Rating.EXCELLENT, e["a-port"]!!.rating); assertEquals(3, e["a-port"]!!.reports)
        assertEquals(Rating.NOT_RECOMMENDED, e["c-port"]!!.rating)
        assertTrue(e["b-port"]!!.needsFiles); assertFalse(e["a-port"]!!.needsFiles)
    }

    @Test fun heavyConsoleRecompsAreCappedAtMedium() {
        val n64 = port("z-port", "recomp", "https://x/z.apk", great = 5).replace("\"type\":\"recomp\"", "\"type\":\"recomp\",\"original_platform\":\"n64\"")
        val pc = port("y-port", "recomp", "https://x/y.apk", great = 5).replace("\"type\":\"recomp\"", "\"type\":\"recomp\",\"original_platform\":\"pc\"")
        val e = Catalog.parse("""{"ports":[$n64,$pc]}""", mapOf("https://x/z.apk" to Survey("sdl2", false), "https://x/y.apk" to Survey("sdl2", false))).associateBy { it.id }
        assertEquals(Rating.MEDIUM, e["z-port"]!!.rating)
        assertEquals(Rating.EXCELLENT, e["y-port"]!!.rating)
    }

    @Test fun coversFallThroughCoverIgdbHero() {
        val e = Catalog.parse(fixture.replace("\"art\":{\"cover\":\"https://img/a-port.png\"}", "\"art\":{\"cover\":\"https://img/a.png\",\"igdb_cover\":\"https://img/a2.jpg\",\"hero\":\"https://img/a3.png\"}"), survey)
        assertEquals(listOf("https://img/a.png", "https://img/a2.jpg", "https://img/a3.png"), e[0].covers)
    }

    @Test fun idIsLowercased() {
        assertEquals("a-port", Catalog.parse(fixture.replace("\"a-port\"", "\"A-Port\""), survey)[0].id)
    }

    @Test fun surveyParsesUrlEngineAndAbi() {
        val s = Catalog.parseSurvey("""[{"name":"n","url":"https://x/a.apk","engine":"sdl2","abis":["arm64-v8a","armeabi-v7a"],"has_v7a":true}]""")
        assertEquals(Survey("sdl2", true), s["https://x/a.apk"])
    }

    @Test fun dotnetSdlAppIsNotRunnable() {
        val s = Catalog.parseSurvey("""[{"url":"https://x/c.apk","engine":"sdl3","abis":["arm64-v8a"],"libs":["libSDL3.so","libmonodroid.so"]}]""")
        assertEquals(Survey("dotnet", false), s["https://x/c.apk"])
    }

    @Test fun abiFallsBackToRequirementsWhenNotSurveyed() {
        val e = Catalog.parse(fixture, emptyMap()).associateBy { it.id }
        assertTrue(e["b-port"]!!.has32); assertFalse(e["a-port"]!!.has32); assertEquals("unknown", e["a-port"]!!.engine)
    }

    @Test fun requirementsComeFromTheCuratedFileThenRecompa() {
        val curated = Catalog.parseRequirements("""{"_about":"x","A-Port":["Xbox USA .iso"]}""")
        assertEquals(mapOf("a-port" to listOf("Xbox USA .iso")), curated)
        val withAssets = port("r-port", "decomp", "https://x/r.apk").replace("\"description\"",
            "\"assets\":[{\"label\":\"ROM (N64)\",\"accepted\":[{\"sha1\":\"d6\",\"label\":\"USA 1.0\",\"format\":\"z64\"}],\"delivery\":{\"filename\":\"rom.z64\"}}],\"description\"")
        val e = Catalog.parse("""{"ports":[${port("a-port", "source-port", "https://x/a.apk")},$withAssets]}""", survey, curated).associateBy { it.id }
        assertEquals(listOf("Xbox USA .iso"), e["a-port"]!!.requires); assertTrue(e["a-port"]!!.needsFiles)
        assertEquals(listOf("ROM (N64): USA 1.0 .z64 (file rom.z64)"), e["r-port"]!!.requires)
    }

    @Test fun structuredRequirementsBecomeLabelledLines() {
        val m = Catalog.parseRequirements("""{"x":{"game":"G","version":"USA","file":"one .iso","not":"PC"}}""")
        assertEquals(listOf("Game: G", "Version: USA", "File: one .iso", "Does not work: PC"), m["x"])
    }

    @Test fun filesWithoutDetailsSayWhatIsUnknown() {
        val p = port("u-port", "recomp", "https://x/u.apk", desc = "Requires your copy.").replace("\"type\":\"recomp\"", "\"type\":\"recomp\",\"original_platform\":\"xbox360\"")
        val e = Catalog.parse("""{"ports":[$p]}""", survey).single()
        assertTrue(e.requires.single().startsWith("Your own Xbox 360 copy"))
    }

    @Test fun shippedRequirementsFileParses() {
        val f = java.io.File("src/main/assets/requirements.json")
        val m = Catalog.parseRequirements(f.readText())
        assertTrue("mostwanted" in m); assertTrue(m.values.all { it.isNotEmpty() })
    }
}
