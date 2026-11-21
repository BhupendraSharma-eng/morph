package bhupendra.morph.data

import android.content.Context
import androidx.core.content.edit
import bhupendra.morph.agent.AgentRuntime
import bhupendra.morph.agent.Origin
import bhupendra.morph.core.agent.Agent
import bhupendra.morph.core.agent.PriorityLane
import bhupendra.morph.core.llm.LlmEvent
import bhupendra.morph.core.llm.LlmRequest
import bhupendra.morph.core.llm.Msg
import bhupendra.morph.core.llm.MsgCodec
import bhupendra.morph.ui.plainText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private val agent: Agent,
    private val runtime: () -> AgentRuntime,
) {
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

    /** Archive the current conversation (if any) and start with a clean canvas and a fresh agent thread. */
    suspend fun startNew() {
        archiveCurrent()
        withContext(Dispatchers.Main) { canvas.items.clear() }
        runtime().reset()
        currentId = UUID.randomUUID().toString()
        currentCreatedAt = System.currentTimeMillis()
        currentTitle = null
    }

    /** Make an archived conversation the live one again (the current one is archived first). */
    suspend fun resume(id: String) {
        if (id == currentId) return
        val meta = _list.value.firstOrNull { it.id == id } ?: return
        val saved = withContext(Dispatchers.IO) {
            runCatching { Json.parseToJsonElement(File(dir, "$id.json").readText()) as JsonObject }.getOrNull()
        } ?: return
        val savedCanvas = (saved["canvas"] as? JsonPrimitive)?.contentOrNull ?: return
        val savedHistory = (saved["history"] as? JsonPrimitive)?.contentOrNull ?: "[]"
        archiveCurrent()
        runtime().cancel()
        withContext(Dispatchers.Main) { canvas.replaceAll(canvas.decode(savedCanvas)) }
        agent.restore(MsgCodec.decode(savedHistory))
        currentId = id
        currentCreatedAt = meta.createdAt
        currentTitle = meta.title
        lastActivity = System.currentTimeMillis()
        // It's live now; it returns to the list (same id, same title) when it's archived again.
        lock.withLock { writeIndex(_list.value.filterNot { it.id == id }) }
    }

    suspend fun delete(id: String) {
        lock.withLock {
            writeIndex(_list.value.filterNot { it.id == id })
            withContext(Dispatchers.IO) { File(dir, "$id.json").delete() }
        }
    }

    /** On-device search over titles and text: every word must appear. */
    fun search(query: String): List<ConversationMeta> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return _list.value
        return _list.value.filter { m -> val hay = m.title.lowercase() + " " + m.text; words.all { it in hay } }
    }

    private suspend fun archiveCurrent() {
        val items = withContext(Dispatchers.Main) { canvas.items.filterNot { it is CanvasItem.Reply && it.streaming }.toList() }
        if (items.none { it is CanvasItem.UserEcho }) return
        val history = agent.snapshot()
        val id = currentId
        val firstAsk = (items.first { it is CanvasItem.UserEcho } as CanvasItem.UserEcho).text
        val lastLine = items.lastOrNull { it !is CanvasItem.UserEcho }?.plainText() ?: firstAsk
        val text = items.joinToString("\n") { it.plainText() }.lowercase().take(8_000)
        val title = currentTitle ?: firstAsk.take(48)
        val meta = ConversationMeta(id, title, currentCreatedAt, System.currentTimeMillis(),
            lastLine.replace('\n', ' ').take(140), text)
        lock.withLock {
            withContext(Dispatchers.IO) {
                File(dir, "$id.json").writeText(buildJsonObject {
                    put("canvas", canvas.encode(items)); put("history", MsgCodec.encode(history))
                }.toString())
            }
            writeIndex(listOf(meta) + _list.value.filterNot { it.id == id })
        }
        if (currentTitle == null) nameInBackground(id, items)
    }

    /** A few-word title from the fast model, in the conversation's own language. Falls back to the first ask. */
    private fun nameInBackground(id: String, items: List<CanvasItem>) = scope.launch {
        val transcript = items.take(8).joinToString("\n") { i ->
            (if (i is CanvasItem.UserEcho) "User: " else "Morph: ") + i.plainText().take(300)
        }
        val done = runtime().background(PriorityLane.Priority.Background, Origin.Proactive) { provider ->
            withTimeoutOrNull(20_000) {
                provider.stream(LlmRequest(
                    system = "Name this conversation in 2–5 words, in the language it was held in. Title case, no quotes, " +
                        "no emoji, no trailing punctuation. Reply with only the title.",
                    messages = listOf(Msg.User(transcript)), tools = emptyList(), maxTokens = 40, reasoningEffort = "low",
                )).filterIsInstance<LlmEvent.Completed>().first()
            }
        } ?: return@launch
        val title = done.text.lines().firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '\'', '.')?.take(60)?.takeIf { it.length >= 2 }
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
