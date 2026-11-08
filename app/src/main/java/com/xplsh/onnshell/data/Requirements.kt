package com.xplsh.onnshell.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What a port needs from the owner, by port id (assets/requirements.json), and where it goes on the box.
 * Shown in the Store's detail panel and on the Get warning; [Pick] also drives "pick your file" before a 64-bit game
 * launches, because those games skip the Android side that would normally ask for the file.
 */
object Requirements {
    /** Where the game reads its files: [dir] holds a file with one of [ext] (or, for a folder game, the [marker] file). */
    data class Pick(val folder: Boolean, val ext: Set<String>, val marker: String?, val dir: String)
    data class Need(val lines: List<String>, val pick: Pick?)

    private val LABELS = listOf("game" to "Game", "version" to "Version", "file" to "File", "check" to "Fingerprint",
        "goes" to "Goes in", "not" to "Does not work", "note" to "")

    /** Entries are either a list of lines (older form) or an object with the [LABELS] fields and an optional "pick". */
    fun parse(json: String): Map<String, Need> {
        val o = JSONObject(json)
        return o.keys().asSequence().filterNot { it.startsWith("_") }.associate { k ->
            k.lowercase() to when (val v = o.get(k)) {
                is JSONArray -> Need((0 until v.length()).map { v.getString(it) }, null)
                is JSONObject -> Need(
                    LABELS.mapNotNull { (key, label) -> v.optString(key).ifEmpty { null }?.let { if (label.isEmpty()) it else "$label: $it" } },
                    v.optJSONObject("pick")?.let { p ->
                        val ext = p.optJSONArray("ext")
                        Pick(p.optString("type") == "folder", (0 until (ext?.length() ?: 0)).map { ext!!.getString(it).lowercase() }.toSet(),
                            p.optString("marker").ifEmpty { null }, p.getString("dir"))
                    },
                )
                else -> Need(listOf(v.toString()), null)
            }
        }
    }

    @Volatile private var cache: Map<String, Need>? = null
    fun all(ctx: Context): Map<String, Need> = cache ?: runCatching {
        ctx.assets.open("requirements.json").bufferedReader().use { parse(it.readText()) }
    }.getOrDefault(emptyMap()).also { cache = it }

    fun forId(ctx: Context, id: String) = all(ctx)[id.lowercase()]

    /** The game's files are already where it reads them. */
    fun present(p: Pick): Boolean {
        val dir = File(p.dir)
        return if (p.folder) p.marker?.let { File(dir, it).isFile } ?: (dir.list()?.isNotEmpty() == true)
        else dir.listFiles()?.any { it.isFile && it.extension.lowercase() in p.ext } == true
    }

    /** What on the box's drives could be the game's files: matching files, or folders holding the marker. Never [Pick.dir] itself. */
    fun candidates(p: Pick, files: List<File>): List<File> {
        val target = File(p.dir).absolutePath
        return if (p.folder) files.filter { it.name.equals(p.marker, ignoreCase = true) }.mapNotNull { it.parentFile }.distinct()
            .filter { it.absolutePath != target }
        else files.filter { it.extension.lowercase() in p.ext && it.parentFile?.absolutePath != target }
    }

    /** Copies [src] (a file, or a folder's contents) into [Pick.dir], reporting 0..1. Large: an ISO is about 3 GB. */
    fun install(p: Pick, src: File, progress: (Float) -> Unit) {
        val dir = File(p.dir).apply { mkdirs() }
        val files = if (src.isDirectory) src.walkTopDown().filter { it.isFile }.toList() else listOf(src)
        val total = files.sumOf { it.length() }.coerceAtLeast(1)
        var done = 0L
        val buf = ByteArray(1 shl 20)
        for (f in files) {
            val out = if (src.isDirectory) File(dir, f.relativeTo(src).path) else File(dir, f.name)
            out.parentFile?.mkdirs()
            val tmp = File(out.path + ".part") // a cancelled or failed copy never looks like a finished file
            f.inputStream().use { i -> tmp.outputStream().use { o ->
                while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; progress(done.toFloat() / total) }
            } }
            if (!tmp.renameTo(out)) { tmp.delete(); error("could not finish ${out.name}") }
        }
    }
}
