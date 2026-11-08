package com.xplsh.onnshell.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class Filter { ALL, EXCELLENT, PLAYABLE, INSTALLED }

/**
 * The Store tab's state: the listable catalog, one install at a time with progress, and what is installed.
 * The three lambdas keep Android out of it, so the rules are unit-tested; [forApp] wires the real ones.
 */
class StoreState(
    private val scope: CoroutineScope,
    private val loader: suspend (force: Boolean) -> List<Entry>,
    private val isInstalled: (id: String) -> Boolean,
    private val installer: suspend (Entry, onProgress: (Float, String) -> Unit) -> Result<Unit>,
    private val scanner: suspend (List<Entry>) -> Map<String, GameFiles.Status> = { emptyMap() },
) {
    var entries by mutableStateOf<List<Entry>>(emptyList()); private set
    var error by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(false); private set
    var filter by mutableStateOf(Filter.ALL)
    /** Store search: matches the name or id, ignoring case, spaces and punctuation. */
    var query by mutableStateOf("")
    /** When the Store tab was last left (uptime ms), so an old search can be dropped on return. */
    var leftAt = 0L
    /** Id being installed, or null. */
    var busy by mutableStateOf<String?>(null); private set
    var progress by mutableStateOf(0f); private set
    var progressText by mutableStateOf(""); private set
    val installed = mutableStateMapOf<String, Boolean>()
    /** Bumped after every successful install, so the home screen reloads its Games row. */
    var installs by mutableIntStateOf(0); private set
    /** Test hooks (CommandReceiver STORE_OPEN / STORE_FOCUS): the shell opens the Store, the Store focuses this id. */
    var openRequest by mutableStateOf(false)
    var focusRequest by mutableStateOf<String?>(null)
    /** Test hook (CommandReceiver OPEN_GAME): HOME opens this 64-bit game through the same path as its tile. */
    var launchRequest by mutableStateOf<String?>(null)
    /** Last focused id, so coming back from a warning or a game lands on the same tile. */
    var last: String? = null

    fun entry(id: String) = entries.firstOrNull { it.id == id }

    /** Whether each game's files are on the box (absent until the first look finishes). */
    val files = mutableStateMapOf<String, GameFiles.Status>()
    private var scanning = false
    private var rescan = false

    /** Looks over the drives again (new uploads, a USB stick); one look at a time, a request during one runs once after it. */
    fun scanFiles() {
        if (entries.isEmpty()) return
        if (scanning) { rescan = true; return }
        scanning = true
        scope.launch {
            do {
                rescan = false
                runCatching { scanner(entries) }.onSuccess { files.putAll(it) }
            } while (rescan)
            scanning = false
        }
    }

    fun refresh(force: Boolean = false) {
        if (loading) return
        loading = true
        scope.launch {
            runCatching { loader(force) }
                .onSuccess { list -> entries = list; error = null; list.forEach { installed[it.id] = isInstalled(it.id) }; scanFiles() }
                .onFailure { error = it.message ?: it.toString() }
            loading = false
        }
    }

    /** Starts installing [e]; false (and nothing happens) while another install runs. */
    fun install(e: Entry): Boolean {
        if (busy != null) return false
        busy = e.id; progress = 0f; progressText = ""
        scope.launch {
            val r = installer(e) { p, t -> progress = p; progressText = t }
            if (r.isSuccess) { installed[e.id] = true; installs++ } else error = r.exceptionOrNull()?.message ?: "install failed"
            busy = null
        }
        return true
    }

    /** After the search and the filter; installed first, then by rating, then by name. */
    val visible: List<Entry>
        get() = entries.filter { matches(it, query) }.filter {
            when (filter) {
                Filter.ALL -> true
                Filter.EXCELLENT -> it.rating == Rating.EXCELLENT
                Filter.PLAYABLE -> it.rating.ordinal <= Rating.PLAYABLE.ordinal
                Filter.INSTALLED -> installed[it.id] == true
            }
        }.sortedWith(compareByDescending<Entry> { installed[it.id] == true }.thenBy { it.rating.ordinal }.thenBy { it.name.lowercase() })

    companion object {
        private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        fun matches(e: Entry, query: String): Boolean {
            val q = norm(query)
            return q.isEmpty() || q in norm(e.name) || q in norm(e.id)
        }
        /** The home screen's instance, for the ADB test hooks. */
        @Volatile var current: StoreState? = null
        fun forApp(ctx: Context, scope: CoroutineScope) = StoreState(
            scope,
            loader = { force -> Catalog.load(ctx, force) },
            isInstalled = { id -> GameInstaller.installed(ctx, id, current?.entry(id)?.pkg) }, // entries are set just before this runs
            installer = { e, p -> GameInstaller.install(ctx, e.id, e.url, e.sha256, e.size, p) },
            scanner = { list ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    GameFiles.grantStorage(ctx.packageName)
                    val all = GameFiles.walk(GameFiles.roots())
                    val idx = GameFiles.index(all, GameFiles.hashExts(list.flatMap { it.wants }))
                    list.associate { e ->
                        val pick = Requirements.forId(ctx, e.id)?.pick
                        e.id to GameFiles.status(e.wants, e.needsFiles, idx, pick?.let { Requirements.present(it) },
                            pick?.let { Requirements.candidates(it, all).isNotEmpty() } ?: false)
                    }
                }
            },
        ).also { current = it }
    }
}
