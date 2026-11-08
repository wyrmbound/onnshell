package com.xplsh.onnshell.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
    /** The upkeep after a load (Catalog.follow); true when the list should be loaded again. */
    private val follower: suspend (installed: Set<String>) -> Boolean = { false },
    private val staleDaysNow: () -> Int = { 0 },
) {
    /** Whole days the main list has been out of reach (0 = it is current): the Store says so and goes to the projects directly. */
    var staleDays by mutableIntStateOf(0); private set
    /** Games that joined the list in the last week. */
    val newCount get() = entries.count { it.isNew() }
    private var following = false
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
            staleDays = staleDaysNow()
            // Upkeep, after the list is on screen: new and updated downloads get judged, and a list that is out of
            // reach is topped up from the games' own projects. One round at a time.
            if (error == null && !following) {
                following = true
                val again = runCatching { follower(installed.filterValues { it }.keys) }.getOrDefault(false)
                if (again) runCatching { loader(false) }.onSuccess { list -> entries = list; list.forEach { installed[it.id] = isInstalled(it.id) }; scanFiles() }
                following = false
            }
        }
    }

    /** Starts installing [e], or puts it in line behind the install that is running. */
    fun install(e: Entry): Boolean {
        if (busy == e.id || e.id in queue) return true
        if (busy != null) { queue += e.id; return true } // owner ask: a Get during another install waits its turn
        start(e)
        return true
    }

    /** Ids waiting for the running install to finish, in the order they were asked for. */
    val queue = mutableStateListOf<String>()
    /** Takes a waiting game out of the line again. */
    fun dequeue(id: String) { queue.remove(id) }

    private fun start(e: Entry) {
        busy = e.id; progress = 0f; progressText = ""
        scope.launch {
            val r = installer(e) { p, t -> progress = p; progressText = t }
            if (r.isSuccess) { installed[e.id] = true; installs++ } else error = r.exceptionOrNull()?.message ?: "install failed"
            busy = null
            // the next one in line that is still listed and not installed meanwhile; a failed install does not stop the line
            while (queue.isNotEmpty()) {
                val n = entry(queue.removeAt(0))
                if (n != null && installed[n.id] != true) { start(n); break }
            }
        }
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
            // while the list is out of reach, an install asks the game's own project for its newest release first
            installer = { e, p -> Catalog.latest(ctx, e).let { f -> GameInstaller.install(ctx, f.id, f.url, f.sha256, f.size, p) } },
            follower = { installed -> Catalog.follow(ctx, installed) },
            staleDaysNow = { Catalog.staleDays(ctx) },
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
