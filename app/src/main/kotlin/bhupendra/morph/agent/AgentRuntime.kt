package bhupendra.morph.agent

import bhupendra.morph.core.agent.Agent
import bhupendra.morph.core.agent.AgentEvent
import bhupendra.morph.core.agent.PriorityLane
import bhupendra.morph.core.llm.LlmProvider
import bhupendra.morph.core.surface.Action
import bhupendra.morph.data.CanvasItem
import bhupendra.morph.data.CanvasRepo
import bhupendra.morph.data.SettingsStore
import bhupendra.morph.core.agent.ToolSpec
import bhupendra.morph.core.surface.Block
import bhupendra.morph.core.surface.FormSpec
import bhupendra.morph.core.surface.InputKind
import bhupendra.morph.core.surface.Item
import bhupendra.morph.core.surface.Surface
import bhupendra.morph.core.surface.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Who asked for the work. Travels in the coroutine context so tools and the ledger can see it. */
enum class Origin { User, Proactive, Automation }

class OriginElement(val origin: Origin) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<OriginElement>
}

/** What the agent is doing right now, for the orb and the intent bar. */
sealed interface Busy {
    data object Idle : Busy
    data object Thinking : Busy
    data class Acting(val label: String) : Busy
    data object Speaking : Busy
}

/**
 * The app-scoped home of the agent. Interactive turns live here (not in a screen), so they keep
 * running when you leave home and their results land on the persisted canvas.
 */
class AgentRuntime(
    private val scope: CoroutineScope,
    private val agent: Agent,
    private val settings: SettingsStore,
    private val canvas: CanvasRepo,
) {
    private val _busy = MutableStateFlow<Busy>(Busy.Idle)
    val busy: StateFlow<Busy> = _busy

    val lane = PriorityLane()
    private var turn: Job? = null

    // ---- Questions & approvals: the agent asks with UI and its turn waits for you --------------------

    private val pending = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<JsonObject?>>()
    private val _waiting = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of question surfaces the agent is currently waiting on (others on the canvas are settled/expired). */
    val waiting: StateFlow<Set<String>> = _waiting

    /** UserPrompter: show [surface] as a question card and suspend until answered (null = dismissed). */
    suspend fun prompt(surface: Surface): JsonObject? {
        val answer = CompletableDeferred<JsonObject?>()
        pending[surface.id] = answer
        _waiting.value = _waiting.value + surface.id
        withContext(Dispatchers.Main) { canvas.add(CanvasItem.Card(surface)) }
        val before = _busy.value
        _busy.value = Busy.Acting("Waiting for you")
        var result: JsonObject? = null
        try {
            result = answer.await()
            return result
        } finally {
            pending.remove(surface.id)
            _waiting.value = _waiting.value - surface.id
            _busy.value = if (before == Busy.Idle) Busy.Idle else Busy.Thinking
            withContext(NonCancellable + Dispatchers.Main) { settle(surface, result) }
        }
    }

    /** Called by the UI when the user submits (values) or dismisses (null) a question card. */
    fun answer(id: String, values: JsonObject?) { pending[id]?.complete(values) }

    /** Replace an answered question with a compact record of what was chosen. */
    private fun settle(surface: Surface, values: JsonObject?) {
        val i = canvas.items.indexOfFirst { it.key == surface.id }
        if (i < 0) return
        val inputs = surface.blocks.filterIsInstance<Block.Input>()
        canvas.items[i] = if (values == null) {
            CanvasItem.Notice("Skipped: ${surface.title ?: "question"}", error = false, key = surface.id)
        } else CanvasItem.Card(Surface(
            id = surface.id, title = surface.title ?: "Your answer", icon = "✅", tone = Tone.Neutral,
            blocks = listOf(Block.ListBlock(inputs.map { inp ->
                val v = values[inp.id]
                val shown = when (v) {
                    is JsonPrimitive -> v.contentOrNull.orEmpty() + (inp.unit?.let { " $it" } ?: "")
                    is JsonArray -> v.joinToString { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
                    else -> "—"
                }
                Item(inp.label, shown.ifBlank { "—" })
            })),
        ))
    }

    /**
     * Gatekeeper: after untrusted content entered a turn, state-changing tools need a yes from you.
     * Message-like text is shown in an editable field — what you approve is what gets sent.
     */
    suspend fun approve(spec: ToolSpec, args: JsonObject): JsonObject? {
        // The autonomy dial decides what needs a yes; at Autonomous only the operator's money floor remains.
        if (!settings.state.value.autonomy.gatesAfterUntrusted(spec)) return args
        val textKey = listOf("text", "body", "message", "reply").firstOrNull { (args[it] as? JsonPrimitive)?.isString == true }
        val details = args.filterKeys { it != textKey }.mapNotNull { (k, v) ->
            val value = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
            if (value.isBlank()) null else Item(k.replace('_', ' ').replaceFirstChar { it.uppercase() }, value)
        }
        val surface = Surface(
            title = "Okay to go ahead? ${spec.label}", icon = "🛡️", tone = Tone.Tertiary,
            blocks = buildList {
                add(Block.Text("This came up after I read content from the web or your notifications, so I'm checking with you first."))
                if (details.isNotEmpty()) add(Block.ListBlock(details))
                if (textKey != null) add(Block.Input(id = "text", kind = InputKind.LongText, label = "Message",
                    initial = (args[textKey] as JsonPrimitive).content))
            },
            form = FormSpec(submitLabel = "Do it", cancelLabel = "Don't"),
        )
        val answer = prompt(surface) ?: return null
        val edited = answer["text"]
        return if (textKey != null && edited != null) JsonObject(args + (textKey to edited)) else args
    }

    /** Runs a model call in the background lane on the fast tier, tagged with its origin. Null without a brain. */
    suspend fun <T> background(priority: PriorityLane.Priority, origin: Origin, block: suspend (LlmProvider) -> T): T? {
        val provider = settings.state.value.provider(fast = true) ?: return null
        return withContext(OriginElement(origin)) { lane.run(priority) { block(provider) } }
    }

    /** A user turn. Newest wins: starting one cancels the previous. Mutates the canvas on the main thread. */
    fun ask(text: String, provider: LlmProvider) {
        turn?.cancel()
        canvas.add(CanvasItem.UserEcho(text))
        turn = scope.launch(Dispatchers.Main.immediate + OriginElement(Origin.User)) {
            var streamingKey: String? = null
            val buffer = StringBuilder()
            fun replace(key: String, f: (CanvasItem) -> CanvasItem) {
                val i = canvas.items.indexOfFirst { it.key == key }
                if (i >= 0) canvas.items[i] = f(canvas.items[i])
            }
            try {
                agent.run(provider, text).collect { ev ->
                    when (ev) {
                        AgentEvent.Thinking -> _busy.value = Busy.Thinking
                        is AgentEvent.ToolRunning -> _busy.value = Busy.Acting(ev.label)
                        is AgentEvent.TextDelta -> {
                            _busy.value = Busy.Speaking
                            buffer.append(ev.text)
                            val key = streamingKey ?: UUID.randomUUID().toString().also {
                                streamingKey = it
                                canvas.add(CanvasItem.Reply("", streaming = true, key = it))
                            }
                            replace(key) { CanvasItem.Reply(buffer.toString(), streaming = true, key = key) }
                        }
                        is AgentEvent.Reply -> {
                            val key = streamingKey
                            if (key != null) replace(key) { CanvasItem.Reply(ev.text, streaming = false, key = key) }
                            else canvas.add(CanvasItem.Reply(ev.text, streaming = false))
                            streamingKey = null
                            buffer.clear()
                        }
                        is AgentEvent.ShowSurface -> canvas.add(CanvasItem.Card(ev.surface))
                        is AgentEvent.Failed -> canvas.add(CanvasItem.Notice(ev.message, error = true, action = "Settings" to Action.OpenSettings))
                        AgentEvent.Done -> Unit
                    }
                }
            } finally {
                streamingKey?.let { k -> replace(k) { (it as? CanvasItem.Reply)?.copy(streaming = false) ?: it } }
                _busy.value = Busy.Idle
            }
        }
    }

    fun cancel() {
        turn?.cancel()
        _busy.value = Busy.Idle
    }

    suspend fun reset() {
        cancel()
        agent.reset()
    }
}
