package bhupendra.morph.agent

import bhupendra.morph.core.agent.Agent
import bhupendra.morph.core.agent.AgentEvent
import bhupendra.morph.core.agent.PriorityLane
import bhupendra.morph.core.llm.LlmProvider
import bhupendra.morph.core.surface.Action
import bhupendra.morph.data.CanvasItem
import bhupendra.morph.data.CanvasRepo
import bhupendra.morph.data.SettingsStore
import kotlinx.coroutines.CancellationException
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

    /** Runs a model call in the background lane, tagged with its origin. Returns null without a brain. */
    suspend fun <T> background(priority: PriorityLane.Priority, origin: Origin, block: suspend (LlmProvider) -> T): T? {
        val provider = settings.state.value.provider() ?: return null
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
