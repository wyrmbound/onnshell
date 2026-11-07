package com.xplsh.onnshell.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xplsh.onnshell.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * The game library: catalog from the LAN server (server/server.py), resumable downloads checked by
 * sha256, silent APK installs as Device Owner, and data packs placed through Shizuku (the shell user
 * can write to other apps' storage, OnnShell cannot). Only native APKs and their data, per the spec.
 */
object Library {
    enum class State { GET, UPDATE, INSTALLED }

    var catalog by mutableStateOf<List<LibItem>>(emptyList()); private set
    var message by mutableStateOf(""); private set
    /** Per item: "Downloading 45%", "Installing", ... while it works. */
    val progress = mutableStateMapOf<String, String>()

    private fun covers(ctx: Context) = File(ctx.filesDir, "covers").apply { mkdirs() }
    fun coverFile(ctx: Context, id: String) = File(covers(ctx), id)

    // Downloads live in the external cache, which the shell user can read when it places data packs
    private fun work(ctx: Context) = File(ctx.externalCacheDir ?: ctx.cacheDir, "library").apply { mkdirs() }

    private fun open(ctx: Context, path: String, from: Long = 0): HttpURLConnection {
        val base = Prefs.libraryUrl(ctx)
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 30000
        Prefs.libraryToken(ctx).takeIf { it.isNotEmpty() }?.let { c.setRequestProperty("X-Token", it) }
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        return c
    }

    /** Fetches the catalog and its covers. Call off the main thread. */
    fun refresh(ctx: Context) {
        val url = Prefs.libraryUrl(ctx)
        if (url.isEmpty()) { message = ctx.getString(R.string.lib_no_server); return }
        if (!LibIndex.lanUrl(url)) { message = ctx.getString(R.string.lib_not_lan); return }
        message = ctx.getString(R.string.lib_loading)
        runCatching {
            val c = open(ctx, "/index.json")
            if (c.responseCode == 401) error(ctx.getString(R.string.lib_token_wrong))
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            catalog = LibIndex.parse(c.inputStream.bufferedReader().use { it.readText() })
            for (item in catalog) item.cover?.let { path ->
                runCatching { open(ctx, path).inputStream.use { i -> coverFile(ctx, item.id).outputStream().use { i.copyTo(it) } } }
            }
            message = ctx.getString(R.string.lib_count, catalog.size)
        }.onFailure { message = ctx.getString(R.string.lib_unreachable, it.message ?: it.toString()) }
    }

    fun state(ctx: Context, item: LibItem): State {
        val have = if (item.type == "apk") {
            runCatching { ctx.packageManager.getPackageInfo(item.id, 0).longVersionCode }.getOrNull()
        } else {
            Prefs.packs(ctx).firstOrNull { it.id == item.id }?.version
        }
        return when {
            have == null -> State.GET
            have < item.version -> State.UPDATE
            else -> State.INSTALLED
        }
    }

    /** Download (resuming), verify, then install or place. Runs in the background, reports through progress. */
    fun get(ctx: Context, item: LibItem) {
        if (progress.containsKey(item.id)) return
        val app = ctx.applicationContext
        progress[item.id] = app.getString(R.string.lib_starting)
        Thread {
            val error = runCatching {
                val file = download(app, item)
                if (item.type == "apk") installApk(app, item, file) else placeData(app, item, file)
                file.delete()
            }.exceptionOrNull()
            progress.remove(item.id)
            message = if (error == null) app.getString(R.string.lib_done, item.title)
            else app.getString(R.string.lib_failed, item.title, error.message ?: error.toString())
        }.start()
    }

    private fun download(ctx: Context, item: LibItem): File {
        val part = File(work(ctx), "${item.id}.part")
        var have = if (part.exists()) part.length() else 0L
        if (have > item.size) { part.delete(); have = 0 }
        if (have < item.size) {
            val c = open(ctx, item.file, have)
            val append = c.responseCode == 206
            if (!append && c.responseCode != 200) error("HTTP ${c.responseCode}")
            if (!append) have = 0 // the server ignored the range, start over
            c.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        val pct = if (item.size > 0) (have * 100 / item.size).toInt() else 100
                        if (pct != last) { progress[item.id] = ctx.getString(R.string.lib_downloading, pct); last = pct }
                    }
                }
            }
        }
        progress[item.id] = ctx.getString(R.string.lib_verifying)
        val md = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        if (sha != item.sha256) {
            part.delete() // never keep a bad download, the next try starts clean
            error(ctx.getString(R.string.lib_bad_sha))
        }
        return part
    }

    /** Silent as Device Owner; if OnnShell ever is not, Android shows its own install prompt instead. */
    private fun installApk(ctx: Context, item: LibItem, file: File) {
        progress[item.id] = ctx.getString(R.string.lib_installing)
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(item.id)
            setSize(file.length())
        }
        val sid = pi.createSession(params)
        val (status, msg) = pi.openSession(sid).use { s ->
            s.openWrite("base.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            await(ctx, sid) { s.commit(it) }
        }
        if (status != PackageInstaller.STATUS_SUCCESS) error(msg ?: "install status $status")
        if (item.game) Prefs.setGames(ctx, Prefs.games(ctx) + item.id) // library games go to the Games row
    }

    /** Uninstalls an app, or removes a data pack's files (only the files it placed: saves stay). */
    fun remove(ctx: Context, item: LibItem) {
        if (progress.containsKey(item.id)) return
        val app = ctx.applicationContext
        progress[item.id] = app.getString(R.string.lib_removing)
        Thread {
            val error = runCatching {
                if (item.type == "apk") {
                    val (status, msg) = await(app, item.id.hashCode()) { app.packageManager.packageInstaller.uninstall(item.id, it) }
                    if (status != PackageInstaller.STATUS_SUCCESS) error(msg ?: "uninstall status $status")
                } else {
                    Prefs.packs(app).firstOrNull { it.id == item.id }?.let { removePack(app, it) }
                }
            }.exceptionOrNull()
            progress.remove(item.id)
            message = if (error == null) app.getString(R.string.lib_removed, item.title)
            else app.getString(R.string.lib_failed, item.title, error.message ?: error.toString())
        }.start()
    }

    /** Waits for a PackageInstaller result. Started with an IntentSender that reports back to us. */
    private fun await(ctx: Context, id: Int, start: (IntentSender) -> Unit): Pair<Int, String?> {
        val action = "${ctx.packageName}.LIBRARY_RESULT.$id"
        val done = CountDownLatch(1)
        var status = PackageInstaller.STATUS_FAILURE
        var msg: String? = null
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    // Not Device Owner: Android asks on screen, the final result comes in a second broadcast
                    i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    return
                }
                done.countDown()
            }
        }
        ctx.registerReceiver(rx, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        try {
            val pi = PendingIntent.getBroadcast(
                ctx, id, Intent(action).setPackage(ctx.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            start(pi.intentSender)
            if (!done.await(10, TimeUnit.MINUTES)) error("timed out")
        } finally {
            ctx.unregisterReceiver(rx)
        }
        return status to msg
    }

    /** Where data packs go: shared storage, or the first mounted USB drive if the owner chose it. */
    private fun volume(ctx: Context): File {
        if (!Prefs.dataOnUsb(ctx)) return Environment.getExternalStorageDirectory()
        return ctx.getSystemService(StorageManager::class.java).storageVolumes
            .firstOrNull { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }?.directory
            ?: error(ctx.getString(R.string.lib_no_usb))
    }

    private fun placeData(ctx: Context, item: LibItem, file: File) {
        if (!ShellUser.ready()) error(ctx.getString(R.string.lib_needs_shizuku))
        val rel = LibIndex.resolveTarget(item.target ?: "", item.requires) ?: error("bad target ${item.target}")
        val root = File(volume(ctx), rel).absolutePath

        // Unpack into a stage folder first (a zip, or the single file under its own name)
        progress[item.id] = ctx.getString(R.string.lib_unpacking)
        val stage = File(work(ctx), "stage-${item.id}").apply { deleteRecursively(); mkdirs() }
        val files = mutableListOf<String>()
        try {
            if (isZip(file)) {
                ZipInputStream(file.inputStream().buffered()).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (e.isDirectory) continue
                        if (!LibIndex.safeEntry(e.name)) error(ctx.getString(R.string.lib_bad_entry, e.name))
                        File(stage, e.name).apply { parentFile?.mkdirs() }.outputStream().use { zip.copyTo(it) }
                        files += e.name
                    }
                }
            } else {
                if (!LibIndex.safeEntry(item.name)) error(ctx.getString(R.string.lib_bad_entry, item.name))
                file.copyTo(File(stage, item.name), overwrite = true)
                files += item.name
            }
            val size = files.sumOf { File(stage, it).length() }

            // An update replaces the old version's files; then make room inside the budget
            val packs = Prefs.packs(ctx)
            packs.firstOrNull { it.id == item.id }?.let { removePack(ctx, it) }
            makeRoom(ctx, size, item.id)
            val free = runCatching { StatFs(volume(ctx).path).availableBytes }.getOrDefault(Long.MAX_VALUE)
            if (free < size) error(ctx.getString(R.string.lib_no_space))

            progress[item.id] = ctx.getString(R.string.lib_placing)
            val code = ShellUser.run("mkdir -p '$root' && cp -r '${stage.absolutePath}/.' '$root/'")
            if (code != 0) error(ctx.getString(R.string.lib_not_writable, root))
            Prefs.setPacks(ctx, Prefs.packs(ctx).filter { it.id != item.id } +
                Pack(item.id, item.version, root, files, size, System.currentTimeMillis(), item.requires))
        } finally {
            stage.deleteRecursively()
        }
    }

    /** Evicts the least recently played data packs until [need] bytes fit in the budget. Never saves. */
    private fun makeRoom(ctx: Context, need: Long, keep: String) {
        val budget = Prefs.budgetGb(ctx) * 1_000_000_000L
        var packs = Prefs.packs(ctx).filter { it.id != keep }
        var used = packs.sumOf { it.size }
        for (victim in LibIndex.evictOrder(packs, Prefs.lastLaunch(ctx))) {
            if (used + need <= budget) break
            removePack(ctx, victim)
            used -= victim.size
            packs = packs - victim
        }
        if (used + need > budget) error(ctx.getString(R.string.lib_over_budget, Prefs.budgetGb(ctx)))
    }

    /** Deletes exactly the files a pack placed, as the shell user. Anything else in that folder (saves) stays. */
    private fun removePack(ctx: Context, p: Pack) {
        if (!ShellUser.ready()) error(ctx.getString(R.string.lib_needs_shizuku))
        val proc = ShellUser.start("cd '${p.root}' && xargs -0 rm -f --") ?: error(ctx.getString(R.string.lib_needs_shizuku))
        try {
            proc.outputStream.use { o -> o.write(p.files.filter { LibIndex.safeEntry(it) }.joinToString("\u0000").toByteArray()) }
            proc.waitFor()
        } catch (e: IOException) {
            error(e.toString())
        }
        Prefs.setPacks(ctx, Prefs.packs(ctx).filter { it.id != p.id })
    }

    private fun isZip(f: File) = f.inputStream().use { i ->
        val b = ByteArray(4)
        i.read(b) == 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2] == 3.toByte() && b[3] == 4.toByte()
    }
}
