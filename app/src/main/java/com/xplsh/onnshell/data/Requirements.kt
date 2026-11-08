package com.xplsh.onnshell.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * What a port needs from the owner, by port id (assets/requirements.json), and where it goes on the box.
 * Shown in the Store's detail panel and on the Get warning; [Pick] also drives "pick your file" before a 64-bit game
 * launches, because those games skip the Android side that would normally ask for the file.
 */
object Requirements {
    /**
     * Where the game reads its files: [dir] holds a file with one of [ext] (or, for a folder game, the [marker] file).
     * A game that wants one exact file (a cartridge ROM) sets [name], the file's name in [dir]. Then: the file may sit
     * inside a zip; [has] is text the right game carries in its first 64 KB (the cartridge's own title), so another
     * game's ROM is never offered; [sha1] are dumps proven on this box, preferred when several fit; [seed] are items
     * of the port's own data folder it also expects in [seedDir] (default [dir]), which on a phone the port's Java side
     * would have copied; [arg] means the game takes the file as its argument.
     */
    data class Pick(val folder: Boolean, val ext: Set<String>, val marker: String?, val dir: String, val name: String? = null,
                    val has: String? = null, val sha1: Set<String> = emptySet(), val seed: List<String> = emptyList(), val seedDir: String? = null, val arg: Boolean = false)
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
                        fun strings(key: String) = p.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                        Pick(p.optString("type") == "folder", (0 until (ext?.length() ?: 0)).map { ext!!.getString(it).lowercase() }.toSet(),
                            p.optString("marker").ifEmpty { null }, p.getString("dir"), p.optString("name").ifEmpty { null },
                            p.optString("has").ifEmpty { null }, strings("sha1").map { it.lowercase() }.toSet(), strings("seed"), p.optString("seedDir").ifEmpty { null }, p.optBoolean("arg"))
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

    private const val GAMES = "/data/local/tmp/games"
    private val ID = Regex("[a-z0-9][a-z0-9-]*")

    /** The game's files are already where it reads them. Only the shell user sees under /data/local/tmp, so [shell] answers there. */
    fun present(p: Pick, shell: (String) -> Int? = ShellUser::run): Boolean {
        val dir = File(p.dir)
        return if (p.name != null) { if (p.dir.startsWith("/data/local/tmp")) shell("[ -f '${p.dir}/${p.name}' ]") == 0 else File(dir, p.name).isFile }
        else if (p.folder) p.marker?.let { File(dir, it).isFile } ?: (dir.list()?.isNotEmpty() == true)
        else dir.listFiles()?.any { it.isFile && it.extension.lowercase() in p.ext } == true
    }

    /** What on the box's drives could be the game's files: matching files (or zip entries), or folders holding the marker. Never [Pick.dir] itself. */
    fun candidates(p: Pick, files: List<File>): List<GameFiles.Found> {
        val target = File(p.dir).absolutePath
        if (p.folder) return files.filter { it.name.equals(p.marker, ignoreCase = true) }.mapNotNull { it.parentFile }.distinct()
            .filter { it.absolutePath != target }.map { GameFiles.Found(it) }
        val loose = files.filter { it.extension.lowercase() in p.ext && it.parentFile?.absolutePath != target }.map { GameFiles.Found(it) }
        if (p.name == null) return loose
        val zipped = files.filter { it.extension.equals("zip", true) }.flatMap { z ->
            runCatching { ZipFile(z).use { zf -> zf.entries().toList().filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in p.ext }.map { GameFiles.Found(z, it.name) } } }.getOrDefault(emptyList())
        }
        val fits = (loose + zipped).filter { f -> p.has == null || runCatching { read(f) { String(head(it), Charsets.ISO_8859_1).contains(p.has) } }.getOrDefault(false) }
        if (fits.size < 2 || p.sha1.isEmpty()) return fits
        return fits.filter { f -> runCatching { read(f) { sha1(it) } }.getOrNull() in p.sha1 }.ifEmpty { fits }
    }

    private fun <T> read(f: GameFiles.Found, block: (InputStream) -> T): T =
        if (f.entry == null) f.file.inputStream().use(block) else ZipFile(f.file).use { z -> z.getInputStream(z.getEntry(f.entry)).use(block) }

    /** Reads until [buf] is full or the stream ends (a zip stream hands out short reads). */
    private fun fill(i: InputStream, buf: ByteArray): Int { var n = 0; while (n < buf.size) { val r = i.read(buf, n, buf.size - n); if (r < 0) break; n += r }; return n }

    /**
     * N64 dumps come in three byte orders and the games want the cartridge's own (.z64, starts 80 37 12 40).
     * Returns the group size to reverse for a dump starting with [b]: 2 (.v64), 4 (.n64), or 0 for leave as is.
     */
    fun n64Swap(b: ByteArray) = when {
        b.size < 4 -> 0
        b[0] == 0x37.toByte() && b[1] == 0x80.toByte() && b[2] == 0x40.toByte() && b[3] == 0x12.toByte() -> 2
        b[0] == 0x40.toByte() && b[1] == 0x12.toByte() && b[2] == 0x37.toByte() && b[3] == 0x80.toByte() -> 4
        else -> 0
    }
    private fun reorder(b: ByteArray, n: Int, group: Int) {
        if (group == 0) return
        var i = 0
        while (i + group <= n) { var a = i; var z = i + group - 1; while (a < z) { val t = b[a]; b[a] = b[z]; b[z] = t; a++; z-- }; i += group }
    }

    /** The stream in cartridge byte order, a buffer at a time. */
    private fun ordered(i: InputStream, each: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(1 shl 16); var group = -1
        while (true) { val n = fill(i, buf); if (n <= 0) break; if (group < 0) group = n64Swap(buf); reorder(buf, n, group); each(buf, n) }
    }
    private fun head(i: InputStream): ByteArray { val buf = ByteArray(1 shl 16); val n = fill(i, buf); reorder(buf, n, n64Swap(buf)); return buf.copyOf(n) }
    private fun sha1(i: InputStream): String {
        val md = MessageDigest.getInstance("SHA-1"); ordered(i) { b, n -> md.update(b, 0, n) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Puts [src] where the game reads it, reporting 0..1. A file or a folder's contents are copied into [Pick.dir]
     * (large: an ISO is about 3 GB). A named file (see [Pick]) is written to [stage] in cartridge byte order, then the
     * shell user moves it into place for game [id], adds the seed items and, for an [Pick.arg] game, its start line.
     */
    fun install(p: Pick, found: GameFiles.Found, id: String = "", stage: File? = null, shell: (String) -> Int? = ShellUser::run, progress: (Float) -> Unit) {
        if (p.name != null) {
            require(ID.matches(id)) { "bad game id" } // goes into a shell line
            val tmp = File(stage!!.apply { mkdirs() }, p.name)
            try {
                val total = (if (found.entry == null) found.file.length() else ZipFile(found.file).use { it.getEntry(found.entry).size }).coerceAtLeast(1)
                var done = 0L
                read(found) { i -> tmp.outputStream().use { o -> ordered(i) { b, n -> o.write(b, 0, n); done += n; progress(done.toFloat() / total) } } }
                val to = "${p.dir}/${p.name}"; val g = "$GAMES/$id"; val sd = p.seedDir ?: p.dir
                val cmd = "mkdir -p '${p.dir}' && cp '${tmp.path}' '$to.part' && mv '$to.part' '$to' && chmod 666 '$to'" +
                    (if (p.seed.isEmpty()) "" else " && mkdir -p '$sd'") + p.seed.joinToString("") { " && cp -r '$g/data/$it' '$sd/'" } +
                    (if (p.arg) " && echo 'exec sh $GAMES/run_apk.sh $g $to' > '$g/run.sh'" else "")
                check(shell(cmd) == 0) { "could not put ${p.name} in place" }
            } finally { tmp.delete() }
            return
        }
        val src = found.file
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
