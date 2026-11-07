package com.xplsh.onnshell.ui

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.LocaleList
import android.provider.Settings
import android.view.InputDevice
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.xplsh.onnshell.R
import com.xplsh.onnshell.admin.Policy
import com.xplsh.onnshell.data.Accent
import com.xplsh.onnshell.data.AppEntry
import com.xplsh.onnshell.data.Apps
import com.xplsh.onnshell.data.Inputd
import com.xplsh.onnshell.data.Prefs
import com.xplsh.onnshell.data.ShellUser
import com.xplsh.onnshell.data.SystemInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

sealed interface Screen {
    data object Home : Screen
    data object Menu : Screen
    data object Controller : Screen
    data object ShellSettings : Screen
    data object EditApps : Screen
    data object InputDebug : Screen
    data class Info(val title: String, val lines: suspend () -> List<String>) : Screen
    data class Details(val tile: Tile) : Screen
    data class Options(val tile: Tile) : Screen
    data class Confirm(val text: String, val yes: String, val onYes: () -> Unit) : Screen
}

data class Tile(
    val id: String,
    val label: String,
    val icon: ImageVector? = null,
    val app: AppEntry? = null,
    /** One line shown in the hero for system items. */
    val desc: String = "",
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/** cards = app and game art, otherwise system tiles. */
private data class Shelf(val title: String, val tiles: List<Tile>, val cards: Boolean, val placeholder: Boolean = false)

/**
 * Focus state, kept out of Shell's own composition: only the hero, the backdrop and the tab
 * pills read it, so moving focus does not rebuild every row (that was the main jank source).
 */
private class Nav {
    var focused by mutableStateOf<Tile?>(null)
    var hint by mutableStateOf("")
    var last = ""
}

@Composable
fun Shell(resumes: Int) {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var accentIx by remember { mutableIntStateOf(Prefs.accentIndex(ctx)) }
    val lite = Pad.lite
    val accent = Prefs.ACCENTS[accentIx]
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var whitelist by remember { mutableStateOf(Prefs.whitelist(ctx) ?: emptySet()) }
    var userGames by remember { mutableStateOf(Prefs.games(ctx)) }
    val nav = remember { Nav() }

    // Reload on every return to HOME, so installs and disables show up. New installs join the Apps row.
    LaunchedEffect(resumes) {
        apps = withContext(Dispatchers.Default) { Apps.load(ctx) }
        val own = ctx.packageManager.getPackageInfo(ctx.packageName, 0).firstInstallTime
        whitelist = Apps.refresh(apps, Prefs.whitelist(ctx) ?: Apps.seed(apps), Prefs.known(ctx), own)
        Prefs.setWhitelist(ctx, whitelist)
        Prefs.setKnown(ctx, apps.map { it.pkg }.toSet())
        // Apps close when you leave them: back on HOME, end the owner's apps in the background
        withContext(Dispatchers.IO) { Apps.closeCommand(apps)?.let { ShellUser.run(it) } }
    }
    /** PS at home: back to the suspended game if there is one, else the last launched app. */
    fun last() {
        android.util.Log.i("M4", "last(): suspended=${com.xplsh.onnshell.display.GameSession.suspended} screen=$screen")
        if (com.xplsh.onnshell.display.GameSession.suspended) {
            ctx.startActivity(Intent(ctx, com.xplsh.onnshell.display.ZeroCopyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else Prefs.lastApp(ctx)?.let { Apps.launch(ctx, it) }
    }
    LaunchedEffect(Unit) {
        Pad.cmds.collect { cmd ->
            when (cmd) {
                Cmd.MENU -> screen = if (screen == Screen.Menu) Screen.Home else Screen.Menu
                Cmd.ARRIVE -> if (!Pad.testing) screen = Screen.Home
                Cmd.HOME -> if (!Pad.testing) { if (screen == Screen.Home) last() else screen = Screen.Home }
                Cmd.LAST -> if (!Pad.testing) last()
                Cmd.DETAILS -> if (screen == Screen.Home) nav.focused?.let { screen = Screen.Details(it) }
                Cmd.OPTIONS -> if (screen == Screen.Home) nav.focused?.let { screen = Screen.Options(it) }
            }
        }
    }
    LaunchedEffect(Pad.testing) { if (!Pad.testing && screen == Screen.Controller) screen = Screen.Home }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    fun open(action: String, data: Uri? = null) {
        runCatching { ctx.startActivity(Intent(action, data).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    fun launch(a: AppEntry) {
        if (!Apps.launch(ctx, a.pkg)) nav.hint = ctx.getString(R.string.launch_failed, a.label)
        else Prefs.setLastApp(ctx, a.pkg)
    }
    fun info(title: Int, lines: suspend () -> List<String>) { screen = Screen.Info(ctx.getString(title), lines) }
    fun confirmReboot() {
        screen = Screen.Confirm(ctx.getString(R.string.reboot_confirm), ctx.getString(R.string.yes_reboot)) { Policy.reboot(ctx) }
    }

    val (games, appRow) = Apps.split(apps, whitelist, userGames)
    val shelves = listOf(
        Shelf(stringResource(R.string.row_games),
            games.map { Tile("g:${it.pkg}", it.label, app = it) { launch(it) } }, cards = true, placeholder = true),
        Shelf(stringResource(R.string.row_apps),
            appRow.map { Tile("a:${it.pkg}", it.label, app = it) { launch(it) } }, cards = true),
        Shelf(stringResource(R.string.row_system), listOf(
            Tile("s:android", stringResource(R.string.sys_android_settings), Glyphs.settings, desc = stringResource(R.string.d_android)) { open(Settings.ACTION_SETTINGS) },
            Tile("s:shell", stringResource(R.string.sys_shell_settings), Glyphs.sliders, desc = stringResource(R.string.d_shell)) { screen = Screen.ShellSettings },
            Tile("s:wifi", stringResource(R.string.sys_wifi), Glyphs.wifi, desc = stringResource(R.string.d_wifi)) { open(Settings.ACTION_WIFI_SETTINGS) },
            Tile("s:eth", stringResource(R.string.sys_ethernet), Glyphs.ethernet, desc = stringResource(R.string.d_eth)) { info(R.string.sys_ethernet) { SystemInfo.ethernet(ctx) } },
            Tile("s:bt", stringResource(R.string.sys_bluetooth), Glyphs.bluetooth, desc = stringResource(R.string.d_bt)) { open(Settings.ACTION_BLUETOOTH_SETTINGS) },
            Tile("s:pad", stringResource(R.string.sys_controller), Glyphs.gamepad, desc = stringResource(R.string.d_pad)) { Pad.held.clear(); Pad.testing = true; screen = Screen.Controller },
            Tile("s:input", stringResource(R.string.sys_input), Glyphs.touchpad, desc = stringResource(R.string.d_input)) { screen = Screen.InputDebug },
            Tile("s:storage", stringResource(R.string.sys_storage), Glyphs.storage, desc = stringResource(R.string.d_storage)) { info(R.string.sys_storage) { SystemInfo.storage(ctx) } },
            Tile("s:library", stringResource(R.string.sys_library), Glyphs.sync, desc = stringResource(R.string.d_library)) { info(R.string.sys_library) { listOf(ctx.getString(R.string.library_phase5)) } },
            Tile("s:adb", stringResource(R.string.sys_adb), Glyphs.terminal, desc = stringResource(R.string.d_adb)) { info(R.string.sys_adb) { SystemInfo.adb(ctx) } },
            Tile("s:about", stringResource(R.string.sys_about), Glyphs.info, desc = stringResource(R.string.d_about)) { info(R.string.sys_about) { SystemInfo.about(ctx) } },
        ), cards = false),
        Shelf(stringResource(R.string.row_power), listOf(
            Tile("p:sleep", stringResource(R.string.power_sleep), Glyphs.sleep, desc = stringResource(R.string.d_sleep)) { Policy.sleep(ctx) },
            Tile("p:reboot", stringResource(R.string.power_reboot), Glyphs.reboot, desc = stringResource(R.string.d_reboot)) { confirmReboot() },
        ), cards = false),
    )

    CompositionLocalProvider(LocalTextStyle provides TextStyle(fontFamily = Outfit, color = Color.White)) {
        Box(Modifier.fillMaxSize().background(Ui.BG)) {
            Backdrop(nav, accent, lite, home = screen == Screen.Home)
            when (val s = screen) {
                Screen.Home -> Home(shelves, accent, lite, nav, resumes, { screen = Screen.Details(it) }, { screen = Screen.Options(it) })

                Screen.Menu -> Panel(stringResource(R.string.menu_title), accent) {
                    Choices(accent, listOf(
                        stringResource(R.string.sys_android_settings) to { screen = Screen.Home; open(Settings.ACTION_SETTINGS) },
                        stringResource(R.string.sys_shell_settings) to { screen = Screen.ShellSettings },
                        stringResource(R.string.power_sleep) to { screen = Screen.Home; Policy.sleep(ctx) },
                        stringResource(R.string.power_reboot) to { confirmReboot() },
                        stringResource(R.string.menu_close) to { screen = Screen.Home },
                    ))
                }

                Screen.Controller -> Panel(stringResource(R.string.sys_controller), accent) {
                    Line(stringResource(R.string.controller_held, Pad.held.joinToString().ifEmpty { "-" }))
                    Line(stringResource(R.string.controller_last, Pad.last.ifEmpty { "-" }))
                    Line(Pad.axes.entries.sortedBy { it.key }.joinToString("   ") { "%s %+.2f".format(it.key, it.value) })
                    Line(stringResource(R.string.controller_exit), Ui.TEXT2)
                }

                Screen.ShellSettings -> Panel(stringResource(R.string.sys_shell_settings), accent) {
                    val names = stringArrayResource(R.array.accent_names)
                    Choices(accent, listOf(
                        stringResource(R.string.settings_accent, names[accentIx]) to {
                            accentIx = (accentIx + 1) % Prefs.ACCENTS.size
                            Prefs.setAccentIndex(ctx, accentIx)
                        },
                        stringResource(R.string.settings_effects, stringResource(if (lite) R.string.effects_lite else R.string.effects_full)) to {
                            Pad.lite = !lite
                            Prefs.setLite(ctx, Pad.lite)
                        },
                        stringResource(R.string.settings_language, stringResource(R.string.lang_name)) to { toggleLanguage(ctx) },
                        stringResource(R.string.settings_apps_on_home) to { screen = Screen.EditApps },
                    ))
                }

                Screen.EditApps -> Panel(stringResource(R.string.settings_apps_on_home), accent) {
                    val on = stringResource(R.string.on)
                    val off = stringResource(R.string.off)
                    Choices(accent, apps.filter { !it.isGame }.map { a ->
                        "${a.label}: ${if (a.pkg in whitelist) on else off}" to {
                            whitelist = if (a.pkg in whitelist) whitelist - a.pkg else whitelist + a.pkg
                            Prefs.setWhitelist(ctx, whitelist)
                        }
                    })
                }

                // Live: reads Inputd's state, so it updates while you use the pad
                Screen.InputDebug -> Panel(stringResource(R.string.sys_input), accent) {
                    Line(if (Inputd.running) stringResource(R.string.input_running) else stringResource(R.string.input_stopped, ShellUser.status()))
                    Line(Inputd.pad?.let { stringResource(R.string.input_pad, it) } ?: stringResource(R.string.input_no_pad))
                    Line(stringResource(R.string.input_mode, stringResource(if (Inputd.touch) R.string.touchpad_touch else R.string.touchpad_pointer)))
                    Line(stringResource(R.string.input_app, Inputd.app.ifEmpty { "-" }))
                    Line(Inputd.latency.ifEmpty { stringResource(R.string.input_no_latency) })
                    if (Inputd.error.isNotEmpty()) Line(stringResource(R.string.input_error, Inputd.error), Ui.TEXT2)
                    Line(stringResource(R.string.input_help), Ui.TEXT2)
                }

                is Screen.Info -> Panel(s.title, accent) {
                    val lines by produceState(emptyList<String>(), s) { value = withContext(Dispatchers.IO) { s.lines() } }
                    lines.forEach { Line(it) }
                }

                is Screen.Details -> Panel(stringResource(R.string.details_title), accent) {
                    val a = s.tile.app
                    if (a == null) Line(s.tile.desc.ifEmpty { stringResource(R.string.no_details) })
                    else {
                        Line(a.label)
                        Line(stringResource(if (a.isGame) R.string.detail_game else R.string.detail_app), Ui.TEXT2)
                        Line(stringResource(R.string.detail_package, a.pkg))
                        Line(stringResource(R.string.detail_version, a.version.ifEmpty { "-" }))
                        Line(stringResource(R.string.detail_updated, DateFormat.getDateInstance().format(Date(a.updated))))
                    }
                }

                is Screen.Options -> Panel(stringResource(R.string.options_title), accent) {
                    val a = s.tile.app
                    if (a == null) Line(stringResource(R.string.no_options))
                    else Choices(accent, buildList {
                        if (s.tile.id.startsWith("a:")) {
                            add(stringResource(R.string.opt_to_games) to {
                                userGames = userGames + a.pkg
                                Prefs.setGames(ctx, userGames)
                                screen = Screen.Home
                            })
                            add(stringResource(R.string.opt_remove_home) to {
                                whitelist = whitelist - a.pkg
                                Prefs.setWhitelist(ctx, whitelist)
                                screen = Screen.Home
                            })
                        }
                        if (a.pkg in userGames) add(stringResource(R.string.opt_to_apps) to {
                            userGames = userGames - a.pkg
                            whitelist = whitelist + a.pkg
                            Prefs.setGames(ctx, userGames)
                            Prefs.setWhitelist(ctx, whitelist)
                            screen = Screen.Home
                        })
                        val touchNow = Prefs.profiles(ctx)[a.pkg]?.touch == true
                        add(stringResource(if (touchNow) R.string.opt_touchpad_pointer else R.string.opt_touchpad_touch) to {
                            Inputd.setTouch(ctx, a.pkg, !touchNow)
                            screen = Screen.Home
                        })
                        add(stringResource(R.string.opt_app_info) to {
                            screen = Screen.Home
                            open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))
                        })
                    })
                }

                is Screen.Confirm -> Panel("", accent) {
                    Line(s.text)
                    Spacer(Modifier.size(16.dp))
                    Choices(accent, listOf( // Cancel first, so it has focus
                        stringResource(R.string.cancel) to { screen = Screen.Home },
                        s.yes to { screen = Screen.Home; s.onYes() },
                    ))
                }
            }
        }
    }
}

private fun toggleLanguage(ctx: Context) {
    val es = ctx.resources.configuration.locales[0].language == "es"
    // Per-app language: Android recreates the activity in the new language
    ctx.getSystemService(LocaleManager::class.java).applicationLocales =
        LocaleList.forLanguageTags(if (es) "en-US" else "es-PR")
}

/**
 * Full: a slowly drifting aurora in the accent colors plus the focused app's art, blurred, behind
 * the hero. Lite: one static solid gradient (the spec's no blur, no transparency look).
 */
@Composable
private fun Backdrop(nav: Nav, accent: Accent, lite: Boolean, home: Boolean) {
    val app = if (home) nav.focused?.app else null
    val glow by animateColorAsState(app?.let { Color(it.color) } ?: accent.main, tween(500), label = "glow")
    if (lite) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(lerp(glow, Ui.BG, 0.5f), Ui.BG), Offset.Zero, 1700f)))
        return
    }
    // 1. The focused app's color, one cheap full screen gradient. Bright colors are dimmed more,
    // so white text stays readable on top of a white app's glow.
    val dim = 0.35f + 0.45f * glow.luminance()
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(lerp(glow, Ui.BG, dim), Ui.BG), Offset.Zero, 1900f)))
    // 2. The aurora: a tiny prerendered texture, drifting by layer transform only (no redraw)
    val aurora = remember(accent) { auroraBitmap(accent) }
    // Drifts back and forth while someone is using the remote; after 30 s idle it stops drawing
    // (a TV can sit on HOME for hours) and wakes on the next key.
    val drift = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        var to = 1f
        while (true) {
            if (System.currentTimeMillis() - Pad.lastInput > 30_000) {
                snapshotFlow { Pad.lastInput }.first { System.currentTimeMillis() - it < 2_000 }
            }
            drift.animateTo(to, tween(12_000, easing = LinearEasing))
            to = 1f - to
        }
    }
    Image(
        aurora, null,
        Modifier.fillMaxSize().graphicsLayer {
            val t = drift.value // read in the layer block: moves the layer, recomposes nothing
            scaleX = 1.25f; scaleY = 1.25f
            translationX = (t - 0.5f) * 140f
            translationY = (0.5f - t) * 70f
        },
        contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Low,
    )
    // 3. The focused app's art behind the hero: a 40 px thumbnail stretched up reads as a heavy blur.
    // Its fade to transparent is baked into the thumbnail, so it melts into the aurora with no edge.
    Crossfade(app?.art, animationSpec = tween(400), label = "art") { art ->
        if (art != null) Image(
            art, null, Modifier.fillMaxWidth().fillMaxHeight(0.75f).graphicsLayer { alpha = 0.6f },
            contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Low,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Home(
    shelves: List<Shelf>, accent: Accent, lite: Boolean, nav: Nav, resumes: Int,
    onDetails: (Tile) -> Unit, onOptions: (Tile) -> Unit,
) {
    val reqs = remember { mutableMapOf<String, FocusRequester>() }
    val ids = shelves.flatMap { s -> s.tiles.map { it.id } }
    val scroll = rememberScrollState()
    val tops = remember { mutableStateMapOf<Int, Int>() }
    var active by remember { mutableIntStateOf(-1) }
    // The focused row snaps to the top of the row area. Keyed on the measured top too: on launch
    // focus arrives before the rows are measured.
    LaunchedEffect(active, tops[active]) { tops[active]?.let { scroll.animateScrollTo(it) } }
    val onFocus: (Tile) -> Unit = { t -> nav.focused = t; nav.last = t.id; nav.hint = "" }

    // Rows run edge to edge so a focused card's lift and frame are never cut at the page margin.
    // Everything else keeps the margin, and the rows scroll to keep the focused card a margin
    // away from the screen edge.
    val marginPx = with(LocalDensity.current) { EDGE.toPx() }
    val rowSpec = remember(marginPx) { KeepMargin(marginPx) }
    Column(Modifier.fillMaxSize().padding(top = 20.dp)) {
        Box(Modifier.padding(horizontal = EDGE)) {
            TopBar(shelves, accent, active, resumes) { i ->
                shelves[i].tiles.firstOrNull()?.let { runCatching { reqs[it.id]?.requestFocus() } }
            }
        }
        Box(Modifier.padding(horizontal = EDGE)) { Hero(nav, accent, onDetails, onOptions) }
        // Vertical moves are owned by the row snap. The default focus scroll fought it and left half
        // rows showing, so it is switched off here; the horizontal rows get KeepMargin below.
        CompositionLocalProvider(LocalBringIntoViewSpec provides NoScroll) {
        Column(Modifier.weight(1f).verticalScroll(scroll)) {
            shelves.forEachIndexed { i, shelf ->
                Column(
                    Modifier
                        .onGloballyPositioned { tops[i] = it.positionInParent().y.toInt() }
                        .onFocusChanged { if (it.hasFocus) active = i },
                ) {
                    Text(
                        shelf.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                        modifier = Modifier.padding(start = EDGE, top = 10.dp, bottom = 10.dp),
                    )
                    if (shelf.tiles.isEmpty() && shelf.placeholder) Box(Modifier.padding(start = EDGE - 10.dp)) { GamesPlaceholder() }
                    else CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                        Row(
                            // full width; the inner padding is the page margin plus room for the lift and glow
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = EDGE, vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(if (shelf.cards) 26.dp else 16.dp),
                        ) {
                            shelf.tiles.forEach { t ->
                                val req = reqs.getOrPut(t.id) { FocusRequester() }
                                if (shelf.cards) GameCard(t, accent, lite, req, onFocus) else SystemTile(t, accent, req, onFocus)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(260.dp)) // lets the last row snap to the top too
        }
        }
    }
    // Focus restore: back to the last focused tile on every return, else the first tile
    LaunchedEffect(resumes, ids.size) {
        val id = nav.last.takeIf { it in ids } ?: ids.firstOrNull() ?: return@LaunchedEffect
        runCatching { reqs[id]?.requestFocus() }
    }
}

/**
 * The DS4 touchpad (and any mouse) drives Android's own pointer, and a pad click arrives as a mouse
 * click. TV Surfaces only react to OK and A key presses, so every clickable thing also gets this.
 * A click focuses the item first, so the hero shows what was clicked.
 */
private fun Modifier.pointerClick(onClick: () -> Unit, onLongClick: (() -> Unit)? = null, focus: FocusRequester? = null) =
    pointerInput(onClick, onLongClick) {
        detectTapGestures(
            onLongPress = onLongClick?.let { long -> { _: Offset -> long() } },
            onTap = { runCatching { focus?.requestFocus() }; onClick() },
        )
    }

/** Page margin. Rows scroll under it, everything else sits inside it. */
private val EDGE = 56.dp

@OptIn(ExperimentalFoundationApi::class)
private object NoScroll : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

/** Horizontal rows: scroll only as much as needed to keep the focused card a margin away from both screen edges. */
@OptIn(ExperimentalFoundationApi::class)
private class KeepMargin(private val margin: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        offset < margin -> offset - margin
        offset + size > containerSize - margin -> offset + size - (containerSize - margin)
        else -> 0f
    }
}

@Composable
private fun TopBar(shelves: List<Shelf>, accent: Accent, active: Int, resumes: Int, onTab: (Int) -> Unit) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000) // tick on the minute
        }
    }
    val net by produceState<ImageVector?>(null, resumes) { value = withContext(Dispatchers.IO) { networkGlyph(ctx) } }
    var pad by remember { mutableStateOf(gamepadConnected()) }
    DisposableEffect(Unit) { // live: the gamepad icon lights up when a controller connects
        val im = ctx.getSystemService(InputManager::class.java)
        val l = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(id: Int) { pad = gamepadConnected() }
            override fun onInputDeviceRemoved(id: Int) { pad = gamepadConnected() }
            override fun onInputDeviceChanged(id: Int) { pad = gamepadConnected() }
        }
        im.registerInputDeviceListener(l, null)
        onDispose { im.unregisterInputDeviceListener(l) }
    }

    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(brush = accent.brush)) { append("onn") }
                append("shell")
            },
            fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp,
        )
        Spacer(Modifier.width(36.dp))
        shelves.forEachIndexed { i, shelf -> TabPill(shelf.title, i == active, accent) { onTab(i) } }
        Spacer(Modifier.weight(1f))
        net?.let { Image(it, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(Ui.TEXT2)) }
        Spacer(Modifier.width(14.dp))
        Image(Glyphs.gamepad, null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(if (pad) accent.main else Ui.TEXT3))
        Spacer(Modifier.width(22.dp))
        Text(
            android.text.format.DateFormat.getTimeFormat(ctx).format(Date(now)),
            fontSize = 22.sp, fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun TabPill(label: String, active: Boolean, accent: Accent, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.padding(end = 8.dp).onFocusChanged { focused = it.isFocused }.pointerClick(onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = clear(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White))),
    ) {
        Text(
            label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            color = if (active || focused) Color.White else Ui.TEXT2,
            modifier = Modifier
                .background(if (active) accent.brush else SolidColor(Color.Transparent), RoundedCornerShape(50))
                .padding(horizontal = 18.dp, vertical = 7.dp),
        )
    }
}

/** The big title area: name, facts, and the button actions for the focused item. */
@Composable
private fun Hero(nav: Nav, accent: Accent, onDetails: (Tile) -> Unit, onOptions: (Tile) -> Unit) {
    val t = nav.focused
    val app = t?.app
    Column(Modifier.fillMaxWidth().height(196.dp).padding(top = 22.dp)) {
        Text(
            t?.label ?: "", fontSize = 48.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (app != null) {
                Chip(stringResource(if (app.isGame) R.string.detail_game else R.string.detail_app))
                if (app.version.isNotEmpty()) Chip(app.version)
            } else if (t != null) Text(t.desc, fontSize = 19.sp, color = Ui.TEXT2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        if (nav.hint.isNotEmpty()) Text(nav.hint, fontSize = 17.sp, color = accent.main, maxLines = 1, overflow = TextOverflow.Ellipsis)
        else if (t != null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Clickable too, for touchpad users; the gamepad buttons work without them
            ActionPill("A", stringResource(R.string.act_open), accent.brush, t.onClick)
            if (app != null) {
                ActionPill("X", stringResource(R.string.act_details), null) { onDetails(t) }
                ActionPill("Y", stringResource(R.string.act_options), null) { onOptions(t) }
            }
        }
    }
}

@Composable
private fun Chip(text: String) {
    Text(
        text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ui.TEXT2, maxLines = 1,
        modifier = Modifier.padding(end = 10.dp).border(1.dp, Ui.SURFACE_HI, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 5.dp),
    )
}

/** A button hint: the face button letter in a circle, then what it does. */
@Composable
private fun ActionPill(button: String, label: String, fill: Brush?, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).pointerClick(onClick).background(fill ?: SolidColor(Ui.SURFACE))
            .padding(start = 8.dp, end = 18.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            Text(button, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        }
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GamesPlaceholder() {
    Row(
        Modifier.padding(vertical = 4.dp, horizontal = 10.dp).size(420.dp, 76.dp)
            .clip(RoundedCornerShape(18.dp)).background(Ui.SURFACE).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(Glyphs.gamepad, null, Modifier.size(30.dp), colorFilter = ColorFilter.tint(Ui.TEXT3))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(stringResource(R.string.games_placeholder), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Ui.TEXT2)
            Text(stringResource(R.string.library_phase5), fontSize = 13.sp, color = Ui.TEXT3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun clear() = ClickableSurfaceDefaults.colors(
    containerColor = Color.Transparent, contentColor = Color.White,
    focusedContainerColor = Color.Transparent, focusedContentColor = Color.White,
    pressedContainerColor = Color.Transparent, pressedContentColor = Color.White,
)

/** App or game art. Focus lifts it, frames it in the accent gradient and (Full) casts a glow in its own color. */
@Composable
private fun GameCard(t: Tile, accent: Accent, lite: Boolean, req: FocusRequester, onFocus: (Tile) -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val app = t.app
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.width(248.dp)) {
        Surface(
            onClick = t.onClick,
            onLongClick = t.onLongClick,
            modifier = Modifier.size(248.dp, 140.dp)
                // clip = false: a clipping shadow would cut off the focus lift and the gradient frame
                .then(if (isFocused && !lite && app != null) Modifier.shadow(28.dp, shape, clip = false, ambientColor = Color(app.color), spotColor = Color(app.color)) else Modifier)
                .focusRequester(req)
                .onFocusChanged { isFocused = it.isFocused; if (it.isFocused) onFocus(t) }
                .pointerClick(t.onClick, t.onLongClick, req),
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = clear(),
            scale = ClickableSurfaceDefaults.scale(focusedScale = if (lite) 1.05f else 1.1f),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, accent.brush))),
        ) {
            Box(Modifier.fillMaxSize().background(app?.let { lerp(Color(it.color), Ui.BG, 0.25f) } ?: Ui.SURFACE), contentAlignment = Alignment.Center) {
                when {
                    app?.banner != null -> Image(app.banner, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    app?.icon != null -> Image(app.icon, null, Modifier.size(64.dp))
                }
            }
        }
        Text(
            t.label, fontSize = 15.sp, fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isFocused) Color.White else Ui.TEXT2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp, start = 4.dp),
        )
    }
}

/** System and Power: rounded tiles with the icon top left and the label bottom left; focus fills them with the accent gradient. */
@Composable
private fun SystemTile(t: Tile, accent: Accent, req: FocusRequester, onFocus: (Tile) -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = t.onClick,
        onLongClick = t.onLongClick,
        modifier = Modifier.size(136.dp, 112.dp).focusRequester(req)
            .onFocusChanged { isFocused = it.isFocused; if (it.isFocused) onFocus(t) }
            .pointerClick(t.onClick, t.onLongClick, req),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        colors = clear(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) {
        Column(
            Modifier.fillMaxSize().background(if (isFocused) accent.brush else SolidColor(Ui.SURFACE)).padding(14.dp),
        ) {
            t.icon?.let { Image(it, null, Modifier.size(28.dp), colorFilter = ColorFilter.tint(Color.White)) }
            Spacer(Modifier.weight(1f))
            Text(
                t.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp,
                color = if (isFocused) Color.White else Ui.TEXT2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun gamepadConnected() = InputDevice.getDeviceIds().any { id ->
    InputDevice.getDevice(id)?.let { !it.isVirtual && it.supportsSource(InputDevice.SOURCE_GAMEPAD) } == true
}

private fun networkGlyph(ctx: Context): ImageVector? {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Glyphs.ethernet
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Glyphs.wifi
        else -> null
    }
}

@Composable
private fun Panel(title: String, accent: Accent, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 80.dp, vertical = 44.dp)) {
        Text(title, fontSize = 40.sp, fontWeight = FontWeight.Bold, style = TextStyle(brush = accent.brush))
        Column(Modifier.weight(1f).padding(top = 22.dp).verticalScroll(rememberScrollState()), content = content)
        val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionPill("B", stringResource(R.string.back_hint).substringAfter(": "), null) { back?.onBackPressed() }
        }
    }
}

@Composable
private fun Line(text: String, color: Color = Color.White) {
    Text(text, color = color, fontSize = 22.sp, modifier = Modifier.padding(vertical = 6.dp))
}

/** Vertical list of pill choices, first one focused. Focus fills the pill with the accent gradient. */
@Composable
private fun Choices(accent: Accent, items: List<Pair<String, () -> Unit>>) {
    val first = remember { FocusRequester() }
    items.forEachIndexed { i, (label, action) ->
        var isFocused by remember { mutableStateOf(false) }
        Surface(
            onClick = action,
            modifier = Modifier
                .padding(vertical = 5.dp)
                .onFocusChanged { isFocused = it.isFocused }
                .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                .pointerClick(action),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
            colors = clear(),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        ) {
            Text(
                label, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                color = if (isFocused) Color.White else Ui.TEXT2,
                modifier = Modifier
                    .background(if (isFocused) accent.brush else SolidColor(Ui.SURFACE), RoundedCornerShape(50))
                    .padding(horizontal = 28.dp, vertical = 12.dp),
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
