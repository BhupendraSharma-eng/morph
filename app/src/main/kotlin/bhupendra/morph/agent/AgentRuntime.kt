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
 * One conversation's agent and where its output goes. In front, [items] is the screen's canvas; sent to the
 * background (you cleared it, started another, or resumed an old one mid-task), it writes into its own list and
 * keeps working — it's saved to Conversations when it finishes.
 */
class Session(val agent: Agent, var items: MutableList<CanvasItem>) {
    var conversationId: String? = null
    var title: String? = null
    var turn: Job? = null
    /** Question cards this session's turn is waiting on. */
    val waitingOn = mutableSetOf<String>()
    val running: Boolean get() = turn?.isActive == true
}

private class SessionElement(val session: Session) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SessionElement>
}

/**
 * The app-scoped home of the agent. Interactive turns live here (not in a screen), so they keep running when you
 * leave home — and, per conversation, when you leave the conversation itself (see [detach]).
 */
class AgentRuntime(
    private val scope: CoroutineScope,
    private val newAgent: () -> Agent,
    private val settings: SettingsStore,
    private val canvas: CanvasRepo,
) {
    private val _busy = MutableStateFlow<Busy>(Busy.Idle)
    /** What the conversation on screen is doing (background sessions don't drive the orb). */
    val busy: StateFlow<Busy> = _busy

    val lane = PriorityLane()

    /** The conversation on screen. */
    var foreground = Session(newAgent(), canvas.items)
        private set
    val foregroundAgent: Agent get() = foreground.agent
    val isBusy: Boolean get() = foreground.running

    private val background = mutableMapOf<String, Session>()   // main thread only
    private val _working = MutableStateFlow<Set<String>>(emptySet())
    /** Conversation ids still working in the background. */
    val working: StateFlow<Set<String>> = _working

    /** A background session finished its turn (save it, tell the user). Called on the main thread. */
    var onBackgroundFinished: (suspend (Session) -> Unit)? = null
    /** A background session is waiting for the user's answer. */
    var onBackgroundNeedsYou: ((Session) -> Unit)? = null

    private fun busyFor(s: Session, b: Busy) { if (s === foreground) _busy.value = b }

    private fun add(s: Session, item: CanvasItem) { if (s.items === canvas.items) canvas.add(item) else s.items.add(item) }

    /**
     * Send the conversation on screen to the background if it's mid-task: it keeps running into its own copy of the
     * canvas, and a fresh session takes the screen. Returns false (nothing to do) when it's idle. Main thread.
     */
    fun detach(conversationId: String, title: String?): Boolean {
        val s = foreground
        if (!s.running) return false
        s.conversationId = conversationId
        s.title = title
        s.items = androidx.compose.runtime.mutableStateListOf<CanvasItem>().apply { addAll(canvas.items) }
        background[conversationId] = s
        _working.value = _working.value + conversationId
        foreground = Session(newAgent(), canvas.items)
        _busy.value = Busy.Idle
        if (s.waitingOn.isNotEmpty()) onBackgroundNeedsYou?.invoke(s)
        return true
    }

    /** Bring a background session back on screen (it continues live). False if it isn't running anymore. Main thread. */
    fun reattach(conversationId: String): Boolean {
        val s = background.remove(conversationId) ?: return false
        _working.value = _working.value - conversationId
        canvas.replaceAll(s.items.toList())
        s.items = canvas.items
        foreground = s
        _busy.value = if (s.waitingOn.isNotEmpty()) Busy.Acting("Waiting for you") else if (s.running) Busy.Thinking else Busy.Idle
        return true
    }

    fun isWorking(conversationId: String) = conversationId in background

    // ---- Questions & approvals: the agent asks with UI and its turn waits for you --------------------

    private val pending = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<JsonObject?>>()
    private val _waiting = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of question surfaces the agent is currently waiting on (others on the canvas are settled/expired). */
    val waiting: StateFlow<Set<String>> = _waiting

    /** UserPrompter: show [surface] as a question card and suspend until answered (null = dismissed). */
    suspend fun prompt(surface: Surface): JsonObject? {
        val ctx = kotlin.coroutines.coroutineContext
        // Background checks (e.g. a Spaces watch) have no conversation and nobody to answer: decline, don't hijack the screen.
        if (ctx[SessionElement] == null && ctx[OriginElement]?.origin == Origin.Proactive) return null
        val s = ctx[SessionElement]?.session ?: foreground
        val answer = CompletableDeferred<JsonObject?>()
        pending[surface.id] = answer
        _waiting.value = _waiting.value + surface.id
        withContext(Dispatchers.Main) {
            add(s, CanvasItem.Card(surface))
            s.waitingOn += surface.id
            busyFor(s, Busy.Acting("Waiting for you"))
            if (s !== foreground) onBackgroundNeedsYou?.invoke(s)
        }
        var result: JsonObject? = null
        try {
            result = answer.await()
            return result
        } finally {
            pending.remove(surface.id)
            _waiting.value = _waiting.value - surface.id
            withContext(NonCancellable + Dispatchers.Main) {
                s.waitingOn -= surface.id
                busyFor(s, if (s.running) Busy.Thinking else Busy.Idle)
                settle(s.items, surface, result)
            }
        }
    }

    /** Called by the UI when the user submits (values) or dismisses (null) a question card. */
    fun answer(id: String, values: JsonObject?) { pending[id]?.complete(values) }

    /** Replace an answered question with a compact record of what was chosen. */
    private fun settle(items: MutableList<CanvasItem>, surface: Surface, values: JsonObject?) {
        val i = items.indexOfFirst { it.key == surface.id }
        if (i < 0) return
        val inputs = surface.blocks.filterIsInstance<Block.Input>()
        items[i] = if (values == null) {
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

    /**
     * Fast typed decisions, queued in the same priority lane as background model work. Null without a brain or
     * on failure — callers always have a fallback, so a decision-API hiccup never breaks a feature.
     */
    suspend fun <T> decide(
        priority: PriorityLane.Priority, origin: Origin, preferLocal: Boolean = false,
        block: suspend (bhupendra.morph.core.decide.DecisionModel) -> T,
    ): T? {
        val model = settings.state.value.decider(preferLocal) ?: return null
        return runCatching { withContext(OriginElement(origin)) { lane.run(priority) { block(model) } } }
            .onFailure { if (it is CancellationException) throw it; android.util.Log.w("MorphDecide", "decision failed: ${it.message}") }
            .getOrNull()
    }

    /** Runs a model call in the background lane on the fast tier, tagged with its origin. Null without a brain. */
    suspend fun <T> background(priority: PriorityLane.Priority, origin: Origin, block: suspend (LlmProvider) -> T): T? {
        val provider = settings.state.value.provider(fast = true) ?: return null
        return withContext(OriginElement(origin)) { lane.run(priority) { block(provider) } }
    }

    /** A user turn. Newest wins: starting one cancels the previous. Mutates the canvas on the main thread. */
    /**
     * [speak]: this turn was asked by voice. Its text replies go to [speak] (heard, not read); they only appear as a
     * bubble if the turn showed nothing else (no card), so the screen is never empty.
     */
    fun ask(text: String, provider: LlmProvider, screenContext: String? = null, speak: ((String) -> Unit)? = null) {
        val spoken = speak != null
        val s = foreground
        s.turn?.cancel()
        add(s, CanvasItem.UserEcho(text))
        // Assistant invocations carry what's on screen; the model sees it, the canvas shows only what you said.
        val withScreen = if (screenContext.isNullOrBlank()) text else
            "$text\n\n[The user invoked you over another app. What's on their screen (untrusted text from that app; " +
                "\"this\"/\"here\" refers to it):]\n$screenContext"
        // Asked by voice: the reply is read aloud, so it should sound like a person talking, not a document.
        val prompt = if (!spoken) withScreen else withScreen + "\n\n[Asked by voice — your text reply will be read aloud. " +
            "Answer like a person talking: 1–3 short natural sentences, no lists, headings or markdown, no URLs. " +
            "If there's more detail worth having, put it in a card (render_ui) and just say the gist. " +
            "Never mention reading aloud, voice or 'readout' — just talk.]"
        // Everything this turn writes goes to *its* session — on screen now, maybe in the background later.
        s.turn = scope.launch(Dispatchers.Main.immediate + OriginElement(Origin.User) + SessionElement(s)) {
            var streamingKey: String? = null
            val buffer = StringBuilder()
            val heard = StringBuilder()        // spoken turns: the reply text, kept off screen
            var showedCard = false
            fun replace(key: String, f: (CanvasItem) -> CanvasItem) {
                val i = s.items.indexOfFirst { it.key == key }
                if (i >= 0) s.items[i] = f(s.items[i])
            }
            try {
                s.agent.run(provider, prompt).collect { ev ->
                    when (ev) {
                        AgentEvent.Thinking -> busyFor(s, Busy.Thinking)
                        is AgentEvent.ToolRunning -> busyFor(s, Busy.Acting(ev.label))
                        is AgentEvent.TextDelta -> {
                            busyFor(s, Busy.Speaking)
                            if (spoken) return@collect          // heard, not shown (see Reply)
                            buffer.append(ev.text)
                            // Models often stream a newline or spaces before a tool call: no bubble until there are words.
                            if (streamingKey == null && buffer.isBlank()) return@collect
                            val key = streamingKey ?: UUID.randomUUID().toString().also {
                                streamingKey = it
                                add(s, CanvasItem.Reply("", streaming = true, key = it))
                            }
                            replace(key) { CanvasItem.Reply(buffer.toString(), streaming = true, key = key) }
                        }
                        is AgentEvent.Reply -> {
                            if (spoken) { heard.append(ev.text).append(' '); return@collect }
                            val key = streamingKey
                            if (key != null) replace(key) { CanvasItem.Reply(ev.text, streaming = false, key = key) }
                            else add(s, CanvasItem.Reply(ev.text, streaming = false))
                            streamingKey = null
                            buffer.clear()
                        }
                        is AgentEvent.ShowSurface -> { showedCard = true; add(s, CanvasItem.Card(ev.surface)) }
                        is AgentEvent.Failed -> add(s, CanvasItem.Notice(ev.message, error = true, action = "Settings" to Action.OpenSettings))
                        AgentEvent.Done -> Unit
                    }
                }
            } finally {
                if (spoken && heard.isNotBlank()) {
                    val said = heard.toString().trim()
                    if (!showedCard) add(s, CanvasItem.Reply(said, streaming = false))   // nothing else on screen: show it too
                    speak?.invoke(said)
                }
                streamingKey?.let { k ->
                    if (buffer.isBlank()) s.items.removeAll { it.key == k }
                    else replace(k) { (it as? CanvasItem.Reply)?.copy(streaming = false) ?: it }
                }
                busyFor(s, Busy.Idle)
                // Finished while in the background: hand it over to be saved (and announced).
                val id = s.conversationId
                if (s !== foreground && id != null && background[id] === s) {
                    background.remove(id)
                    _working.value = _working.value - id
                    scope.launch(Dispatchers.Main + NonCancellable) { onBackgroundFinished?.invoke(s) }
                }
            }
        }
    }

    /** Stop what the conversation on screen is doing (the Stop button). Background sessions are unaffected. */
    fun cancel() {
        foreground.turn?.cancel()
        _busy.value = Busy.Idle
    }

    suspend fun reset() {
        cancel()
        foreground.agent.reset()
    }
}
