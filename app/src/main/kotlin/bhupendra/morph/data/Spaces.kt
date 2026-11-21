package bhupendra.morph.data

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import bhupendra.morph.core.agent.Risk
import bhupendra.morph.core.agent.Tool
import bhupendra.morph.core.agent.ToolOutcome
import bhupendra.morph.core.agent.ToolSpec
import bhupendra.morph.core.agent.schema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/** Something you arrange on the Spaces board. */
sealed interface Tile {
    val key: String

    /** A real Android widget, hosted by Morph, laid out on a grid at its designed size. */
    data class Widget(val appWidgetId: Int, override val key: String = "w$appWidgetId") : Tile

    /**
     * Something Morph keeps track of for you. Never a frozen picture: a Watch re-checks itself, a Saved item opens
     * what it points to. Every button on it is wired to something real.
     */
    data class Kept(
        override val key: String = "k" + UUID.randomUUID().toString().take(12),
        val kind: Kind,
        val title: String,
        val icon: String = if (kind == Kind.Watch) "👀" else "🔖",
        /** One line: why it's kept / what to look for. */
        val note: String = "",
        /** Saved: what it opens. */
        val url: String? = null,
        /** Saved: when it's due (epoch ms), for a countdown. */
        val deadline: Long? = null,
        /** Watch: the question re-asked on each check, phrased so it stands alone. */
        val prompt: String? = null,
        val everyMs: Long = 24 * HOUR,
        val lastChecked: Long = 0L,
        /** Watch: the latest answer (one or two lines). */
        val lastResult: String? = null,
        /** Watch: when the answer last actually changed. */
        val lastChange: Long = 0L,
        val createdAt: Long = System.currentTimeMillis(),
    ) : Tile {
        enum class Kind { Watch, Saved }
    }

    companion object { const val HOUR = 60 * 60_000L }
}

/**
 * The Spaces board: what Morph keeps track of for you, plus your widgets. The live sections (what's running, who's
 * waiting on you) are derived elsewhere; here are the things you arrange — kept items and widgets — in your order.
 */
class Spaces(private val context: Context, private val scope: CoroutineScope) {
    val host = AppWidgetHost(context, HOST_ID)
    val widgets: AppWidgetManager = AppWidgetManager.getInstance(context)
    private val file = File(context.filesDir, "spaces.json")

    /** Your board, top to bottom. Mutate on the main thread. */
    val tiles = mutableStateListOf<Tile>()

    /**
     * Runs one watch check: (question, previous answer) → the agent's reply, which starts with "CHANGED:" or
     * "SAME:". Wired by the app graph (it needs the agent); null until then.
     */
    var checker: (suspend (prompt: String, previous: String?) -> String?)? = null
    /** Called when a watch's answer really changed (notify the user). */
    var onChanged: ((Tile.Kept) -> Unit)? = null
    private val checkLock = Mutex()
    private val _checking = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** The watch being checked right now (for a spinner). */
    val checking: kotlinx.coroutines.flow.StateFlow<String?> = _checking

    init {
        runCatching { if (file.exists()) tiles.addAll(decode(file.readText())) }
        tiles.removeAll { it is Tile.Widget && widgets.getAppWidgetInfo(it.appWidgetId) == null }
        @OptIn(FlowPreview::class)
        scope.launch(Dispatchers.Main) {
            androidx.compose.runtime.snapshotFlow { tiles.toList() }.drop(1).debounce(300).collect { list ->
                val text = encode(list)
                withContext(Dispatchers.IO) { runCatching { file.writeText(text) } }
            }
        }
        // Watches re-check themselves while Morph is alive (it's the home screen, so that's most of the time).
        scope.launch { while (true) { delay(15 * 60_000L); checkDue() } }
    }

    // ---- Arranging ------------------------------------------------------------------------------------

    fun addWidget(appWidgetId: Int) { if (tiles.none { it is Tile.Widget && it.appWidgetId == appWidgetId }) tiles.add(0, Tile.Widget(appWidgetId)) }

    fun keep(item: Tile.Kept) {
        // Same title again = an update, not a duplicate.
        val i = tiles.indexOfFirst { it is Tile.Kept && it.title.equals(item.title, ignoreCase = true) }
        if (i >= 0) tiles[i] = item.copy(key = tiles[i].key) else tiles.add(0, item)
        if (item.kind == Tile.Kept.Kind.Watch && item.lastResult == null) scope.launch { check(item.key) }
    }

    fun remove(tile: Tile) {
        tiles.remove(tile)
        if (tile is Tile.Widget) runCatching { host.deleteAppWidgetId(tile.appWidgetId) }
    }

    fun move(from: Int, to: Int) {
        if (from == to || from !in tiles.indices || to !in tiles.indices) return
        tiles.add(to, tiles.removeAt(from))
    }

    // ---- Watching -------------------------------------------------------------------------------------

    /** Check every watch that's due. Cheap to call often (on resume, on a timer). */
    suspend fun checkDue() {
        val now = System.currentTimeMillis()
        val due = withContext(Dispatchers.Main) {
            tiles.filterIsInstance<Tile.Kept>().filter { it.kind == Tile.Kept.Kind.Watch && now - it.lastChecked >= it.everyMs }
        }
        due.forEach { check(it.key) }
    }

    /** Re-check one watch now ("Check now", or right after it's created). */
    suspend fun check(key: String) = checkLock.withLock {
        val item = withContext(Dispatchers.Main) { tiles.firstOrNull { it.key == key } as? Tile.Kept } ?: return@withLock
        val prompt = item.prompt ?: return@withLock
        val run = checker ?: return@withLock
        _checking.value = key
        val reply = try { runCatching { run(prompt, item.lastResult) }.getOrNull() } finally { _checking.value = null }
        val now = System.currentTimeMillis()
        withContext(Dispatchers.Main) {
            val i = tiles.indexOfFirst { it.key == key }
            if (i < 0) return@withContext
            val cur = tiles[i] as Tile.Kept
            if (reply.isNullOrBlank()) { tiles[i] = cur.copy(lastChecked = now); return@withContext }
            val changed = reply.trimStart().startsWith("CHANGED", ignoreCase = true)
            val text = reply.trim().removePrefix("CHANGED:").removePrefix("SAME:").removePrefix("CHANGED").removePrefix("SAME").trim()
            val first = cur.lastResult == null
            val updated = cur.copy(lastChecked = now, lastResult = text.take(280), lastChange = if (changed || first) now else cur.lastChange)
            tiles[i] = updated
            if (changed && !first) onChanged?.invoke(updated)
        }
    }

    // ---- Storage --------------------------------------------------------------------------------------

    private fun encode(list: List<Tile>): String = JsonArray(list.map { t ->
        when (t) {
            is Tile.Widget -> buildJsonObject { put("t", "widget"); put("id", t.appWidgetId) }
            is Tile.Kept -> buildJsonObject {
                put("t", "kept"); put("key", t.key); put("kind", t.kind.name); put("title", t.title); put("icon", t.icon)
                put("note", t.note); t.url?.let { put("url", it) }; t.deadline?.let { put("deadline", it) }
                t.prompt?.let { put("prompt", it) }; put("every", t.everyMs); put("checked", t.lastChecked)
                t.lastResult?.let { put("result", it) }; put("changed", t.lastChange); put("created", t.createdAt)
            }
        }
    }).toString()

    private fun decode(s: String): List<Tile> = (Json.parseToJsonElement(s) as JsonArray).mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        fun num(k: String) = (o[k] as? JsonPrimitive)?.longOrNull
        when (str("t")) {
            "widget" -> (o["id"] as? JsonPrimitive)?.intOrNull?.let { Tile.Widget(it) }
            "kept" -> Tile.Kept(
                key = str("key") ?: return@mapNotNull null,
                kind = runCatching { Tile.Kept.Kind.valueOf(str("kind")!!) }.getOrDefault(Tile.Kept.Kind.Saved),
                title = str("title") ?: return@mapNotNull null, icon = str("icon") ?: "🔖", note = str("note").orEmpty(),
                url = str("url"), deadline = num("deadline"), prompt = str("prompt"), everyMs = num("every") ?: (24 * Tile.HOUR),
                lastChecked = num("checked") ?: 0L, lastResult = str("result"), lastChange = num("changed") ?: 0L,
                createdAt = num("created") ?: System.currentTimeMillis(),
            )
            else -> null   // V1.1 "pinned card" snapshots are retired: they were pictures, not things kept track of
        }
    }

    companion object { const val HOST_ID = 0x4D4F }   // "MO"
}

/**
 * keep_track: the agent puts something on the Spaces board when it promises to keep an eye on it or the user wants
 * it kept — a Watch (re-checked on a schedule, alerts on a real change) or a Saved item (a link/thing to come back
 * to, optionally with a deadline).
 */
class KeepTrackTool(private val spaces: Spaces) : Tool {
    override val spec = ToolSpec(
        name = "keep_track",
        label = "Keeping track",
        risk = Risk.Act,
        description = "Put something on the user's Spaces board so it isn't lost. kind 'watch' = Morph re-checks it on a " +
            "schedule and tells the user when it really changes (prices, releases, news on a topic, a status). kind 'saved' " +
            "= a link or thing to come back to (forms, articles, tickets), optionally with a deadline. Use whenever you offer " +
            "or promise to keep an eye on something / remind about it, or the user asks to save or track it. Never say you'll " +
            "watch something without calling this.",
        parameters = schema(listOf("kind", "title")) {
            string("kind", "watch or saved", listOf("watch", "saved"))
            string("title", "Short title, e.g. 'Patriot Viper 16GB DDR4 price'")
            string("note", "One line: why it's kept / what matters")
            string("icon", "One emoji")
            string("url", "saved: the link it opens")
            string("deadline", "saved: due date, ISO yyyy-MM-dd or yyyy-MM-ddTHH:mm")
            string("question", "watch: the question to re-check, standalone, e.g. 'Current price of Patriot Viper Steel 16GB (2x8) DDR4-3200 in India'")
            integer("every_hours", "watch: how often to check in hours (prices 6–12, news 24, slow things 72)")
        },
    )

    override suspend fun run(args: JsonObject): ToolOutcome {
        fun s(k: String) = (args[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val kind = if (s("kind") == "watch") Tile.Kept.Kind.Watch else Tile.Kept.Kind.Saved
        val title = s("title") ?: return ToolOutcome("Error: title required")
        if (kind == Tile.Kept.Kind.Watch && s("question") == null) return ToolOutcome("Error: a watch needs 'question'")
        val deadline = s("deadline")?.let { d ->
            runCatching {
                if ('T' in d) java.time.LocalDateTime.parse(d).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                else java.time.LocalDate.parse(d).atTime(23, 59).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()
        }
        val hours = (args["every_hours"] as? JsonPrimitive)?.doubleOrNull?.coerceIn(1.0, 24.0 * 14) ?: 24.0
        val item = Tile.Kept(
            kind = kind, title = title, note = s("note").orEmpty(),
            icon = s("icon") ?: if (kind == Tile.Kept.Kind.Watch) "👀" else "🔖",
            url = s("url"), deadline = deadline, prompt = s("question"), everyMs = (hours * Tile.HOUR).toLong(),
        )
        withContext(Dispatchers.Main) { spaces.keep(item) }
        return ToolOutcome(
            if (kind == Tile.Kept.Kind.Watch) "On the Spaces board: watching '$title', checked every ${hours.toInt()} h; the user is told when it changes."
            else "On the Spaces board: saved '$title'" + (deadline?.let { " with a countdown to its deadline" } ?: "") + "."
        )
    }
}
