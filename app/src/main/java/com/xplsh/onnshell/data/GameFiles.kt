package com.xplsh.onnshell.data

import android.os.Environment
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Game files on the box's storage, for OnnShell's file picker: walk the drives, match what a port wants. */
object GameFiles {
    /** A file on the box, or one entry inside a zip on the box. */
    data class Found(val file: File, val entry: String? = null)

    private val SHA1 = Regex("[0-9a-f]{40}")
    private val ROM_EXT = setOf("z64", "n64", "v64", "iso", "bin", "rom", "zip")
    private const val MAX_HASH = 1L shl 30

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
        fun hit(name: String, size: Long, open: () -> InputStream) = name in names ||
            (sums.isNotEmpty() && name.substringAfterLast('.', "") in exts && size in 0 until MAX_HASH && open().use { sha1(it) } in sums)
        val out = mutableListOf<Found>()
        for (f in files) runCatching {
            val n = f.name.lowercase()
            if (hit(n, f.length()) { f.inputStream() }) out += Found(f)
            else if (n.endsWith(".zip")) ZipFile(f).use { z ->
                for (e in z.entries()) if (!e.isDirectory && hit(e.name.substringAfterLast('/').lowercase(), e.size) { z.getInputStream(e) }) out += Found(f, e.name)
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
