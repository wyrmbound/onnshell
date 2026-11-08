package com.xplsh.onnshell.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

data class AppEntry(
    val label: String,
    val pkg: String,
    val isGame: Boolean,
    val isSystem: Boolean,
    val version: String = "",
    val updated: Long = 0,
    val installed: Long = 0,
    val icon: ImageBitmap? = null,
    val banner: ImageBitmap? = null,
    /** Average color of the banner (or icon), ARGB. Tints the backdrop and the card glow. */
    val color: Int = 0xFF202020.toInt(),
    /** The banner shrunk to 40x22. Stretched full screen it reads as a soft blur, with no blur pass. */
    val art: ImageBitmap? = null,
)

object Apps {
    /** Shown by default if installed. Jellyfin and Moonlight are named in the spec. */
    val DEFAULT_WHITELIST = setOf("org.jellyfin.androidtv", "com.limelight", "com.android.vending")

    /** Every launchable app except OnnShell, with icons. Call off the main thread. */
    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        // Some games mark themselves only with OUYA's category, not Android's appCategory (Pokemon Red recomp)
        val ouya = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory("tv.ouya.intent.category.GAME"), 0)
            .map { it.activityInfo.packageName }.toSet()
        return listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
            .flatMap { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(it), 0) }
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != ctx.packageName }
            .map { ai ->
                val info = runCatching { pm.getPackageInfo(ai.packageName, 0) }.getOrNull()
                val icon = ai.loadIcon(pm)?.toBitmap(96, 96)
                val banner = ai.loadBanner(pm)?.toBitmap(320, 180)
                AppEntry(
                    label = ai.loadLabel(pm).toString(),
                    pkg = ai.packageName,
                    isGame = ai.category == ApplicationInfo.CATEGORY_GAME || ai.packageName in ouya,
                    // Preinstalled apps that got Play updates (Netflix, YouTube) count as the owner's apps
                    isSystem = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                        ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0,
                    version = info?.versionName ?: "",
                    updated = info?.lastUpdateTime ?: 0,
                    installed = info?.firstInstallTime ?: 0,
                    icon = icon?.asImageBitmap(),
                    banner = banner?.asImageBitmap(),
                    color = (banner ?: icon)?.averageColor() ?: 0xFF202020.toInt(),
                    art = banner?.let { fadedThumb(it).asImageBitmap() },
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /** Where installed 64-bit games keep their APK (OnnShell reads name and art from it; it is never installed). */
    private fun bareEntries(ids: Set<String>) = ids.map { id ->
        AppEntry(
            label = nameFor(id) ?: id, pkg = NATIVE + id, isGame = true, isSystem = false, version = "", updated = 0L, installed = 0L,
            icon = null, banner = null, color = 0xFF202020.toInt(), art = null,
        )
    }

    fun nativeGamesDir(ctx: Context) = java.io.File(ctx.getExternalFilesDir(null), "games")
    const val NATIVE = "native:"

    /**
     * 64-bit games (apkinstall output in /data/local/tmp/games/<id>, run through apkrun). Their APKs sit in
     * nativeGamesDir as <id>.apk: Android cannot install them here, but can still read their label, icon and banner.
     * Call off the main thread.
     */
    /** The games folder lists what is really installed: `<id>` per game, one per line ("id size mtime" as [signature]). */
    private const val GAMES = "/data/local/tmp/games"
    private const val LIST = "cd $GAMES 2>/dev/null && for d in */; do d=\${d%/}; [ -f \$d/game.json ] || [ -f \$d/run.sh ] && echo \$d; done"

    /** Installed 64-bit game ids from the box, or null when Shizuku is down (then the tiles in [nativeGamesDir] are trusted as is). */
    fun nativeIds(): Set<String>? = ShellUser.output(LIST)?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()

    /** Changes when a game is added, removed or replaced, here or in [nativeGamesDir]; cheap enough to poll. */
    fun nativeSignature(ctx: Context): String =
        (ShellUser.output("cd $GAMES 2>/dev/null && ls -ld --full-time */") ?: "") +
            (nativeGamesDir(ctx).listFiles()?.joinToString { "${it.name}${it.lastModified()}" } ?: "")

    /** A readable name for a game id with no art of its own: the Store's name for it, else the id. */
    var nameFor: (String) -> String? = { null }

    fun loadNative(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val onBox = nativeIds()
        val files = (nativeGamesDir(ctx).listFiles { f -> f.name.endsWith(".apk") || f.name.endsWith(".tile") } ?: emptyArray())
            // a game deleted from the box (adb, a test) leaves its tile behind: drop it
            .filter { onBox == null || it.nameWithoutExtension in onBox }
        // games on the box with no tile here (put there by hand, over adb, or restored from a backup) still get one
        val bare = (onBox ?: emptySet()) - files.map { it.nameWithoutExtension }.toSet()
        return bareEntries(bare) + files.mapNotNull { f ->
            // Games built for the box (no APK): <id>.tile holds the name, <id>.png the art
            if (f.name.endsWith(".tile")) {
                val art = runCatching { android.graphics.BitmapFactory.decodeFile(java.io.File(f.parentFile, f.nameWithoutExtension + ".png").path) }.getOrNull()
                    ?.let { android.graphics.Bitmap.createScaledBitmap(it, 320, 180, true) }
                return@mapNotNull AppEntry(
                    label = f.readText().trim().ifEmpty { f.nameWithoutExtension }, pkg = NATIVE + f.nameWithoutExtension,
                    isGame = true, isSystem = false, version = "", updated = f.lastModified(), installed = f.lastModified(),
                    icon = null, banner = art?.asImageBitmap(), color = art?.averageColor() ?: 0xFF202020.toInt(),
                    art = art?.let { fadedThumb(it).asImageBitmap() },
                )
            }
            val info = pm.getPackageArchiveInfo(f.path, 0) ?: return@mapNotNull null
            val ai = info.applicationInfo ?: return@mapNotNull null
            ai.sourceDir = f.path; ai.publicSourceDir = f.path // lets PackageManager load resources from the file
            val icon = runCatching { ai.loadIcon(pm)?.toBitmap(96, 96) }.getOrNull()
            val banner = runCatching { ai.loadBanner(pm)?.toBitmap(320, 180) }.getOrNull()
            AppEntry(
                label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(f.nameWithoutExtension),
                pkg = NATIVE + f.nameWithoutExtension,
                isGame = true, isSystem = false,
                version = info.versionName ?: "", updated = f.lastModified(), installed = f.lastModified(),
                icon = icon?.asImageBitmap(), banner = banner?.asImageBitmap(),
                color = (banner ?: icon)?.averageColor() ?: 0xFF202020.toInt(),
                art = banner?.let { fadedThumb(it).asImageBitmap() },
            )
        }.sortedBy { it.label.lowercase() }
    }

    /**
     * Games row and Apps row. Games (by category, or moved there by the owner) always show,
     * other apps only if whitelisted.
     */
    fun split(apps: List<AppEntry>, whitelist: Set<String>, games: Set<String> = emptySet()): Pair<List<AppEntry>, List<AppEntry>> {
        val (g, a) = apps.partition { it.isGame || it.pkg in games }
        return g to a.filter { it.pkg in whitelist }
    }

    /** The Games row rule for one package: Android's game category, OUYA's, or moved to Games by the owner. */
    fun isGame(ctx: Context, pkg: String): Boolean {
        if (pkg in Prefs.games(ctx)) return true
        val pm = ctx.packageManager
        val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return false
        return ai.category == ApplicationInfo.CATEGORY_GAME ||
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory("tv.ouya.intent.category.GAME").setPackage(pkg), 0).isNotEmpty()
    }

    /** First run whitelist: the defaults plus every non-game app the owner installed. */
    fun seed(apps: List<AppEntry>): Set<String> =
        DEFAULT_WHITELIST + apps.filter { !it.isGame && !it.isSystem }.map { it.pkg }

    /**
     * New installs join the Apps row by themselves: anything not seen before, or, the first time
     * this runs, anything installed after OnnShell. Apps the owner removed stay removed, since
     * they are already known.
     */
    fun refresh(apps: List<AppEntry>, whitelist: Set<String>, known: Set<String>?, ownInstall: Long): Set<String> =
        whitelist + apps.filter { a -> !a.isSystem && if (known != null) a.pkg !in known else a.installed > ownInstall }.map { it.pkg }

    /** Never closed: Play Store (its updates must finish) and Shizuku's own manager app. */
    private val KEEP_RUNNING = setOf("com.android.vending", "moe.shizuku.privileged.api")
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /**
     * The shell command that closes the owner's apps in the background: one "am kill" per app.
     * am kill only ends processes that are safe to kill (cached, background), so anything on screen,
     * playing, or running a foreground service (a download, a port install) is left alone.
     * Names are checked against the package name grammar before they reach the shell.
     */
    fun closeCommand(apps: List<AppEntry>): String? =
        apps.filter { !it.isSystem && it.pkg !in KEEP_RUNNING && PACKAGE_NAME.matches(it.pkg) }
            .joinToString("; ") { "am kill --user 0 ${it.pkg}" }
            .ifEmpty { null }

    fun launch(ctx: Context, pkg: String): Boolean {
        val pm = ctx.packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) // explicit component from the package manager
        Prefs.setLaunched(ctx, pkg) // the library evicts the data of the least played games first
        return true
    }

    private fun Drawable.toBitmap(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, w, h)
        draw(Canvas(b))
        return b
    }

    /**
     * 40x22 thumbnail, darkened to 55% (white banners like Play Store and YouTube would otherwise
     * wash out the text on top), whose alpha fades to zero toward the bottom.
     */
    private fun fadedThumb(src: Bitmap): Bitmap {
        val b = Bitmap.createBitmap(40, 22, Bitmap.Config.ARGB_8888)
        val dark = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setScale(0.55f, 0.55f, 0.55f, 1f) })
        }
        Canvas(b).drawBitmap(src, null, android.graphics.Rect(0, 0, 40, 22), dark)
        val mask = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, 22f, intArrayOf(-0x1, -0x1, 0), floatArrayOf(0f, 0.35f, 1f), android.graphics.Shader.TileMode.CLAMP)
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        }
        Canvas(b).drawRect(0f, 0f, 40f, 22f, mask)
        return b
    }

    /** Mean of the opaque pixels, so transparent icon corners do not pull it to black. */
    private fun Bitmap.averageColor(): Int {
        val small = Bitmap.createScaledBitmap(this, 16, 16, true)
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (x in 0 until 16) for (y in 0 until 16) {
            val p = small.getPixel(x, y)
            if (p ushr 24 < 128) continue
            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
        }
        if (n == 0) return 0xFF202020.toInt()
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }
}
