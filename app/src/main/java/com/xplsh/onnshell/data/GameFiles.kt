package com.xplsh.onnshell.data

import android.os.Environment
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/** Game files on the box's storage, for OnnShell's file picker: walk the drives, match what a port wants. */
object GameFiles {
    /** A file on the box, or one entry inside a zip on the box. */
    data class Found(val file: File, val entry: String? = null)

    private val SHA1 = Regex("[0-9a-f]{40}")
    private val ROM_EXT = setOf("z64", "n64", "v64", "iso", "bin", "rom", "zip")
    private const val MAX_HASH = 1L shl 30

    /** Where "Send game files" puts what arrives from a phone or PC. Any file anywhere on the drives still counts. */
    val INBOX = File("/storage/emulated/0/GameFiles")

    /** sha1 by path, size, mtime (and zip entry). shortcut: memory only, so the first look after a restart hashes again; persist it if that gets slow. */
    private val sums = ConcurrentHashMap<String, String>()
    private fun cachedSha1(key: String, open: () -> InputStream) = sums.getOrPut(key) { open().use { sha1(it) } }

    /** What is on the drives, reduced to what game matching needs: names, fingerprints, and which kinds of file exist. */
    data class Index(val names: Set<String>, val sums: Set<String>, val exts: Set<String>)

    /** Indexes [files] and the entries of zips among them; hashes only files with one of [hashExts] under 1 GB. */
    fun index(files: List<File>, hashExts: Set<String>): Index {
        val names = HashSet<String>(); val found = HashSet<String>(); val exts = HashSet<String>()
        fun add(name: String, size: Long, key: String, open: () -> InputStream) {
            val ext = name.substringAfterLast('.', "")
            names += name; exts += ext
            if (ext in hashExts && size in 0 until MAX_HASH) runCatching { found += cachedSha1(key, open) }
        }
        for (f in files) runCatching {
            val n = f.name.lowercase(); val key = "${f.path}|${f.length()}|${f.lastModified()}"
            add(n, f.length(), key) { f.inputStream() }
            if (n.endsWith(".zip")) ZipFile(f).use { z ->
                for (e in z.entries()) if (!e.isDirectory) add(e.name.substringAfterLast('/').lowercase(), e.size, "$key!${e.name}") { z.getInputStream(e) }
            }
        }
        return Index(names, found, exts)
    }

    enum class Status { NONE, READY, WRONG, MISSING }

    /** Wanted name extensions that say "this kind of file" (a .z64 ROM); zip and bin say nothing about the game. */
    private fun telling(wants: Set<String>) = wants.filterNot { SHA1.matches(it) }.map { it.substringAfterLast('.', "") }
        .filter { it.isNotEmpty() && it !in setOf("zip", "bin", "dat", "txt") }.toSet()

    /** Extensions worth hashing for these wants (ROM-like files plus the wanted names' own). */
    fun hashExts(wants: Collection<String>) = ROM_EXT + wants.filterNot { SHA1.matches(it) }.map { it.substringAfterLast('.', "") }.filter { it.isNotEmpty() }

    /**
     * Whether a game's files are on the box. [present]/[candidates] are for 64-bit games that read from one folder
     * (requirements.json "pick"); [wants] are the names and fingerprints a regular port asks for.
     * WRONG: a file of the kind the game wants is here, but no accepted version (another region, a modified dump).
     */
    fun status(wants: Set<String>, needsFiles: Boolean, idx: Index, present: Boolean? = null, candidates: Boolean = false): Status = when {
        present != null -> if (present || candidates) Status.READY else Status.MISSING
        wants.isNotEmpty() -> when {
            wants.any { it in idx.names || it in idx.sums } -> Status.READY
            wants.any { SHA1.matches(it) } && telling(wants).any { it in idx.exts } -> Status.WRONG
            else -> Status.MISSING
        }
        needsFiles -> Status.MISSING
        else -> Status.NONE
    }

    /** Internal storage plus every readable drive under /storage (USB sticks show up there by volume id). */
    fun roots(): List<File> = listOf(File("/storage/emulated/0")) +
        (File("/storage").listFiles()?.filter { it.isDirectory && it.canRead() && it.name !in setOf("emulated", "self") }.orEmpty())

    /** Device Owner can't grant "all files access" to itself; the shell user can, through Shizuku. */
    fun grantStorage(pkg: String) {
        if (!Environment.isExternalStorageManager()) ShellUser.run("appops set --uid $pkg MANAGE_EXTERNAL_STORAGE allow")
    }

    fun walk(roots: List<File>): List<File> = roots.flatMap { root ->
        root.walkTopDown().maxDepth(6)
            .onEnter { it == root || (it.name != "Android" && !it.name.startsWith(".")) }
            .filter { it.isFile && !it.name.startsWith(".") }.toList()
    }

    /** Files (and zip entries) whose lowercase name or sha1 is in [wants]. Hashes only ROM-like files under 1 GB. */
    fun matches(files: List<File>, wants: Set<String>): List<Found> {
        if (wants.isEmpty()) return emptyList()
        val sums = wants.filter { SHA1.matches(it) }.toSet()
        val names = wants - sums
        val exts = ROM_EXT + names.map { it.substringAfterLast('.', "") }.filter { it.isNotEmpty() }
        fun hit(name: String, size: Long, key: String, open: () -> InputStream) = name in names ||
            (sums.isNotEmpty() && name.substringAfterLast('.', "") in exts && size in 0 until MAX_HASH && cachedSha1(key, open) in sums)
        val out = mutableListOf<Found>()
        for (f in files) runCatching {
            val n = f.name.lowercase()
            val key = "${f.path}|${f.length()}|${f.lastModified()}"
            if (hit(n, f.length(), key) { f.inputStream() }) out += Found(f)
            else if (n.endsWith(".zip")) ZipFile(f).use { z ->
                for (e in z.entries()) if (!e.isDirectory && hit(e.name.substringAfterLast('/').lowercase(), e.size, "$key!${e.name}") { z.getInputStream(e) }) out += Found(f, e.name)
            }
        }
        return out
    }

    fun sha1(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-1")
        val buf = ByteArray(1 shl 16)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Copies one zip entry to [dir]/<entry file name>; the name comes from an untrusted zip, so no paths. */
    fun extract(zip: File, entry: String, dir: File): File {
        val name = entry.substringAfterLast('/')
        require(name.isNotEmpty() && name != "." && name != "..") { "bad entry name" }
        dir.mkdirs()
        val out = File(dir, name)
        ZipFile(zip).use { z -> z.getInputStream(z.getEntry(entry)).use { i -> out.outputStream().use { i.copyTo(it) } } }
        return out
    }
}
