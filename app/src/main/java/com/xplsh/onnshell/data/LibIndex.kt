package com.xplsh.onnshell.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI

/** One entry of the library server's index.json (server/server.py documents the fields). */
data class LibItem(
    val id: String,
    val title: String,
    val type: String, // "apk" or "data"
    val version: Long,
    val size: Long,
    val sha256: String,
    val file: String, // path on the server, e.g. /files/<id>
    val name: String, // original file name
    val cover: String? = null,
    val target: String? = null, // data: relative to shared storage, "{requires}" placeholder
    val requires: String? = null,
    val game: Boolean = true,
)

/** A data pack placed on the box: exactly the files OnnShell copied, so removal never touches saves. */
data class Pack(
    val id: String,
    val version: Long,
    val root: String,
    val files: List<String>,
    val size: Long,
    val installedAt: Long,
    val requires: String? = null,
)

/** The rules, kept free of Android so they are unit tested. */
object LibIndex {
    private val ID = Regex("^[A-Za-z0-9._-]{1,128}$")
    private val SHA = Regex("^[0-9a-f]{64}$")
    private val TARGET_CHARS = Regex("^[A-Za-z0-9._ {}/-]{1,200}$")
    private val SAFE_PATH = Regex("^[A-Za-z0-9._ +()\\[\\]/-]{1,240}$")

    /** Items from index.json. Anything malformed is dropped, never trusted. */
    fun parse(json: String): List<LibItem> {
        val items = runCatching { JSONObject(json).getJSONArray("items") }.getOrNull() ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            runCatching {
                val o = items.getJSONObject(i)
                val item = LibItem(
                    id = o.getString("id"),
                    title = o.optString("title").ifBlank { o.getString("id") },
                    type = o.getString("type"),
                    version = o.getLong("version"),
                    size = o.getLong("size"),
                    sha256 = o.getString("sha256"),
                    file = o.getString("file"),
                    name = o.optString("name").ifBlank { o.getString("id") },
                    cover = o.optString("cover").takeIf { it.startsWith("/covers/") },
                    target = o.optString("target").ifBlank { null },
                    requires = o.optString("requires").ifBlank { null },
                    game = o.optBoolean("game", true),
                )
                item.takeIf {
                    ID.matches(it.id) && it.type in setOf("apk", "data") && SHA.matches(it.sha256) && it.size >= 0 &&
                        it.file.startsWith("/files/") && (it.requires == null || ID.matches(it.requires)) &&
                        (it.type == "apk" || (it.target != null && validTarget(it.target)))
                }
            }.getOrNull()
        }
    }

    /** A data target: relative, no "..", plain characters only (it ends up in a shell command). */
    fun validTarget(t: String) =
        TARGET_CHARS.matches(t) && !t.startsWith("/") && t.split("/").none { it == ".." }

    /** The target with "{requires}" filled in, or null when it needs an app id the item does not name. */
    fun resolveTarget(t: String, requires: String?): String? {
        if (!validTarget(t)) return null
        if ("{requires}" in t && requires == null) return null
        val r = t.replace("{requires}", requires ?: "")
        return r.takeUnless { '{' in it || '}' in it }?.trim('/')
    }

    /** A path inside a data pack (zip entry): relative, no "..", no backslashes. Stops zip slip. */
    fun safeEntry(name: String) =
        SAFE_PATH.matches(name) && !name.startsWith("/") && name.split("/").none { it == ".." || it == "." }

    /** The server must be on the LAN: a private or loopback IP, or a local name (.local, .lan, .home, no dots). */
    fun lanUrl(url: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        if (u.scheme != "http" && u.scheme != "https") return false
        val host = u.host ?: return false
        if (host.all { it.isDigit() || it == '.' }) {
            val ip = runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet4Address ?: return false
            return ip.isSiteLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress
        }
        return '.' !in host || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home") || host.endsWith(".internal")
    }

    /** Data packs to evict, least recently played first (by the last launch of the app they belong to). */
    fun evictOrder(packs: List<Pack>, lastLaunch: Map<String, Long>): List<Pack> =
        packs.sortedBy { p -> p.requires?.let { lastLaunch[it] } ?: p.installedAt }

    fun formatPacks(packs: List<Pack>): String = JSONArray(packs.map { p ->
        JSONObject().put("id", p.id).put("version", p.version).put("root", p.root).put("files", JSONArray(p.files))
            .put("size", p.size).put("installedAt", p.installedAt).put("requires", p.requires ?: JSONObject.NULL)
    }).toString()

    fun parsePacks(json: String?): List<Pack> {
        val a = runCatching { JSONArray(json ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            runCatching {
                val o = a.getJSONObject(i)
                val f = o.getJSONArray("files")
                Pack(
                    o.getString("id"), o.getLong("version"), o.getString("root"),
                    (0 until f.length()).map { f.getString(it) }, o.getLong("size"), o.getLong("installedAt"),
                    if (o.isNull("requires")) null else o.getString("requires"),
                )
            }.getOrNull()
        }
    }
}
