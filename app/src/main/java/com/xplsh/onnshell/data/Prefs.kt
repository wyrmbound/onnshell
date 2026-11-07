package com.xplsh.onnshell.data

import android.content.Context
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** An accent is a gradient. The first color doubles as the solid accent (icons, text). */
data class Accent(val colors: List<Color>) {
    val main: Color get() = colors.first()
    val brush: Brush get() = Brush.linearGradient(colors)
    fun at(i: Int): Color = colors[i.coerceAtMost(colors.lastIndex)]
}

private fun solid(c: Color) = Accent(listOf(c, lerp(c, Color.White, 0.35f)))

/** OnnShell's own settings. These are app data, not system changes, so they are not in the ledger. */
object Prefs {
    val ACCENTS = listOf(
        Accent(listOf(Color(0xFFFF4C1D), Color(0xFFE5266E), Color(0xFF7A2FF2))), // Sunset, the default
        solid(Color(0xFF00BCD4)), solid(Color(0xFFFFC107)), solid(Color(0xFF4CAF50)),
        solid(Color(0xFFE040FB)), solid(Color(0xFFFF5252)), solid(Color(0xFFFFFFFF)),
    )

    private fun sp(ctx: Context) = ctx.getSharedPreferences("shell", Context.MODE_PRIVATE)

    // v2: Sunset was added at index 0 and made the default
    fun accentIndex(ctx: Context) = sp(ctx).getInt("accent_v2", 0).coerceIn(ACCENTS.indices)
    fun setAccentIndex(ctx: Context, i: Int) = sp(ctx).edit().putInt("accent_v2", i).apply()

    /** Lite effects: the spec's flat look (no blur, no transparency, no drifting background). */
    fun lite(ctx: Context) = sp(ctx).getBoolean("lite", false)
    fun setLite(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("lite", on).apply()

    // v2: the first seed missed updated system apps (Netflix, YouTube), so it is seeded again once
    fun whitelist(ctx: Context): Set<String>? = sp(ctx).getStringSet("whitelist_v2", null)?.toSet()
    fun setWhitelist(ctx: Context, s: Set<String>) = sp(ctx).edit().putStringSet("whitelist_v2", s).apply()

    /** Every launchable package seen at the last load, to spot new installs. Null before the first load. */
    fun known(ctx: Context): Set<String>? = sp(ctx).getStringSet("known", null)?.toSet()
    fun setKnown(ctx: Context, s: Set<String>) = sp(ctx).edit().putStringSet("known", s).apply()

    /** Per-app touchpad profiles, see Profiles. */
    fun profiles(ctx: Context): Map<String, Profile> = Profiles.parse(sp(ctx).getString("profiles", null))
    fun setProfile(ctx: Context, pkg: String, p: Profile?) {
        val m = profiles(ctx).toMutableMap()
        if (p == null) m.remove(pkg) else m[pkg] = p
        sp(ctx).edit().putString("profiles", Profiles.format(m)).apply()
    }

    /** Library server address and its optional shared token. */
    fun libraryUrl(ctx: Context) = sp(ctx).getString("library_url", "") ?: ""
    fun libraryToken(ctx: Context) = sp(ctx).getString("library_token", "") ?: ""
    fun setLibrary(ctx: Context, url: String, token: String) =
        sp(ctx).edit().putString("library_url", url.trim().trimEnd('/')).putString("library_token", token.trim()).apply()

    /** Data pack storage budget in GB, and whether packs go to a USB drive. */
    val BUDGETS = listOf(4, 8, 16, 32, 64)
    /** Package the user launched last, for "PS at home goes back to it". */
    fun lastApp(ctx: Context): String? = sp(ctx).getString("last_app", null)
    fun setLastApp(ctx: Context, pkg: String?) = sp(ctx).edit().putString("last_app", pkg).apply()

    fun budgetGb(ctx: Context) = sp(ctx).getInt("budget_gb", 16)
    fun setBudgetGb(ctx: Context, gb: Int) = sp(ctx).edit().putInt("budget_gb", gb).apply()
    fun dataOnUsb(ctx: Context) = sp(ctx).getBoolean("data_usb", false)
    fun setDataOnUsb(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("data_usb", on).apply()

    /** Data packs placed on the box (see Pack). */
    fun packs(ctx: Context): List<Pack> = LibIndex.parsePacks(sp(ctx).getString("packs", null))
    fun setPacks(ctx: Context, p: List<Pack>) = sp(ctx).edit().putString("packs", LibIndex.formatPacks(p)).commit()

    /** When each app was last opened from OnnShell, for evicting the least played data packs first. */
    fun lastLaunch(ctx: Context): Map<String, Long> =
        sp(ctx).all.filterKeys { it.startsWith("launch:") }.mapNotNull { (k, v) -> (v as? Long)?.let { k.removePrefix("launch:") to it } }.toMap()
    fun setLaunched(ctx: Context, pkg: String) = sp(ctx).edit().putLong("launch:$pkg", System.currentTimeMillis()).apply()

    /** Apps the owner moved to the Games row. */
    fun games(ctx: Context): Set<String> = sp(ctx).getStringSet("games", emptySet())!!.toSet()
    fun setGames(ctx: Context, s: Set<String>) = sp(ctx).edit().putStringSet("games", s).apply()
}
