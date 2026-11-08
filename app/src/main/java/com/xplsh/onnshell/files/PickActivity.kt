package com.xplsh.onnshell.files

import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import com.xplsh.onnshell.R
import com.xplsh.onnshell.data.Accent
import com.xplsh.onnshell.data.Catalog
import com.xplsh.onnshell.data.GameFiles
import com.xplsh.onnshell.data.Prefs
import com.xplsh.onnshell.ui.Choices
import com.xplsh.onnshell.ui.LocalTheme
import com.xplsh.onnshell.ui.Themes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** OnnShell's own document picker (the box ships none): OPEN_DOCUMENT, GET_CONTENT and OPEN_DOCUMENT_TREE. */
class PickActivity : ComponentActivity() {
    private val tree get() = intent.action == Intent.ACTION_OPEN_DOCUMENT_TREE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val caller = callingActivity?.packageName ?: callingPackage
        val theme = Themes.byId(Prefs.themeId(this))
        val accent = if (theme === Themes.SLATE) Prefs.ACCENTS[Prefs.accentIndex(this)] else Accent(listOf(theme.accent, theme.accent))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalTheme provides theme, LocalTextStyle provides TextStyle(fontFamily = theme.body, color = theme.text)) {
                    Column(Modifier.fillMaxSize().background(theme.bg).padding(horizontal = 80.dp, vertical = 44.dp)) {
                        Text(
                            getString(if (tree) R.string.pick_folder_title else R.string.pick_file_title),
                            fontSize = 40.sp, fontWeight = FontWeight.Bold, style = TextStyle(brush = accent.brush),
                        )
                        Picker(accent, caller)
                    }
                }
            }
        }
    }

    @Composable
    private fun Picker(accent: Accent, caller: String?) {
        var dir by remember { mutableStateOf<File?>(null) } // null = "Found on this box"
        var matches by remember { mutableStateOf<List<GameFiles.Found>?>(null) } // null while searching
        var newest by remember { mutableStateOf<List<File>>(emptyList()) }
        val scope = rememberCoroutineScope()
        val roots = remember { GameFiles.roots() }
        LaunchedEffect(Unit) {
            val (n, m) = withContext(Dispatchers.IO) {
                GameFiles.grantStorage(packageName)
                val wants = caller?.let { c -> runCatching { Catalog.load(this@PickActivity) }.getOrNull()?.firstOrNull { it.pkg == c }?.wants }.orEmpty()
                val files = GameFiles.walk(GameFiles.roots())
                files.sortedByDescending { it.lastModified() }.take(50) to GameFiles.matches(files, wants)
            }
            newest = n; matches = m
        }
        fun up(d: File) = if (roots.any { it.path == d.path }) null else d.parentFile
        BackHandler { val d = dir; if (d == null) finish() else dir = up(d) }

        val items = mutableListOf<Pair<String, () -> Unit>>()
        val d = dir
        if (d == null) {
            matches?.forEach { m ->
                val label = getString(R.string.pick_match, m.entry?.let { "${m.file.name} > $it" } ?: m.file.name)
                items += label to {
                    if (tree) dir = m.file.parentFile
                    else if (m.entry == null) pick(m.file)
                    else scope.launch {
                        withContext(Dispatchers.IO) { runCatching { GameFiles.extract(m.file, m.entry, File(cacheDir, "picked")) }.getOrNull() }?.let { pick(it) }
                    }
                }
            }
            newest.forEach { f -> items += "${f.name}  ·  ${f.parent}" to { if (tree) dir = f.parentFile else pick(f) } }
            roots.forEach { r -> items += getString(R.string.pick_browse, if (r.path == "/storage/emulated/0") getString(R.string.pick_internal) else r.name) to { dir = r } }
        } else {
            if (tree) items += getString(R.string.pick_use_folder) to { pick(d) }
            items += getString(R.string.pick_up) to { dir = up(d) }
            // shortcut: first 300 entries only, folders first; add paging if a real folder needs more
            val kids = d.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })).take(300)
            kids.forEach { f ->
                if (f.isDirectory) items += "${f.name}/" to { dir = f }
                else if (!tree) items += f.name to { pick(f) }
            }
        }
        Column(Modifier.padding(top = 22.dp).verticalScroll(rememberScrollState())) {
            if (d == null) Text(getString(if (matches == null) R.string.pick_searching else R.string.pick_found), fontSize = 22.sp, modifier = Modifier.padding(vertical = 6.dp))
            else Text(d.path, fontSize = 22.sp, color = LocalTheme.current.text2, modifier = Modifier.padding(vertical = 6.dp))
            // Fresh Choices per view so the first item takes focus again
            key(d, matches) { Choices(accent, items) }
        }
    }

    private fun pick(f: File) {
        val auth = "$packageName.files"
        val uri = if (tree) DocumentsContract.buildTreeDocumentUri(auth, f.path) else DocumentsContract.buildDocumentUri(auth, f.path)
        var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        if (tree) flags = flags or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        setResult(RESULT_OK, Intent().setData(uri).addFlags(flags))
        finish()
    }

    /** Gamepad: A selects, B goes back (up a folder, or cancel at the top). */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val mapped = when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_DPAD_CENTER
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BACK
            else -> return super.dispatchKeyEvent(e)
        }
        return super.dispatchKeyEvent(
            KeyEvent(e.downTime, e.eventTime, e.action, mapped, e.repeatCount, e.metaState, e.deviceId, e.scanCode, e.flags, e.source)
        )
    }
}
