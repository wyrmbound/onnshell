package com.xplsh.onnshell.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xplsh.onnshell.R

/** Where the tabs live. */
enum class Tabs { TOP_PILLS, LEFT_RAIL, MASTHEAD }
/** How game tiles are laid out. */
enum class Grid { HERO_ROW, BUBBLES, STRIPS }
/** What focus looks like. */
enum class Focus { RING, GLOW, OUTLINE }

/**
 * Everything a theme decides. The three themes are the owner's picks from the 2026-11-07 mockups
 * (Slate Console, Ink and Neon, Editorial Mono); the layout flags are what tells them apart beyond color.
 */
data class Theme(
    val id: String,
    val nameRes: Int,
    val bg: Color,
    val surface: Color,
    val surfaceHi: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    /** Focus and active-tab color. Slate uses the accent gradient the user picked; the others fix theirs. */
    val accent: Color,
    val display: FontFamily,
    val body: FontFamily,
    val tileRadius: Dp,
    val tabs: Tabs,
    val grid: Grid,
    val focus: Focus,
    /** Draw the drifting aurora in the theme's accent (Ink only). */
    val ambient: Boolean,
)

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weights: List<Int>) = FontFamily(
    weights.map { Font(res, FontWeight(it), variationSettings = FontVariation.Settings(FontVariation.weight(it))) }
)

object Themes {
    val Fredoka = variable(R.font.fredoka, listOf(400, 600, 700))
    val Nunito = variable(R.font.nunito, listOf(400, 700))
    val Bebas = FontFamily(Font(R.font.bebasneue, FontWeight.Normal))
    val PlexMono = FontFamily(Font(R.font.ibmplexmono, FontWeight.Normal), Font(R.font.ibmplexmono_medium, FontWeight.Medium))

    val SLATE = Theme(
        id = "slate", nameRes = R.string.theme_slate,
        bg = Color(0xFF0E0F12), surface = Color(0xFF23252C), surfaceHi = Color(0xFF2E3039),
        text = Color(0xFFF2F2F0), text2 = Color(0xFFC9CBD3), text3 = Color(0xFF8D909B), accent = Color(0xFFF2F2F0),
        display = Outfit, body = Outfit, tileRadius = 9.dp,
        tabs = Tabs.TOP_PILLS, grid = Grid.HERO_ROW, focus = Focus.RING, ambient = false,
    )
    val INK = Theme(
        id = "ink", nameRes = R.string.theme_ink,
        bg = Color(0xFF0B1F2E), surface = Color(0xFF10303F), surfaceHi = Color(0xFF15405A),
        text = Color(0xFFEEF7F9), text2 = Color(0xFFA9D5E2), text3 = Color(0xFF7FB5C6), accent = Color(0xFF39E2C3),
        display = Fredoka, body = Nunito, tileRadius = 24.dp,
        tabs = Tabs.LEFT_RAIL, grid = Grid.BUBBLES, focus = Focus.GLOW, ambient = true,
    )
    val MONO = Theme(
        id = "mono", nameRes = R.string.theme_mono,
        bg = Color(0xFF101010), surface = Color(0xFF1C1B18), surfaceHi = Color(0xFF2A2822),
        text = Color(0xFFEFE9DC), text2 = Color(0xFF8D877A), text3 = Color(0xFF5E5A50), accent = Color(0xFFD8A33A),
        display = Bebas, body = PlexMono, tileRadius = 0.dp,
        tabs = Tabs.MASTHEAD, grid = Grid.STRIPS, focus = Focus.OUTLINE, ambient = false,
    )
    val ALL = listOf(SLATE, INK, MONO)
    fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: SLATE
}

val LocalTheme = compositionLocalOf { Themes.SLATE }
