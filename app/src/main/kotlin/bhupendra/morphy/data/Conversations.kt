package bhupendra.morphy.data

import android.content.Context
import androidx.core.content.edit
import bhupendra.morphy.agent.AgentRuntime
import bhupendra.morphy.core.agent.PriorityLane
import bhupendra.morphy.core.llm.Msg
import bhupendra.morphy.core.llm.MsgCodec
import bhupendra.morphy.ui.plainText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/** One archived conversation, as listed and searched. The full canvas + agent thread live in its own file. */
data class ConversationMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val preview: String,
    /** Lowercased plain text of the whole conversation, for on-device search (capped). */
    val text: String = "",
)


/**
 * Past conversations. The live one is the canvas + agent thread as before; it gets archived here when you clear
 * it, when you resume another one, or when you start talking after a quiet gap. Resuming restores both the
 * canvas and the agent's memory of it, so it continues exactly where it left off. Everything stays on the device.
 */
class Conversations(
    context: Context,
    private val scope: CoroutineScope,
    private val canvas: CanvasRepo,
    private val runtime: () -> AgentRuntime,
) {
    private val notifier = bhupendra.morphy.agent.TaskNotifier(context)
    private val dir = File(context.filesDir, "conversations").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val prefs = context.getSharedPreferences("conversations", Context.MODE_PRIVATE)
    private val lock = Mutex()

    private val _list = MutableStateFlow<List<ConversationMeta>>(emptyList())
    /** Archived conversations, newest first. */
    val list: StateFlow<List<ConversationMeta>> = _list

    private var currentId: String
        get() = prefs.getString("current", null) ?: UUID.randomUUID().toString().also { currentId = it }
        set(v) = prefs.edit { putString("current", v) }
    private var currentCreatedAt: Long
        get() = prefs.getLong("created", System.currentTimeMillis())
        set(v) = prefs.edit { putLong("created", v) }
    private var currentTitle: String?
        get() = prefs.getString("title", null)
        set(v) = prefs.edit { if (v == null) remove("title") else putString("title", v) }
    private var lastActivity: Long
        get() = prefs.getLong("active", 0L)
        set(v) = prefs.edit { putLong("active", v) }

    init {
        // Sessions that kept working after you left them: save them when they finish, and say so.
        runtime().onBackgroundFinished = { s -> finishedInBackground(s) }
        runtime().onBackgroundNeedsYou = { s -> s.conversationId?.let { notifier.needsYou(it, s.title ?: "A conversation") } }
        scope.launch(Dispatchers.IO) {
            _list.value = runCatching { (Json.parseToJsonElement(indexFile.readText()) as JsonArray).mapNotNull { metaOf(it as? JsonObject) } }
                .getOrDefault(emptyList())
        }
    }

    /** Only conversations where the user actually asked something are worth keeping. */
    private fun hasConversation() = canvas.items.any { it is CanvasItem.UserEcho }

    /** Call before a new request: after a quiet gap, the old conversation is archived and a fresh one starts. */
    suspend fun beforeAsk() {
        val now = System.currentTimeMillis()
        if (hasConversation() && lastActivity > 0 && now - lastActivity > QUIET_GAP_MS) startNew()
        lastActivity = System.currentTimeMillis()
    }

    /**
     * Step away from the conversation on screen: it's archived, and if the agent is mid-task it keeps working in the
     * background (saved again when it finishes). Returns true if it was sent to the background.
     */
    private suspend fun leaveCurrent(): Boolean {
        val id = currentId
        val busy = withContext(Dispatchers.Main) { runtime().isBusy }
        // A running agent holds its thread until the turn ends; its memory is saved when it finishes instead.
        archiveCurrent(withHistory = !busy)
        val detached = withContext(Dispatchers.Main) { runtime().detach(id, currentTitle ?: firstAsk()) }
        if (!detached && busy) archiveCurrent(withHistory = true)   // it finished in the meantime
        return detached
    }

    /** Archive the current conversation (if any) and start with a clean canvas and a fresh agent thread. */
    suspend fun startNew() {
        val detached = leaveCurrent()
        withContext(Dispatchers.Main) { canvas.items.clear() }
        // A detached session took its agent along; resetting the fresh one also clears the live thread on disk.
        if (detached) runtime().foregroundAgent.reset() else runtime().reset()
        currentId = UUID.randomUUID().toString()
        currentCreatedAt = System.currentTimeMillis()
        currentTitle = null
    }

    /**
     * Make another conversation the one on screen. If it's still working in the background it comes back live;
     * otherwise it's restored from disk (canvas + the agent's memory). The current one is left first (see [leaveCurrent]).
     */
    suspend fun resume(id: String) {
        if (id == currentId) return
        notifier.clear(id)
        val meta = _list.value.firstOrNull { it.id == id }
        if (withContext(Dispatchers.Main) { runtime().isWorking(id) }) {
            leaveCurrent()
            withContext(Dispatchers.Main) { runtime().reattach(id) }
        } else {
            meta ?: return
            val saved = withContext(Dispatchers.IO) {
                runCatching { Json.parseToJsonElement(File(dir, "$id.json").readText()) as JsonObject }.getOrNull()
            } ?: return
            val savedCanvas = (saved["canvas"] as? JsonPrimitive)?.contentOrNull ?: return
            val savedHistory = (saved["history"] as? JsonPrimitive)?.contentOrNull ?: "[]"
            leaveCurrent()
            withContext(Dispatchers.Main) { canvas.replaceAll(canvas.decode(savedCanvas)) }
            runtime().foregroundAgent.restore(MsgCodec.decode(savedHistory))
        }
        currentId = id
        currentCreatedAt = meta?.createdAt ?: System.currentTimeMillis()
        currentTitle = meta?.title
        lastActivity = System.currentTimeMillis()
        // It's live now; it returns to the list (same id, same title) when it's archived again.
        lock.withLock { writeIndex(_list.value.filterNot { it.id == id }) }
    }

    /** A background session finished: save its canvas + memory under its conversation, and let the user know. */
    private suspend fun finishedInBackground(s: bhupendra.morphy.agent.Session) {
        val id = s.conversationId ?: return
        val items = s.items.filterNot { it is CanvasItem.Reply && it.streaming }.toList()
        val history = s.agent.snapshot()
        val old = _list.value.firstOrNull { it.id == id }
        save(id, items, history, old?.createdAt ?: System.currentTimeMillis(), old?.title ?: s.title)
        val last = items.lastOrNull { it !is CanvasItem.UserEcho }?.plainText()?.replace('\n', ' ')?.take(120) ?: "Done."
        notifier.finished(id, old?.title ?: s.title ?: "Your task", last)
    }

    suspend fun delete(id: String) {
        lock.withLock {
            writeIndex(_list.value.filterNot { it.id == id })
            withContext(Dispatchers.IO) { File(dir, "$id.json").delete() }
        }
    }

    /** Plain-text dump of the open conversation, the saved ones and Spaces (adb debug export). Returns the path. */
    suspend fun exportText(graph: bhupendra.morphy.AppGraph): String {
        val out = StringBuilder()
        val open = withContext(Dispatchers.Main) { canvas.items.toList() }
        out.appendLine("=== OPEN CONVERSATION ===")
        open.forEach { out.appendLine((if (it is CanvasItem.UserEcho) "USER: " else "MORPHY: ") + it.plainText()) }
        _list.value.take(15).forEach { m ->
            out.appendLine("\n=== ${m.title}  (${java.time.Instant.ofEpochMilli(m.updatedAt)}) ===")
            val items = runCatching {
                val o = Json.parseToJsonElement(File(dir, "${m.id}.json").readText()) as JsonObject
                canvas.decode((o["canvas"] as JsonPrimitive).content)
            }.getOrDefault(emptyList())
            items.forEach { out.appendLine((if (it is CanvasItem.UserEcho) "USER: " else "MORPHY: ") + it.plainText()) }
        }
        out.appendLine("\n=== SPACES ===")
        withContext(Dispatchers.Main) { graph.spaces.tiles.toList() }.forEach { t ->
            out.appendLine(when (t) {
                is Tile.Widget -> "widget: " + (graph.spaces.widgets.getAppWidgetInfo(t.appWidgetId)?.let {
                    "${it.provider.flattenToShortString()} min=${it.minWidth}x${it.minHeight}px target=${it.targetCellWidth}x${it.targetCellHeight}"
                } ?: "?")
                is Tile.Kept -> "kept ${t.kind}: ${t.title} | ${t.note} | url=${t.url} | q=${t.prompt} | last=${t.lastResult}"
            })
        }
        val dirOut = File(graph.app.getExternalFilesDir(null), "export").apply { mkdirs() }
        val f = File(dirOut, "conversations.txt")
        f.writeText(out.toString())
        return f.absolutePath
    }

    /** On-device search over titles and text: every word must appear. */
    fun search(query: String): List<ConversationMeta> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return _list.value
        return _list.value.filter { m -> val hay = m.title.lowercase() + " " + m.text; words.all { it in hay } }
    }

    private fun firstAsk(): String? = (canvas.items.firstOrNull { it is CanvasItem.UserEcho } as? CanvasItem.UserEcho)?.text?.take(48)

    private suspend fun archiveCurrent(withHistory: Boolean) {
        val items = withContext(Dispatchers.Main) { canvas.items.filterNot { it is CanvasItem.Reply && it.streaming }.toList() }
        if (items.none { it is CanvasItem.UserEcho }) return
        val history = if (withHistory) runtime().foregroundAgent.snapshot() else null
        val title = currentTitle
        save(currentId, items, history, currentCreatedAt, title)
        if (title == null) nameInBackground(currentId, items)
    }

    /** Write one conversation (history null = keep what's on disk) and its index entry. */
    private suspend fun save(id: String, items: List<CanvasItem>, history: List<Msg>?, createdAt: Long, title: String?) {
        val firstAsk = (items.firstOrNull { it is CanvasItem.UserEcho } as? CanvasItem.UserEcho)?.text ?: return
        val lastLine = items.lastOrNull { it !is CanvasItem.UserEcho }?.plainText() ?: firstAsk
        val text = items.joinToString("\n") { it.plainText() }.lowercase().take(8_000)
        val meta = ConversationMeta(id, title ?: firstAsk.take(48), createdAt, System.currentTimeMillis(),
            lastLine.replace('\n', ' ').take(140), text)
        lock.withLock {
            withContext(Dispatchers.IO) {
                val file = File(dir, "$id.json")
                val historyJson = history?.let(MsgCodec::encode)
                    ?: runCatching { ((Json.parseToJsonElement(file.readText()) as JsonObject)["history"] as JsonPrimitive).content }.getOrNull()
                    ?: "[]"
                file.writeText(buildJsonObject { put("canvas", canvas.encode(items)); put("history", historyJson) }.toString())
            }
            writeIndex(listOf(meta) + _list.value.filterNot { it.id == id })
        }
    }

    /** A few-word title from the fast model, in the conversation's own language. Falls back to the first ask. */
    private fun nameInBackground(id: String, items: List<CanvasItem>) = scope.launch {
        val transcript = items.take(8).joinToString("\n") { i ->
            (if (i is CanvasItem.UserEcho) "User: " else "Morphy: ") + i.plainText().take(300)
        }
        val done = runtime().quick(
            "Name this conversation in 2–5 words, in the language it was held in. Title case, no quotes, " +
                "no emoji, no trailing punctuation. Reply with only the title.",
            transcript, maxTokens = 40, priority = PriorityLane.Priority.Background,
        ) ?: return@launch
        val title = done.lines().firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '\'', '.')?.take(60)?.takeIf { it.length >= 2 }
            ?: return@launch
        lock.withLock { writeIndex(_list.value.map { if (it.id == id) it.copy(title = title) else it }) }
    }

    private suspend fun writeIndex(list: List<ConversationMeta>) {
        val sorted = list.sortedByDescending { it.updatedAt }
        val trimmed = sorted.take(MAX_KEPT)
        _list.value = trimmed
        withContext(Dispatchers.IO) {
            sorted.drop(MAX_KEPT).forEach { File(dir, "${it.id}.json").delete() }
            runCatching { indexFile.writeText(JsonArray(trimmed.map(::metaJson)).toString()) }
        }
    }

    private fun metaJson(m: ConversationMeta) = buildJsonObject {
        put("id", m.id); put("title", m.title); put("createdAt", m.createdAt); put("updatedAt", m.updatedAt)
        put("preview", m.preview); put("text", m.text)
    }

    private fun metaOf(o: JsonObject?): ConversationMeta? {
        o ?: return null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        fun num(k: String) = (o[k] as? JsonPrimitive)?.longOrNull ?: 0L
        return ConversationMeta(str("id") ?: return null, str("title").orEmpty(), num("createdAt"), num("updatedAt"),
            str("preview").orEmpty(), str("text").orEmpty())
    }

    companion object {
        /** Talking again after this long starts a new conversation (the old one is archived, not lost). */
        const val QUIET_GAP_MS = 30 * 60_000L
        const val MAX_KEPT = 300
    }
}
