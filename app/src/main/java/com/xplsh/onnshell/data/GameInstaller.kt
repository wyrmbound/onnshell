package com.xplsh.onnshell.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Store downloads, from "Get" to a playable game:
 *  - an APK with a 32-bit build installs as a normal Android app (silent: OnnShell is Device Owner);
 *  - a 64-bit-only SDL2 APK, or any SDL3 APK, becomes /data/local/tmp/games/<id> for apkrun (the on-box apkinstall.sh), and its APK
 *    is kept in Apps.nativeGamesDir so the Games row can show its name and art.
 * probe() tells which of the two (or neither) an APK is from a ranged read of its zip directory, before downloading.
 */
object GameInstaller {
    private const val TAG = "GameInstaller"
    private const val GAMES = "/data/local/tmp/games"
    private const val PB = "/data/local/tmp/pb"
    private val ID = Regex("[a-z0-9][a-z0-9-]{0,62}")
    /** The APK's own SDL is dropped: our libSDL2/libSDL3 in pb replace it. */
    private val OUR_SDL = setOf("libSDL2.so", "libSDL3.so")

    enum class Engine { SDL2, SDL3, GAMEMAKER, UNITY, GODOT, OTHER, NONE }
    data class Probe(val engine: Engine, val has32: Boolean, val has64: Boolean) {
        /** Can it run here: installs natively, or goes through the 64-bit runner. */
        val runnable get() = has32 || engine == Engine.SDL2 || engine == Engine.SDL3
    }

    // ---- probe: which engine, which ABIs, from a few KB of the APK ------------------------------------------

    private val probes = HashMap<String, Probe>()

    suspend fun probe(url: String, size: Long): Probe = withContext(Dispatchers.IO) {
        probes[url]?.let { return@withContext it }
        classify(zipNamesRemote(url, size)).also { synchronized(probes) { probes[url] = it } }
    }

    fun classify(names: List<String>): Probe {
        val libs = names.filter { it.startsWith("lib/arm64-v8a/") }.map { it.substringAfterLast('/') }.toSet()
        val has32 = names.any { it.startsWith("lib/armeabi-v7a/") }
        val engine = when {
            libs.isEmpty() -> Engine.NONE
            "libyoyo.so" in libs -> Engine.GAMEMAKER
            "libunity.so" in libs || "libil2cpp.so" in libs -> Engine.UNITY
            libs.any { it.startsWith("libgodot") } -> Engine.GODOT
            "libSDL3.so" in libs -> Engine.SDL3
            "libSDL2.so" in libs -> Engine.SDL2
            else -> Engine.OTHER
        }
        return Probe(engine, has32, libs.isNotEmpty())
    }

    private fun ranged(url: String, start: Long, end: Long): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.setRequestProperty("Range", "bytes=$start-$end")
        c.setRequestProperty("User-Agent", "OnnShell")
        c.connectTimeout = 15000; c.readTimeout = 30000
        try { return c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
    }

    /** File names in a remote zip: read the end-of-central-directory, then the directory itself. */
    private fun zipNamesRemote(url: String, size: Long): List<String> {
        val tailLen = minOf(size, 70_000L)
        val tail = ranged(url, size - tailLen, size - 1)
        var i = tail.size - 22
        while (i >= 0 && !(tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() && tail[i + 2] == 5.toByte() && tail[i + 3] == 6.toByte())) i--
        require(i >= 0) { "not a zip (no end record)" }
        val eocd = ByteBuffer.wrap(tail, i, 22).order(ByteOrder.LITTLE_ENDIAN)
        val cdSize = eocd.getInt(i + 12).toLong() and 0xffffffffL
        val cdOff = eocd.getInt(i + 16).toLong() and 0xffffffffL
        require(cdOff != 0xffffffffL) { "zip64 not supported" }
        return centralNames(ranged(url, cdOff, cdOff + cdSize - 1))
    }

    fun centralNames(cd: ByteArray): List<String> {
        val b = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<String>(); var p = 0
        while (p + 46 <= cd.size && b.getInt(p) == 0x02014b50) {
            val n = b.getShort(p + 28).toInt() and 0xffff
            val m = b.getShort(p + 30).toInt() and 0xffff
            val k = b.getShort(p + 32).toInt() and 0xffff
            out += String(cd, p + 46, n, Charsets.UTF_8)
            p += 46 + n + m + k
        }
        return out
    }

    // ---- install ----------------------------------------------------------------------------------------------

    // A 32-bit game removed outside the Store (adb, older builds without the id_of_ record) left its id in native32: ask Android too
    fun installed(ctx: Context, id: String, pkg: String? = null) = File(Apps.nativeGamesDir(ctx), "$id.apk").exists() ||
        id in ctx.getSharedPreferences("store", 0).getStringSet("native32", emptySet())!! &&
        (pkg == null || runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess)

    /** Download, verify and install. onProgress(fraction 0..1, what is happening). Off the main thread. */
    suspend fun install(ctx: Context, id: String, url: String, sha256: String?, size: Long,
                        onProgress: (Float, String) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(ID.matches(id)) { "bad id" }
            require(ShellUser.ready()) { "Shizuku is not running" }
            val apk = File(ctx.externalCacheDir, "dl/$id.apk").apply { parentFile!!.mkdirs() }
            download(url, apk, size) { onProgress(it * 0.8f, "Downloading") }
            if (!sha256.isNullOrBlank()) require(sha256(apk).equals(sha256, true)) { "download is corrupt (checksum)" }
            onProgress(0.85f, "Installing")
            unwrap(apk)
            val p = ZipFile(apk).use { z -> classify(z.entries().toList().map { it.name }) }
            when {
                p.engine == Engine.SDL3 -> install64(ctx, id, apk, "sdl3") // our runtime even when a 32-bit build exists
                p.has32 -> install32(ctx, id, apk)
                p.engine == Engine.SDL2 -> install64(ctx, id, apk, "sdl2")
                else -> error("this game's engine (${p.engine}) does not run here yet")
            }
            onProgress(1f, "Installed")
        }.onFailure { Log.e(TAG, "install $id: $it") }
    }

    /** Some releases ship the APK inside a zip (Open Tyrian 2000): put that APK in its place. */
    private fun unwrap(f: File) {
        val inner = File(f.path + ".inner")
        ZipFile(f).use { z ->
            if (z.getEntry("AndroidManifest.xml") != null) return
            val e = z.entries().toList().filter { it.name.endsWith(".apk") }.singleOrNull() ?: return
            z.getInputStream(e).use { i -> inner.outputStream().use { i.copyTo(it) } }
        }
        check(inner.renameTo(f)) { "could not unpack the APK" }
    }

    private fun download(url: String, dest: File, size: Long, progress: (Float) -> Unit) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true; c.setRequestProperty("User-Agent", "OnnShell")
        c.connectTimeout = 15000; c.readTimeout = 60000
        try {
            val total = if (c.contentLengthLong > 0) c.contentLengthLong else size
            c.inputStream.use { inp -> dest.outputStream().use { out ->
                val buf = ByteArray(1 shl 16); var got = 0L; var last = 0L
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    out.write(buf, 0, n); got += n
                    if (total > 0 && got - last > 256 * 1024) { last = got; progress(got.toFloat() / total) }
                }
            } }
        } finally { c.disconnect() }
    }

    private fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").let { md ->
        f.inputStream().use { s -> val buf = ByteArray(1 shl 16); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 32-bit build: a normal app. As Device Owner the commit needs no confirmation on the TV. */
    private suspend fun install32(ctx: Context, id: String, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val sid = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        val action = "${ctx.packageName}.INSTALL_RESULT.$sid"
        val result = CompletableDeferred<Pair<Int, String?>>()
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                result.complete(i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999) to i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
            }
        }
        ctx.registerReceiver(rx, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        try {
            pi.openSession(sid).use { s ->
                s.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                val pending = PendingIntent.getBroadcast(ctx, sid, Intent(action).setPackage(ctx.packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                s.commit(pending.intentSender)
            }
            val (status, msg) = result.await()
            check(status == PackageInstaller.STATUS_SUCCESS) { "Android refused the install: $msg ($status)" }
        } finally { runCatching { ctx.unregisterReceiver(rx) } }
        val sp = ctx.getSharedPreferences("store", 0)
        val pkg = ctx.packageManager.getPackageArchiveInfo(apk.path, 0)?.packageName
        sp.edit().putStringSet("native32", sp.getStringSet("native32", emptySet())!! + id)
            .apply { if (pkg != null) putString("id_of_$pkg", id) }.apply()
        pkg?.let { Prefs.setGames(ctx, Prefs.games(ctx) + it) }
        apk.delete()
    }

    /** 64-bit SDL2/SDL3 build: unpack for apkrun (same steps as onn-pathb/apkrun/apkinstall.sh). */
    private fun install64(ctx: Context, id: String, apk: File, engine: String) {
        val stage = File(ctx.getExternalFilesDir(null), "staging/$id").apply { deleteRecursively(); mkdirs() }
        val data = File(stage, "data").apply { mkdirs() }
        ZipFile(apk).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory) continue
                val target = when {
                    e.name.startsWith("lib/arm64-v8a/") && e.name.endsWith(".so") -> File(stage, e.name.substringAfterLast('/'))
                    e.name.startsWith("assets/") -> File(data, e.name.removePrefix("assets/"))
                    else -> continue
                }
                if (target.name in OUR_SDL && target.parentFile == stage) continue // ours replaces it on the box
                require(target.canonicalPath.startsWith(stage.canonicalPath)) { "unsafe path in APK: ${e.name}" }
                target.parentFile!!.mkdirs()
                z.getInputStream(e).use { inp -> target.outputStream().use { inp.copyTo(it) } }
            }
        }
        val libs = stage.listFiles { f -> f.name.endsWith(".so") }!!.toList()
        val elfs = libs.associateWith { runCatching { Elf.read(it) }.getOrNull() }
        val entry = elfs.entries.firstOrNull { it.value?.defines("SDL_main") == true }?.key
            ?: error("no SDL_main: not an SDL app")
        val runtime = runtimeLibs()
        val have = libs.map { it.name }.toSet() + runtime + OUR_SDL
        val missing = elfs.values.filterNotNull().flatMap { it.needed }.toSet() - have
        for (m in missing) stubFor(ctx, m).let { File(stage, m).writeBytes(it) }
        File(stage, "game.json").writeText("""{ "id": "$id", "entry": "${entry.name}", "engine": "$engine" }""" + "\n")
        val g = "$GAMES/$id"
        val rc = ShellUser.run("rm -rf '$g' && mkdir -p '$g' && cp -r '${stage.absolutePath}/.' '$g/' && chmod -R a+rX '$g'")
        check(rc == 0) { "could not place the game on the box ($rc)" }
        stage.deleteRecursively()
        val keep = File(Apps.nativeGamesDir(ctx), "$id.apk").apply { parentFile!!.mkdirs() }
        if (!apk.renameTo(keep)) { apk.copyTo(keep, true); apk.delete() }
        Log.i(TAG, "installed $id: entry ${entry.name}, stand-ins $missing")
    }

    /** 64-bit game: its files on the box and the APK kept for the tile. Saves live in its data dir, so they go too. */
    fun uninstall(ctx: Context, id: String) {
        require(ID.matches(id))
        ShellUser.run("rm -rf '$GAMES/$id'")
        listOf("apk", "tile", "png").forEach { File(Apps.nativeGamesDir(ctx), "$id.$it").delete() }
    }

    /** A normal app the owner installed (store 32-bit games, anything else). Silent: OnnShell is Device Owner. */
    suspend fun uninstallPackage(ctx: Context, pkg: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val action = "${ctx.packageName}.UNINSTALL_RESULT.${pkg.hashCode()}"
            val result = CompletableDeferred<Pair<Int, String?>>()
            val rx = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    result.complete(i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999) to i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                }
            }
            ctx.registerReceiver(rx, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            try {
                val pending = PendingIntent.getBroadcast(ctx, pkg.hashCode(), Intent(action).setPackage(ctx.packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                ctx.packageManager.packageInstaller.uninstall(pkg, pending.intentSender)
                val (status, msg) = result.await()
                check(status == PackageInstaller.STATUS_SUCCESS) { "Android refused to uninstall $pkg: $msg ($status)" }
            } finally { runCatching { ctx.unregisterReceiver(rx) } }
            val sp = ctx.getSharedPreferences("store", 0)
            sp.getString("id_of_$pkg", null)?.let { id ->
                sp.edit().putStringSet("native32", sp.getStringSet("native32", emptySet())!! - id).remove("id_of_$pkg").apply()
            }
            Prefs.setGames(ctx, Prefs.games(ctx) - pkg)
        }.onFailure { Log.e(TAG, "uninstall $pkg: $it") }
    }

    private fun runtimeLibs(): Set<String> {
        val p = ShellUser.start("ls $PB") ?: return emptySet()
        return p.inputStream.bufferedReader().readLines().map { it.trim() }.filter { it.endsWith(".so") || ".so." in it }.toSet()
    }

    /** The shipped empty library (assets/onnstub.so) renamed: its 63-char SONAME placeholder becomes [name]. */
    fun stubFor(ctx: Context, name: String): ByteArray =
        renameStub(ctx.assets.open("onnstub.so").use { it.readBytes() }, name)

    const val PLACEHOLDER = "libonnstub_placeholder_0000000000000000000000000000000000000.so"

    fun renameStub(stub: ByteArray, name: String): ByteArray {
        require(name.length <= PLACEHOLDER.length) { "library name too long for the stand-in: $name" }
        val at = String(stub, Charsets.ISO_8859_1).indexOf(PLACEHOLDER)
        require(at >= 0) { "stand-in template has no placeholder" }
        val out = stub.copyOf()
        for (i in PLACEHOLDER.indices) out[at + i] = if (i < name.length) name[i].code.toByte() else 0
        return out
    }
}

/** Just enough ELF64 (little endian, as on arm64) to read a shared library's DT_NEEDED and dynamic symbols. */
class Elf private constructor(val needed: List<String>, private val defined: Set<String>) {
    fun defines(sym: String) = sym in defined

    companion object {
        fun read(f: File): Elf = RandomAccessFile(f, "r").use { raf ->
            val bytes = ByteArray(raf.length().toInt()).also { raf.readFully(it) }
            parse(bytes)
        }

        fun parse(bytes: ByteArray): Elf {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(b.getInt(0) == 0x464c457f && bytes[4] == 2.toByte()) { "not ELF64" }
            val shoff = b.getLong(0x28).toInt(); val shentsize = b.getShort(0x3A).toInt(); val shnum = b.getShort(0x3C).toInt()
            data class Sec(val type: Int, val off: Int, val size: Int, val link: Int, val entsize: Int)
            val secs = (0 until shnum).map { i ->
                val o = shoff + i * shentsize
                Sec(b.getInt(o + 4), b.getLong(o + 0x18).toInt(), b.getLong(o + 0x20).toInt(), b.getInt(o + 0x28), b.getLong(o + 0x38).toInt())
            }
            fun str(tab: Sec, at: Int): String { var e = tab.off + at; while (bytes[e] != 0.toByte()) e++; return String(bytes, tab.off + at, e - tab.off - at, Charsets.UTF_8) }
            val needed = ArrayList<String>()
            secs.firstOrNull { it.type == 6 }?.let { dyn -> // SHT_DYNAMIC
                val strtab = secs[dyn.link]
                var p = dyn.off
                while (p + 16 <= dyn.off + dyn.size) {
                    val tag = b.getLong(p); val v = b.getLong(p + 8)
                    if (tag == 0L) break
                    if (tag == 1L) needed += str(strtab, v.toInt()) // DT_NEEDED
                    p += 16
                }
            }
            val defined = HashSet<String>()
            secs.firstOrNull { it.type == 11 }?.let { sym -> // SHT_DYNSYM
                val strtab = secs[sym.link]; val n = sym.size / maxOf(sym.entsize, 24)
                for (i in 1 until n) {
                    val o = sym.off + i * 24
                    val shndx = b.getShort(o + 6).toInt() and 0xffff
                    if (shndx != 0) defined += str(strtab, b.getInt(o))
                }
            }
            return Elf(needed, defined)
        }
    }
}
