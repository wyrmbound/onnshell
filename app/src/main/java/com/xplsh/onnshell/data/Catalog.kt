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
    /** The exact game version the port needs, one line each: curated (assets/requirements.json) first, then Recompa's assets[]. */
    val requires: List<String> = emptyList(),
    /** The game's own GitHub project and the pattern of its APK there: the Store's way to it without any list ([Direct]). */
    val repo: String? = null,
    val pattern: String? = null,
    /** The release this download is (its tag), when the game joined the list and when this release came out (epoch ms, 0 = unknown). */
    val tag: String = "",
    val addedAt: Long = 0,
    val updatedAt: Long = 0,
    /** Which list this came from: "recompa", an extra list's id, or "github" when the link was refreshed from the project itself. */
    val source: String = "recompa",
) {
    val runnable get() = has32 || engine == "sdl2" || engine == "sdl3"
    fun isNew(now: Long = System.currentTimeMillis()) = addedAt > 0 && now - addedAt < WEEK_MS
    fun isUpdated(now: Long = System.currentTimeMillis()) = !isNew(now) && updatedAt > 0 && now - updatedAt < WEEK_MS
    private companion object { const val WEEK_MS = 7 * 24 * 3600 * 1000L }
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
    // An hour: new ports and fresh reports show up the same evening (owner ask, 2026-11-09); Retry forces it sooner
    private const val TTL_MS = 3600 * 1000L

    fun parse(json: String, survey: Map<String, Survey>, curated: Map<String, List<String>> = emptyMap(), source: String = "recompa"): List<Entry> {
        val ports = JSONObject(json).optJSONArray("ports") ?: JSONArray()
        // one odd listing must not take the whole list down with it
        return (0 until ports.length()).mapNotNull { i -> runCatching { entry(ports.getJSONObject(i), survey, curated)?.copy(source = source) }.getOrNull() }
    }

    /** A fetched list may replace the saved copy only if it really is a list with games in it (not an error page, not an emptied index). */
    fun usable(json: String) = runCatching { JSONObject(json).getJSONArray("ports").length() > 0 }.getOrDefault(false)

    /** assets/requirements.json: port id -> the lines shown in the Store (see [Requirements]). */
    fun parseRequirements(json: String): Map<String, List<String>> = Requirements.parse(json).mapValues { it.value.lines }

    private val PLATFORMS = mapOf("pc" to "PC", "dos" to "DOS", "xbox" to "original Xbox", "xbox360" to "Xbox 360", "ps1" to "PlayStation",
        "ps2" to "PS2", "ps3" to "PS3", "ps4" to "PS4", "psp" to "PSP", "n64" to "N64", "gamecube" to "GameCube", "wii" to "Wii",
        "wiiu" to "Wii U", "switch" to "Switch", "3ds" to "3DS", "ds" to "DS", "gb" to "Game Boy", "gbc" to "Game Boy Color",
        "gba" to "Game Boy Advance", "snes" to "SNES", "nes" to "NES", "genesis" to "Mega Drive")

    /** For a port that needs files but says nothing exact: name the platform and say plainly what is unknown. */
    fun unknownVersion(platform: String): String =
        "Your own ${PLATFORMS[platform.lowercase()] ?: "original"} copy of the game. This listing does not say which version or region works; check the port's page first."


    /** One line per Recompa asset: its label, then the accepted versions with their formats, then the exact file name. */
    fun requiredLines(assets: JSONArray?): List<String> = (0 until (assets?.length() ?: 0)).mapNotNull { i ->
        val a = assets!!.getJSONObject(i)
        val acc = a.optJSONArray("accepted")
        val versions = (0 until (acc?.length() ?: 0)).map { j ->
            val v = acc!!.getJSONObject(j)
            listOf(v.optString("label"), v.optString("format").ifEmpty { null }?.let { ".$it" }).filter { !it.isNullOrEmpty() }.joinToString(" ")
        }.filter { it.isNotEmpty() }
        val file = a.optJSONObject("delivery")?.optString("filename")?.ifEmpty { null }
        val label = a.optString("label").ifEmpty { return@mapNotNull null }
        label + (if (versions.isNotEmpty()) ": " + versions.joinToString(", ") else "") + (file?.let { " (file $it)" } ?: "")
    }

    private fun entry(p: JSONObject, survey: Map<String, Survey>, curated: Map<String, List<String>>): Entry? {
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
            needsFiles = p.has("assets") || p.getString("id").lowercase() in curated || Regex("requires your|your own|game files", RegexOption.IGNORE_CASE).containsMatchIn(desc),
            rating = rating, estimated = est, reports = great + playable + broken,
            wants = wants(p.optJSONArray("assets")),
            requires = ((curated[p.getString("id").lowercase()] ?: emptyList()) + requiredLines(p.optJSONArray("assets"))).ifEmpty {
                if (p.has("assets") || Regex("requires your|your own|game files", RegexOption.IGNORE_CASE).containsMatchIn(desc)) listOf(unknownVersion(platform)) else emptyList()
            },
            repo = fork.optString("repo").ifEmpty { null }, pattern = fork.optString("asset_pattern").ifEmpty { null },
            tag = fork.optJSONObject("release")?.optString("tag").orEmpty(),
            addedAt = isoMs(p.optString("added_at")), updatedAt = isoMs(fork.optJSONObject("release")?.optString("published_at")),
        )
    }

    fun wants(assets: JSONArray?): Set<String> {
        val out = mutableSetOf<String>()
        for (i in 0 until (assets?.length() ?: 0)) {
            val a = assets!!.getJSONObject(i)
            a.optJSONObject("delivery")?.optString("filename")?.ifEmpty { null }?.let { out += it.lowercase() }
            val acc = a.optJSONArray("accepted") ?: continue
            for (j in 0 until acc.length()) acc.getJSONObject(j).let { v ->
                v.optString("sha1").ifEmpty { null }?.let { out += it.lowercase() }
                // "*.z64": the kind of file, never a real name. It tells the Store a file of that kind is on the box
                // (so a wrong version can be told from nothing at all) and which extensions are worth fingerprinting.
                v.optString("format").ifEmpty { null }?.let { out += "*." + it.lowercase() }
            }
            a.optJSONArray("convert_from")?.let { c -> for (j in 0 until c.length()) out += "*." + c.getString(j).lowercase() }
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

    /** Where the Store's lists come from: Recompa, then any extra lists in the same format (Prefs.storeSources). The first listing of an id wins. */
    fun sources(ctx: Context): List<Pair<String, String>> =
        listOf("recompa" to INDEX_URL) + Prefs.storeSources(ctx).sorted().map { "x" + Integer.toHexString(it.hashCode()) to it }

    /** The last good copy of a list. In filesDir, not the cache: it has to outlive a low-storage cleanup and the list's home going away. */
    private fun saved(ctx: Context, id: String) = File(ctx.filesDir, "store-$id.json")
    /** When the Recompa list was last fetched successfully (0 = never), and for how many whole days it has been out of reach. */
    fun savedAt(ctx: Context) = saved(ctx, "recompa").lastModified()
    fun staleDays(ctx: Context) = savedAt(ctx).let { if (it == 0L) 0 else ((System.currentTimeMillis() - it) / (24 * 3600 * 1000L)).toInt() }

    private fun text(ctx: Context, id: String, url: String, force: Boolean, fetch: (String) -> String): String? {
        val f = saved(ctx, id)
        if (id == "recompa" && !f.exists()) File(ctx.cacheDir, "recompa-index.json").takeIf { it.exists() }?.copyTo(f) // where older builds kept it
        if (f.exists() && !force && System.currentTimeMillis() - f.lastModified() < TTL_MS) return f.readText()
        return runCatching {
            fetch(url).also { t ->
                require(usable(t)) { "not a game list" }
                val tmp = File(f.path + ".tmp"); tmp.writeText(t); check(tmp.renameTo(f))
            }
        }.getOrElse { if (f.exists()) f.readText() else null }
    }

    /** Everything the lists hold, before the "runs here" filter: what [follow] looks through. */
    @Volatile private var lastAll: List<Entry> = emptyList()

    /**
     * The listable entries. Each list comes from the network when its saved copy is older than an hour (or [force]),
     * else from that copy; a list that cannot be reached keeps serving its last good copy for as long as it takes.
     */
    suspend fun load(ctx: Context, force: Boolean = false, fetch: (String) -> String = ::http): List<Entry> = withContext(Dispatchers.IO) {
        val survey = runCatching { ctx.assets.open("survey.json").bufferedReader().use { parseSurvey(it.readText()) } }.getOrDefault(emptyMap()) + Probes.all(ctx)
        val curated = runCatching { ctx.assets.open("requirements.json").bufferedReader().use { parseRequirements(it.readText()) } }.getOrDefault(emptyMap())
        val lists = sources(ctx).mapNotNull { (id, url) -> text(ctx, id, url, force, fetch)?.let { id to it } }
        if (lists.isEmpty()) throw IOException("Store needs the network")
        val seen = HashSet<String>()
        val all = lists.flatMap { (id, json) -> runCatching { parse(json, survey, curated, id) }.getOrDefault(emptyList()) }.filter { seen.add(it.id) }
        lastAll = Direct.apply(ctx, all, savedAt(ctx))
        listable(lastAll)
    }

    /**
     * The Store's upkeep after a load, off the screen's way: judge the downloads nothing is known about yet ([Probes]),
     * and when the Recompa list has been out of reach for a day, ask each game's GitHub project directly ([Direct]),
     * installed games first. True when the list should be loaded again.
     */
    suspend fun follow(ctx: Context, installed: Set<String> = emptySet()): Boolean {
        var changed = Probes.learn(ctx, lastAll) > 0
        if (staleDays(ctx) >= 1) changed = Direct.sweep(ctx, lastAll, installed) > 0 || changed
        return changed
    }

    /** For an install while the list is out of reach: the game's newest release straight from its project, else the entry as listed. */
    suspend fun latest(ctx: Context, e: Entry): Entry {
        if (staleDays(ctx) < 1) return e
        val r = runCatching { Direct.check(ctx, e) }.getOrNull()
        return if (Direct.newer(e, r, savedAt(ctx))) e.copy(url = r!!.url, size = r.size, sha256 = r.sha256, tag = r.tag) else e
    }

    /** Test hook: the full entry (listable or not) for an id, after a load. */
    fun any(id: String) = lastAll.firstOrNull { it.id == id }

    fun http(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 15_000
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}
