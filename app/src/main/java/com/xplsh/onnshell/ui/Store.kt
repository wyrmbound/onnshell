package com.xplsh.onnshell.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.xplsh.onnshell.R
import com.xplsh.onnshell.data.Accent
import com.xplsh.onnshell.data.Entry
import com.xplsh.onnshell.data.Filter
import com.xplsh.onnshell.data.Rating
import com.xplsh.onnshell.data.StoreState
import kotlinx.coroutines.delay

/** Semantic rating colors: the same in every theme, separate from the accent. */
private fun Rating.color() = when (this) {
    Rating.EXCELLENT -> Color(0xFF5FD38D); Rating.PLAYABLE -> Color(0xFF6CB4FF)
    Rating.MEDIUM -> Color(0xFFF0B640); Rating.NOT_RECOMMENDED -> Color(0xFFFF6B5E)
}

@Composable
private fun Rating.label(estimated: Boolean): String {
    val base = stringResource(when (this) {
        Rating.EXCELLENT -> R.string.rating_excellent; Rating.PLAYABLE -> R.string.rating_playable
        Rating.MEDIUM -> R.string.rating_medium; Rating.NOT_RECOMMENDED -> R.string.rating_not_recommended
    })
    return if (estimated) stringResource(R.string.rating_est, base) else base
}

private fun mb(size: Long) = "%d MB".format((size + 524288) / 1048576)

/**
 * The Store tab. Slate and Ink: a grid of square covers with a detail panel (Slate) or a toast line (Ink).
 * Mono: a ledger, one game per line. [onGet] decides about the warning and starts the install; [onOpen] launches an installed game.
 */
@Composable
fun Store(state: StoreState, accent: Accent, lite: Boolean, onGet: (Entry) -> Unit, onOpen: (Entry) -> Unit, onSendFiles: () -> Unit = {}) {
    val theme = LocalTheme.current
    var focused by remember { mutableStateOf<Entry?>(null) }
    LaunchedEffect(Unit) { if (state.entries.isEmpty()) state.refresh() else state.scanFiles() } // files may have arrived since
    // A search is for now: more than 10 s away from the Store and it starts clean (owner ask)
    LaunchedEffect(Unit) { if (state.leftAt > 0 && android.os.SystemClock.uptimeMillis() - state.leftAt > 10_000) state.query = "" }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { state.leftAt = android.os.SystemClock.uptimeMillis() } }
    // The panel follows the search: when the focused game is filtered out, show the first match instead of a stale one
    LaunchedEffect(state.query, state.filter, state.entries.size) { if (focused == null || state.visible.none { it.id == focused?.id }) focused = state.visible.firstOrNull() }
    // Start on the last game, else the first (else nothing is focused, the panel is empty and the first pad press has no anchor)
    LaunchedEffect(state.entries.isEmpty()) {
        if (state.focusRequest == null) state.focusRequest = state.last?.takeIf { id -> state.visible.any { it.id == id } } ?: state.visible.firstOrNull()?.id
    }
    val edge = if (theme.tabs == Tabs.LEFT_RAIL) 28.dp else 56.dp
    Column(Modifier.fillMaxSize().padding(start = edge, end = edge, top = 24.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            // The count sits under the title: beside it, the row ran out of room and squeezed the last pill into a circle
            Column(Modifier.weight(1f)) { // takes what the pills leave; its second line is cut, never the pills
                Text(stringResource(R.string.store_title), fontFamily = theme.display, fontSize = if (theme.grid == Grid.STRIPS) 48.sp else 40.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 48.sp)
                val count = stringResource(R.string.store_runs_here, state.visible.size) +
                    (if (state.staleDays > 0) " · " + stringResource(R.string.store_stale, state.staleDays)
                     else if (state.newCount > 0) " · " + stringResource(R.string.store_new_week, state.newCount) else "")
                Text(count, fontSize = 13.sp, color = theme.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 10.dp, end = 12.dp))
            }
            SearchPill(state, accent, lite, Modifier.padding(bottom = 10.dp, end = 8.dp))
            Box(Modifier.padding(bottom = 10.dp, end = 8.dp)) { TabPill(stringResource(R.string.store_send_files), false, accent, lite) { onSendFiles() } }
            Row(Modifier.padding(bottom = 10.dp)) {
                listOf(Filter.ALL to R.string.store_filter_all, Filter.EXCELLENT to R.string.store_filter_excellent,
                       Filter.PLAYABLE to R.string.store_filter_playable, Filter.INSTALLED to R.string.store_filter_installed).forEach { (f, res) ->
                    TabPill(stringResource(res), state.filter == f, accent, lite) { state.filter = f }
                }
            }
        }
        if (theme.grid == Grid.STRIPS) Box(Modifier.fillMaxWidth().height(2.dp).background(theme.text))
        Spacer(Modifier.height(16.dp))
        val err = state.error
        when {
            state.entries.isEmpty() && state.loading -> Text(stringResource(R.string.store_loading), fontSize = 22.sp, color = theme.text2)
            state.entries.isEmpty() && err != null -> Column {
                Text(stringResource(R.string.store_offline), fontSize = 24.sp, color = theme.text)
                Text(err, fontSize = 16.sp, color = theme.text3, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
                Choices(accent, listOf(stringResource(R.string.store_retry) to { state.refresh(force = true) }))
            }
            state.visible.isEmpty() && state.query.isNotBlank() -> Text(stringResource(R.string.store_search_none, state.query), fontSize = 22.sp, color = theme.text2)
            theme.grid == Grid.STRIPS -> Ledger(state, accent, lite, onGet, onOpen)
            else -> Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    Grid(state, accent, lite, onFocus = { focused = it; state.last = it.id },onGet = onGet, onOpen = onOpen)
                }
                if (theme.tabs == Tabs.TOP_PILLS) Detail(focused, state, accent, lite)
            }
        }
    }
    if (theme.grid == Grid.BUBBLES && focused != null) Toast(focused!!, state, Modifier.padding(start = edge, bottom = 24.dp))
}

@Composable
private fun Grid(state: StoreState, accent: Accent, lite: Boolean, onFocus: (Entry) -> Unit, onGet: (Entry) -> Unit, onOpen: (Entry) -> Unit) {
    val theme = LocalTheme.current
    val tile = if (theme.grid == Grid.BUBBLES) 145.dp else 150.dp
    val reqs = remember { mutableMapOf<String, androidx.compose.ui.focus.FocusRequester>() }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // Only composed tiles have a requester: scroll the target into view first, give it a frame, then focus it
    LaunchedEffect(state.focusRequest) {
        state.focusRequest?.let { id ->
            val ix = state.visible.indexOfFirst { it.id == id }
            if (ix >= 0) { gridState.scrollToItem(ix); delay(50); runCatching { reqs[id]?.requestFocus() } }
            state.focusRequest = null
        }
    }
    LazyVerticalGrid(
        GridCells.Adaptive(tile), state = gridState, contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(state.visible, key = { _, e -> e.id }) { i, e ->
            StoreTile(e, i, state, accent, lite, tile, reqs.getOrPut(e.id) { androidx.compose.ui.focus.FocusRequester() }, onFocus, onGet, onOpen)
        }
    }
}

@Composable
private fun StoreTile(e: Entry, index: Int, state: StoreState, accent: Accent, lite: Boolean, size: androidx.compose.ui.unit.Dp,
                      req: androidx.compose.ui.focus.FocusRequester, onFocus: (Entry) -> Unit, onGet: (Entry) -> Unit, onOpen: (Entry) -> Unit) {
    val theme = LocalTheme.current
    val shape = RoundedCornerShape(theme.tileRadius)
    val installed = state.installed[e.id] == true
    val busy = state.busy == e.id
    var isFocused by remember { mutableStateOf(false) }
    // First appearance: fade and rise, staggered by position (off in Lite)
    var shown by remember { mutableStateOf(lite) }
    LaunchedEffect(Unit) { if (!shown) { delay((index % 10) * 20L); shown = true } }
    val appear by animateFloatAsState(if (shown) 1f else 0f, tween(200), label = "appear")
    val queued = e.id in state.queue
    val act = { if (installed) onOpen(e) else if (queued) state.dequeue(e.id) else if (!busy) onGet(e) }
    Box(contentAlignment = Alignment.Center) {
    if (isFocused && !lite && theme.focus == Focus.GLOW) Box(Modifier.size(size + 40.dp).background(Brush.radialGradient(listOf(accent.main.copy(alpha = 0.55f), Color.Transparent))))
    Surface(
        onClick = act,
        modifier = Modifier.size(size)
            .graphicsLayer { alpha = appear; translationY = (1f - appear) * 40f }
            .focusRequester(req)
            .onFocusChanged { isFocused = it.isFocused; if (it.isFocused) onFocus(e) }
            .pointerClick(act),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = clear(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (lite) 1.04f else 1.08f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(if (theme.focus == Focus.GLOW) 8.dp else 5.dp, if (theme.focus == Focus.RING) theme.text else accent.main), shape = shape)),
    ) {
        Box(Modifier.fillMaxSize().background(theme.surface)) {
            Cover(e.covers, Modifier.fillMaxSize())
            if (theme.grid == Grid.BUBBLES) {
                // Ink: the rating is a ring around the cover; Installed is a badge
                Box(Modifier.fillMaxSize().background(Color.Transparent).padding(0.dp)) {
                    Box(Modifier.fillMaxSize().border(if (installed) 0.dp else 5.dp, e.rating.color(), shape))
                }
                if (installed) Text(stringResource(R.string.store_installed), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = accent.main,
                    modifier = Modifier.padding(10.dp).background(theme.bg.copy(alpha = 0.75f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp))
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, theme.bg.copy(alpha = 0.92f)))).padding(start = 10.dp + theme.tileRadius / 2, end = 10.dp, top = 22.dp, bottom = 8.dp)) {
                Text(e.name, fontFamily = theme.display, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = theme.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (theme.grid != Grid.BUBBLES) Row(Modifier.padding(top = 4.dp)) {
                    if (busy) Text(stringResource(R.string.store_installing), fontSize = 11.sp, color = accent.main)
                    else if (queued) RatingChip(stringResource(R.string.store_queued), accent.main)
                    else if (installed) RatingChip(stringResource(R.string.store_installed), accent.main)
                    else RatingChip(e.rating.label(e.estimated), e.rating.color())
                }
                if (busy) Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).background(theme.surfaceHi, RoundedCornerShape(50))) {
                    Box(Modifier.fillMaxWidth(state.progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(accent.main, RoundedCornerShape(50)))
                }
            }
        }
    }
    }
}

/** Tries each art URL in turn; a failed load (logged under tag Store) falls through to the next, then to the surface color. */
@Composable
private fun Cover(urls: List<String>, modifier: Modifier) {
    val theme = LocalTheme.current
    var i by remember(urls) { mutableStateOf(0) }
    val url = urls.getOrNull(i)
    if (url == null) { Box(modifier.background(theme.surfaceHi)); return }
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(250).build(),
        contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop,
        onError = { android.util.Log.w("Store", "cover failed: $url: ${it.result.throwable}"); i++ },
    )
}

@Composable
private fun RatingChip(text: String, color: Color) {
    val theme = LocalTheme.current
    Text(
        text.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = theme.bg,
        modifier = Modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/** Slate: the panel on the right that explains the focused game before Get. */
@Composable
private fun Detail(e: Entry?, state: StoreState, accent: Accent, lite: Boolean) {
    val theme = LocalTheme.current
    Crossfade(e, animationSpec = tween(if (lite) 0 else 250), label = "detail") { entry ->
        Column(Modifier.padding(start = 16.dp, bottom = 80.dp).width(270.dp).fillMaxHeight().background(theme.surface.copy(alpha = 0.9f), RoundedCornerShape(12.dp)).padding(18.dp)) {
            if (entry == null) return@Crossfade
            // The text takes the room that is left and is cut there; the Get pill below always keeps its full height
            // (a long "Needs" list used to squeeze it flat).
            Column(Modifier.weight(1f).clipToBounds()) {
                Text(entry.name, fontFamily = theme.display, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 28.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(meta(entry, state), fontSize = 13.sp, color = theme.text3, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
                Text(entry.description.ifEmpty { stringResource(R.string.store_no_description) }, fontSize = 14.sp, lineHeight = 20.sp, color = theme.text2, maxLines = if (entry.requires.isEmpty()) 8 else 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.store_rating), fontSize = 13.sp, color = theme.text2, modifier = Modifier.padding(end = 8.dp))
                    RatingChip(entry.rating.label(entry.estimated), entry.rating.color())
                }
                if (entry.reports > 0) Text(stringResource(R.string.store_reports, entry.reports), fontSize = 12.sp, color = theme.text3, modifier = Modifier.padding(top = 4.dp))
                if (entry.requires.isNotEmpty()) {
                    Text(stringResource(R.string.store_needs), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = theme.text2, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                    // capped at 5 lines so the Get pill stays whole (7 squeezed it under a two-line meta); the Get warning shows every line in full
                    // one line each, label in bold; the long fingerprint stays for the Get warning, which shows everything
                    Text(needsText(entry.requires.filterNot { it.startsWith("Fingerprint:") }), fontSize = 12.sp, lineHeight = 16.sp, color = theme.text2, maxLines = 5, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(8.dp))
            val installed = state.installed[entry.id] == true
            val label = when { state.busy == entry.id -> stringResource(R.string.store_installing); entry.id in state.queue -> stringResource(R.string.store_queued_cancel); installed -> stringResource(R.string.store_open); else -> stringResource(R.string.store_get) }
            ActionPill("A", label, accent.brush) { }
            if (!installed && entry.rating.ordinal >= Rating.MEDIUM.ordinal) Text(
                stringResource(R.string.store_warn_medium, entry.rating.label(false)), fontSize = 12.sp, lineHeight = 17.sp, color = Rating.MEDIUM.color(),
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** Ink: one line under the grid for the focused game. */
@Composable
private fun Toast(e: Entry, state: StoreState, modifier: Modifier) {
    val theme = LocalTheme.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        Column(modifier.width(400.dp).background(theme.surface, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(e.name, fontFamily = theme.display, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Rating.MEDIUM.color().takeIf { e.rating.ordinal >= Rating.MEDIUM.ordinal } ?: theme.text)
            Text(meta(e, state) + " · " + e.rating.label(e.estimated) + (if (state.installed[e.id] == true) " · " + stringResource(R.string.store_installed) else ""), fontSize = 13.sp, color = theme.text2, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun meta(e: Entry, state: StoreState): String {
    val parts = mutableListOf(e.type, e.engine, mb(e.size))
    if (e.isNew()) parts.add(0, stringResource(R.string.store_new)) else if (e.isUpdated()) parts.add(0, stringResource(R.string.store_updated))
    if (e.source == "github") parts += stringResource(R.string.store_from_github)
    when (state.files[e.id]) {
        com.xplsh.onnshell.data.GameFiles.Status.READY -> parts += stringResource(R.string.store_files_ready)
        com.xplsh.onnshell.data.GameFiles.Status.WRONG -> parts += stringResource(R.string.store_files_wrong)
        com.xplsh.onnshell.data.GameFiles.Status.MISSING -> parts += stringResource(R.string.store_needs_files)
        com.xplsh.onnshell.data.GameFiles.Status.NONE -> {}
        null -> if (e.needsFiles) parts += stringResource(R.string.store_needs_files)
    }
    return parts.joinToString(" · ")
}

/** Mono: the ledger, one game per line, which reads ratings and sizes across many games. */
@Composable
private fun Ledger(state: StoreState, accent: Accent, lite: Boolean, onGet: (Entry) -> Unit, onOpen: (Entry) -> Unit) {
    val theme = LocalTheme.current
    val reqs = remember { mutableMapOf<String, androidx.compose.ui.focus.FocusRequester>() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(state.focusRequest) {
        state.focusRequest?.let { id ->
            val ix = state.visible.indexOfFirst { it.id == id }
            if (ix >= 0) { listState.scrollToItem(ix); delay(50); runCatching { reqs[id]?.requestFocus() } }
            state.focusRequest = null
        }
    }
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 120.dp)) {
        itemsIndexed(state.visible, key = { _, e -> e.id }) { _, e ->
            val req = reqs.getOrPut(e.id) { androidx.compose.ui.focus.FocusRequester() }
            val installed = state.installed[e.id] == true
            val busy = state.busy == e.id
            var isFocused by remember { mutableStateOf(false) }
            val queued = e.id in state.queue
            val act = { if (installed) onOpen(e) else if (queued) state.dequeue(e.id) else if (!busy) onGet(e) }
            Surface(
                onClick = act,
                modifier = Modifier.fillMaxWidth().focusRequester(req).onFocusChanged { isFocused = it.isFocused }.pointerClick(act),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
                colors = clear(),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                Column(Modifier.background(if (isFocused) theme.surface else Color.Transparent)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(e.covers, Modifier.size(72.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                            Text(e.name.uppercase(), fontFamily = theme.display, fontSize = 26.sp, lineHeight = 26.sp, color = if (isFocused) accent.main else theme.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(meta(e, state), fontSize = 13.sp, color = theme.text2, modifier = Modifier.padding(top = 3.dp))
                            if (busy) Box(Modifier.padding(top = 6.dp).width(240.dp).height(4.dp).background(theme.surfaceHi)) {
                                Box(Modifier.fillMaxWidth(state.progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(accent.main))
                            }
                        }
                        Box(Modifier.width(130.dp)) { RatingChip(e.rating.label(e.estimated), e.rating.color()) }
                        Text(mb(e.size), fontSize = 15.sp, modifier = Modifier.width(80.dp))
                        val label = when { busy -> stringResource(R.string.store_installing); queued -> stringResource(R.string.store_queued_cancel); installed -> stringResource(R.string.store_open); else -> stringResource(R.string.store_get) }
                        Text(
                            label.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp,
                            color = if (isFocused) theme.bg else theme.text,
                            modifier = Modifier.background(if (isFocused) accent.main else Color.Transparent).border(2.dp, if (isFocused) accent.main else theme.text).padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(theme.text3.copy(alpha = 0.5f)))
                }
            }
        }
    }
}

/**
 * Store search: a pill until pressed, then a text field with the TV keyboard, so moving across the header with the
 * D-pad never pops the keyboard. Search on the keyboard, or leaving the field, closes it; the query stays as the pill's label.
 */
@Composable
private fun SearchPill(state: StoreState, accent: Accent, lite: Boolean, modifier: Modifier) {
    val theme = LocalTheme.current
    var editing by remember { mutableStateOf(false) }
    if (!editing) {
        val label = state.query.ifBlank { stringResource(R.string.store_search) }
        Box(modifier) { TabPill(label, state.query.isNotBlank(), accent, lite) { editing = true } }
        return
    }
    val req = remember { androidx.compose.ui.focus.FocusRequester() }
    // B while typing closes the keyboard and stays in the Store; the Shell's own BACK (to HOME) only when not typing.
    // This handler is composed inside the Shell's, so it wins while the field is open.
    androidx.activity.compose.BackHandler(enabled = true) { editing = false }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var hadFocus by remember { mutableStateOf(false) }
    androidx.compose.foundation.text.BasicTextField(
        value = state.query,
        onValueChange = { state.query = it },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = theme.text, fontSize = 16.sp, fontFamily = theme.body),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(accent.main),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { editing = false; focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }),
        modifier = modifier.width(200.dp).focusRequester(req)
            .onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus) editing = false },
        decorationBox = { inner ->
            Row(Modifier.background(theme.surface, RoundedCornerShape(50)).border(2.dp, accent.main, RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box { if (state.query.isEmpty()) Text(stringResource(R.string.store_search), fontSize = 16.sp, color = theme.text3); inner() }
            }
        },
    )
    LaunchedEffect(Unit) { runCatching { req.requestFocus() } }
}

/** Requirement lines as "Label: value" with the label in bold, one per line. */
private fun needsText(lines: List<String>) = androidx.compose.ui.text.buildAnnotatedString {
    lines.forEachIndexed { i, l ->
        if (i > 0) append("\n")
        val cut = l.indexOf(": ")
        if (cut in 1..18) {
            pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)); append(l.substring(0, cut + 1)); pop()
            append(l.substring(cut + 1))
        } else append(l)
    }
}
