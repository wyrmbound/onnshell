package com.xplsh.onnshell.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** ISO time ("2026-11-09T14:00:15Z") to epoch ms; 0 when missing or odd. */
fun isoMs(s: String?): Long = if (s.isNullOrEmpty()) 0 else runCatching { Instant.parse(s).toEpochMilli() }.getOrDefault(0)

private fun readJson(f: File) = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
private fun writeJson(f: File, o: JSONObject) { val tmp = File(f.path + ".tmp"); tmp.writeText(o.toString()); tmp.renameTo(f) }

/**
 * What the box has worked out itself about downloads the bundled survey (assets/survey.json) does not know.
 * The survey is keyed by download link and links change with every game update, so it ages by the day; the box reads
 * a few KB of each unknown APK (GameInstaller.probe) and remembers the answer. This is the Store's daily upkeep.
 */
object Probes {
    private const val RETRY_MS = 24 * 3600 * 1000L
    private fun file(ctx: Context) = File(ctx.filesDir, "store-probes.json")

    fun engineName(e: GameInstaller.Engine) = when (e) {
        GameInstaller.Engine.SDL2 -> "sdl2"; GameInstaller.Engine.SDL3 -> "sdl3"; GameInstaller.Engine.GAMEMAKER -> "gamemaker"
        GameInstaller.Engine.UNITY -> "unity"; GameInstaller.Engine.GODOT -> "godot"; GameInstaller.Engine.DOTNET -> "dotnet"
        GameInstaller.Engine.OTHER -> "native-other"; GameInstaller.Engine.NONE -> "no-native"
    }

    fun all(ctx: Context): Map<String, Survey> {
        val o = readJson(file(ctx))
        return o.keys().asSequence().mapNotNull { url ->
            val v = o.getJSONObject(url)
            if (v.has("engine")) url to Survey(v.getString("engine"), v.optBoolean("has32")) else null
        }.toMap()
    }

    /** Probes up to [budget] of [entries] that nothing is known about (a failed probe is retried after a day). Returns how many were learned. */
    suspend fun learn(ctx: Context, entries: List<Entry>, budget: Int = 160): Int = withContext(Dispatchers.IO) {
        val o = readJson(file(ctx)); val now = System.currentTimeMillis()
        val todo = entries.filter { it.engine == "unknown" && it.size > 0 }
            .filter { e -> o.optJSONObject(e.url).let { it == null || (!it.has("engine") && now - it.optLong("t") > RETRY_MS) } }
            .distinctBy { it.url }.take(budget)
        if (todo.isEmpty()) return@withContext 0
        val gate = Semaphore(6)
        val got = coroutineScope {
            todo.map { e -> async { gate.withPermit { e.url to runCatching { GameInstaller.probe(e.url, e.size) }.getOrNull() } } }.awaitAll()
        }
        var learned = 0
        for ((url, p) in got) {
            val v = JSONObject().put("t", now)
            if (p != null) { v.put("engine", engineName(p.engine)).put("has32", p.has32); learned++ }
            o.put(url, v)
        }
        writeJson(file(ctx), o)
        android.util.Log.i("Store", "probed ${todo.size} unknown downloads, learned $learned")
        learned
    }
}

/**
 * The Store's second source: each game's own GitHub project. Every listing carries its repository and the pattern of
 * its APK, so when the Recompa list cannot be reached the box asks GitHub for the project's latest release itself.
 * Nothing is mirrored: the box keeps links, and the downloads still come from the projects.
 */
object Direct {
    data class Rel(val url: String, val size: Long, val tag: String, val sha256: String?, val published: Long, val checked: Long)

    private fun file(ctx: Context) = File(ctx.filesDir, "store-direct.json")

    /** The APK of a GitHub "latest release" answer that fits [pattern] (the listing's own regex, else any .apk). */
    fun parseLatest(json: String, pattern: String?, now: Long = System.currentTimeMillis()): Rel? {
        val o = JSONObject(json)
        val assets = o.optJSONArray("assets") ?: return null
        val re = runCatching { Regex(pattern?.ifEmpty { null } ?: "(?i)\\.apk$") }.getOrDefault(Regex("(?i)\\.apk$"))
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val url = a.optString("browser_download_url")
            if (!re.containsMatchIn(a.optString("name")) || !url.startsWith("https://github.com/")) continue
            return Rel(url, a.optLong("size"), o.optString("tag_name"), a.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 },
                isoMs(o.optString("published_at")), now)
        }
        return null
    }

    /** A direct answer replaces the list's link only when it was fetched after the list was last refreshed and names another release. */
    fun newer(e: Entry, r: Rel?, listSavedAt: Long) = r != null && r.checked > listSavedAt && r.tag.isNotEmpty() && r.tag != e.tag && r.url != e.url

    fun all(ctx: Context): Map<String, Rel> {
        val o = readJson(file(ctx))
        return o.keys().asSequence().mapNotNull { id ->
            val v = o.getJSONObject(id)
            if (v.has("url")) id to Rel(v.getString("url"), v.optLong("size"), v.optString("tag"), v.optString("sha256").ifEmpty { null }, v.optLong("published"), v.optLong("t")) else null
        }.toMap()
    }

    /** The list as the Store shows it: links from GitHub directly where those are newer. The engine verdict stays the project's. */
    fun apply(ctx: Context, entries: List<Entry>, listSavedAt: Long): List<Entry> {
        val rels = all(ctx)
        if (rels.isEmpty()) return entries
        return entries.map { e ->
            val r = rels[e.id]
            if (newer(e, r, listSavedAt)) e.copy(url = r!!.url, size = r.size, sha256 = r.sha256, tag = r.tag, updatedAt = r.published, source = "github") else e
        }
    }

    /** Asks GitHub for [e]'s latest release (one API call) and remembers it. Null when the game has no repository or GitHub says no. */
    suspend fun check(ctx: Context, e: Entry, fetch: (String) -> String = Catalog::http): Rel? = withContext(Dispatchers.IO) {
        val repo = e.repo?.takeIf { Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(it) } ?: return@withContext null
        val r = parseLatest(fetch("https://api.github.com/repos/$repo/releases/latest"), e.pattern)
        synchronized(this@Direct) {
            val o = readJson(file(ctx)); val v = JSONObject().put("t", System.currentTimeMillis())
            if (r != null) v.put("url", r.url).put("size", r.size).put("tag", r.tag).put("sha256", r.sha256 ?: "").put("published", r.published)
            o.put(e.id, v); writeJson(file(ctx), o)
        }
        r
    }

    /**
     * Checks the [budget] games looked at longest ago ([first] ids go ahead of the rest). GitHub allows 60 anonymous
     * calls an hour, so a full pass over the list takes a few hours of Store visits; a refusal ends the round.
     * Returns how many answers differ from the list.
     */
    suspend fun sweep(ctx: Context, entries: List<Entry>, first: Set<String> = emptySet(), budget: Int = 40): Int = withContext(Dispatchers.IO) {
        val o = readJson(file(ctx))
        val todo = entries.filter { it.repo != null }.sortedWith(compareBy<Entry>({ it.id !in first }, { o.optJSONObject(it.id)?.optLong("t") ?: 0L })).take(budget)
        var changed = 0
        for (e in todo) {
            val r = runCatching { check(ctx, e) }.getOrElse { err ->
                // 403/429: GitHub's hourly limit, try again next round. Anything else (a project that is gone, 404) is
                // noted as looked at, or it would stay first in line and block every later round.
                if (Regex("HTTP (403|429)").containsMatchIn(err.message ?: "")) {
                    android.util.Log.i("Store", "direct: GitHub's hourly limit reached at ${e.id}"); return@withContext changed
                }
                synchronized(this@Direct) { val d = readJson(file(ctx)); d.put(e.id, JSONObject().put("t", System.currentTimeMillis())); writeJson(file(ctx), d) }
                null
            }
            if (r != null && r.tag != e.tag && r.url != e.url) changed++
        }
        android.util.Log.i("Store", "direct: checked ${todo.size} projects on GitHub, $changed newer than the list")
        changed
    }
}
