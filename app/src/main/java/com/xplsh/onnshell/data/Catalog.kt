package com.xplsh.onnshell.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

enum class Rating { EXCELLENT, PLAYABLE, MEDIUM, NOT_RECOMMENDED }

/** One Recompa port, reduced to what the Store shows and the installer needs. */
data class Entry(
    val id: String,
    val name: String,
    val type: String,
    val url: String,
    val size: Long,
    val sha256: String?,
    val engine: String,
    val has32: Boolean,
    /** Package name of the 32-bit build, for launching it after install. */
    val pkg: String?,
    val cover: String?,
    val hero: String?,
    /** Art to try in order (cover, IGDB cover, hero); a tile falls through on a failed load. */
    val covers: List<String> = listOfNotNull(cover),
    val description: String,
    /** The owner must supply game files (ROM, WAD, PC data). */
    val needsFiles: Boolean,
    val rating: Rating,
    /** No report from this box yet; the rating comes from other people's devices. */
    val estimated: Boolean,
    val reports: Int,
    /** Game files the port asks for: accepted sha1s and exact file names (lowercase), for OnnShell's file picker. */
    val wants: Set<String> = emptySet(),
) {
    val runnable get() = has32 || engine == "sdl2" || engine == "sdl3"
}

/** What the engine survey (onn-pathb/apkrun/survey.json, shipped as assets/survey.json) knows about one APK, keyed by its URL. */
data class Survey(val engine: String, val has32: Boolean)

/**
 * The Recompa index: fetched daily, cached on disk, filtered to what runs here and rated.
 * Rule (CONSOLE_MODE section 10): listed only if the type is decomp/recomp/source-port and the build
 * either has a 32-bit lib (installs natively) or is an SDL2/SDL3 arm64 port (our runner).
 */
object Catalog {
    const val INDEX_URL = "https://raw.githubusercontent.com/kalasyan/recompa/gh-pages/index.json"
    private val TYPES = setOf("decomp", "recomp", "source-port")
    private val HEAVY_3D = setOf("n64", "ps1", "ps2", "ps3", "ps4", "psp", "gamecube", "wii", "wiiu", "xbox", "xbox360", "switch", "switch2", "3ds", "ds")
    private const val TTL_MS = 24 * 3600 * 1000L

    fun parse(json: String, survey: Map<String, Survey>): List<Entry> {
        val ports = JSONObject(json).optJSONArray("ports") ?: JSONArray()
        return (0 until ports.length()).mapNotNull { i -> entry(ports.getJSONObject(i), survey) }
    }

    private fun entry(p: JSONObject, survey: Map<String, Survey>): Entry? {
        val forks = p.optJSONArray("forks") ?: return null
        // The first fork with a release asset is the one Recompa shows first too
        var fork: JSONObject? = null; var asset: JSONObject? = null
        for (i in 0 until forks.length()) {
            val f = forks.getJSONObject(i)
            val a = f.optJSONObject("release")?.optJSONObject("asset")
            if (a != null && a.optString("url").isNotEmpty()) { fork = f; asset = a; break }
        }
        if (fork == null || asset == null) return null
        val url = asset.getString("url")
        val s = survey[url]
        var great = 0; var playable = 0; var broken = 0
        for (i in 0 until forks.length()) {
            val r = forks.getJSONObject(i).optJSONArray("reports") ?: continue
            for (j in 0 until r.length()) { val o = r.getJSONObject(j); great += o.optInt("great"); playable += o.optInt("playable"); broken += o.optInt("broken") }
        }
        val abi = p.optJSONObject("requirements")?.optJSONArray("abi")
        val has32 = s?.has32 ?: ((0 until (abi?.length() ?: 0)).any { abi!!.getString(it) == "armeabi-v7a" })
        val engine = s?.engine ?: "unknown"
        val art = p.optJSONObject("art")
        val desc = p.optString("description", "")
        var (rating, est) = rate(great, playable, broken, engine, has32)
        val platform = p.optString("original_platform")
        // Recomps of 3D consoles need a heavy renderer (RT64 and the like) that nobody has proven on this box:
        // cap their estimate at Medium so the warning shows before Get.
        if (p.optString("type") == "recomp" && platform in HEAVY_3D && rating.ordinal < Rating.MEDIUM.ordinal) rating = Rating.MEDIUM
        val cover = art?.optString("cover")?.ifEmpty { null }
        val covers = listOfNotNull(cover, art?.optString("igdb_cover")?.ifEmpty { null }, art?.optString("hero")?.ifEmpty { null }).distinct()
        return Entry(
            id = p.getString("id").lowercase(), name = p.optString("name", p.getString("id")), type = p.optString("type"),
            url = url, size = asset.optLong("size"), sha256 = asset.optString("sha256").ifEmpty { null },
            engine = engine, has32 = has32, pkg = fork.optString("package").ifEmpty { null },
            cover = cover, hero = art?.optString("hero")?.ifEmpty { null }, covers = covers,
            description = desc,
            needsFiles = p.has("assets") || Regex("requires your|your own|game files", RegexOption.IGNORE_CASE).containsMatchIn(desc),
            rating = rating, estimated = est, reports = great + playable + broken,
            wants = wants(p.optJSONArray("assets")),
        )
    }

    fun wants(assets: JSONArray?): Set<String> {
        val out = mutableSetOf<String>()
        for (i in 0 until (assets?.length() ?: 0)) {
            val a = assets!!.getJSONObject(i)
            a.optJSONObject("delivery")?.optString("filename")?.ifEmpty { null }?.let { out += it.lowercase() }
            val acc = a.optJSONArray("accepted") ?: continue
            for (j in 0 until acc.length()) acc.getJSONObject(j).optString("sha1").ifEmpty { null }?.let { out += it.lowercase() }
        }
        return out
    }

    fun listable(entries: List<Entry>) = entries.filter { it.type in TYPES && it.runnable }

    /** Sum of every fork's reports -> tier. No reports: a 64-bit SDL2 port is Medium, a 32-bit build Playable, both estimated. */
    fun rate(great: Int, playable: Int, broken: Int, engine: String, has32: Boolean): Pair<Rating, Boolean> {
        val total = great + playable + broken
        if (total == 0) return (if (has32) Rating.PLAYABLE else Rating.MEDIUM) to true
        val r = (great + 0.5 * playable) / total
        val tier = when { r >= 0.75 -> Rating.EXCELLENT; r >= 0.5 -> Rating.PLAYABLE; r >= 0.25 -> Rating.MEDIUM; else -> Rating.NOT_RECOMMENDED }
        return tier to true // shortcut: every rating is an estimate until local run reports exist (Prefs hook to come)
    }

    fun parseSurvey(json: String): Map<String, Survey> {
        val a = JSONArray(json)
        return (0 until a.length()).associate { i ->
            val o = a.getJSONObject(i)
            val abis = o.optJSONArray("abis")
            val has32 = o.optBoolean("has_v7a") || (0 until (abis?.length() ?: 0)).any { abis!!.getString(it) == "armeabi-v7a" }
            // .NET for Android (Celeste Android and the like) bundles libSDL but starts from Java, which our 64-bit runner cannot run
            val libs = o.optJSONArray("libs")
            val dotnet = (0 until (libs?.length() ?: 0)).any { libs!!.getString(it) in DOTNET_LIBS }
            o.optString("url") to Survey(if (dotnet) "dotnet" else o.optString("engine", "unknown"), has32)
        }
    }
    private val DOTNET_LIBS = setOf("libmonodroid.so", "libmonosgen-2.0.so", "libxamarin-app.so")

    private fun cacheFile(ctx: Context) = File(ctx.cacheDir, "recompa-index.json")

    /** The listable entries, from the network when the cache is older than a day (or [force]), else from the cache. */
    suspend fun load(ctx: Context, force: Boolean = false, fetch: (String) -> String = ::http): List<Entry> = withContext(Dispatchers.IO) {
        val survey = runCatching { ctx.assets.open("survey.json").bufferedReader().use { parseSurvey(it.readText()) } }.getOrDefault(emptyMap())
        val cache = cacheFile(ctx)
        val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < TTL_MS
        val json = if (fresh && !force) cache.readText() else runCatching { fetch(INDEX_URL).also { cache.writeText(it) } }
            .getOrElse { e -> if (cache.exists()) cache.readText() else throw IOException("Store needs the network", e) }
        listable(parse(json, survey))
    }

    fun http(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 15_000
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}
