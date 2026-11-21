package bhupendra.morph.core.agent

import bhupendra.morph.core.llm.LlmEvent
import bhupendra.morph.core.llm.LlmException
import bhupendra.morph.core.llm.LlmProvider
import bhupendra.morph.core.llm.LlmRequest
import bhupendra.morph.core.llm.Msg
import bhupendra.morph.core.surface.Surface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

sealed interface AgentEvent {
    data object Thinking : AgentEvent
    data class TextDelta(val text: String) : AgentEvent
    data class ToolRunning(val name: String, val label: String) : AgentEvent
    data class ShowSurface(val surface: Surface) : AgentEvent
    /** A finished block of assistant text (may precede tool calls). */
    data class Reply(val text: String) : AgentEvent
    data class Failed(val message: String) : AgentEvent
    data object Done : AgentEvent
}

/**
 * The agent loop: model → tool calls → results → model, until the model answers
 * or emits only terminal (UI) tools. One loop for every entry point (typed, voice,
 * surface taps, and later proactive triggers).
 */
class Agent(
    private val tools: ToolRegistry,
    private val systemPrompt: suspend () -> String,
    /** Read per turn, so the autonomy dial applies immediately. */
    private val maxRounds: () -> Int = { 8 },
    private val maxTokens: Int = 4096,
    private val maxHistory: Int = 30,
    /** Called after every turn with the settled history — persist it here. */
    private val onHistory: (List<Msg>) -> Unit = {},
    /** Consulted before state-changing tools once a turn has read untrusted content. */
    private val gatekeeper: Gatekeeper? = null,
    /** Reasoning effort for conversational turns ("low"/"medium"/"high"; null = model default). */
    private val reasoningEffort: () -> String? = { null },
) {
    private val history = mutableListOf<Msg>()
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun reset() = lock.withLock { history.clear(); onHistory(emptyList()) }

    /** The conversation so far (for archiving it). */
    suspend fun snapshot(): List<Msg> = lock.withLock { history.toList() }

    /** Restore a previously persisted conversation (call once at startup). */
    suspend fun restore(messages: List<Msg>) = lock.withLock {
        history.clear()
        // Only resume from a user-turn boundary so tool call/result pairs stay valid.
        val start = messages.indexOfFirst { it is Msg.User }
        if (start >= 0) history += messages.drop(start).takeLast(maxHistory)
        onHistory(history.toList())   // persist right away: the restored thread is now the live one
    }

    fun run(provider: LlmProvider, userText: String): Flow<AgentEvent> = flow {
        lock.withLock {
            val checkpoint = history.size
            history += Msg.User(userText)
            compactOldToolResults()   // previous turns' bulky results aren't re-sent in full
            var clean = false
            // Becomes true once this turn has read third-party text (web, notifications, messages).
            var tainted = false
            try {
                val rounds = maxRounds()
                for (round in 0 until rounds) {
                    emit(AgentEvent.Thinking)
                    var completed: LlmEvent.Completed? = null
                    provider.stream(LlmRequest(systemPrompt(), history.toList(), tools.specs, maxTokens, reasoningEffort())).collect { ev ->
                        when (ev) {
                            is LlmEvent.TextDelta -> emit(AgentEvent.TextDelta(ev.text))
                            is LlmEvent.Thinking -> Unit
                            is LlmEvent.Completed -> completed = ev
                        }
                    }
                    val c = completed ?: throw LlmException("The model returned nothing")
                    history += Msg.Assistant(c.text, c.toolCalls)
                    if (c.text.isNotBlank()) emit(AgentEvent.Reply(c.text.trim()))

                    if (c.toolCalls.isEmpty()) { clean = true; break }

                    for (call in c.toolCalls) {
                        val tool = tools[call.name]
                        val result = if (tool == null) {
                            "Error: no tool named ${call.name}"
                        } else {
                            emit(AgentEvent.ToolRunning(call.name, tool.spec.label))
                            try {
                                var args = runCatching { json.parseToJsonElement(call.argumentsJson) as? JsonObject }
                                    .getOrNull() ?: JsonObject(emptyMap())
                                val needsApproval = tainted && gatekeeper != null &&
                                    tool.spec.risk != Risk.Read && !tool.spec.terminal
                                val approved = if (needsApproval) gatekeeper!!.approve(tool.spec, args) else args
                                if (approved == null) {
                                    "The user declined this action. Don't retry it unless they ask."
                                } else {
                                    args = approved
                                    val out = tool.run(args)
                                    if (tool.spec.untrustedOutput) tainted = true
                                    out.surface?.let { emit(AgentEvent.ShowSurface(it)) }
                                    out.result
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                "Error: ${e.message ?: e::class.simpleName}"
                            }
                        }
                        history += Msg.ToolResult(call.id, call.name, result)
                    }

                    if (c.toolCalls.all { tools[it.name]?.spec?.terminal == true }) { clean = true; break }
                    if (round == rounds - 1) emit(AgentEvent.Reply("I stopped after $rounds steps."))
                }
                clean = true
                emit(AgentEvent.Done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(AgentEvent.Failed(e.message ?: "Something went wrong"))
            } finally {
                // Never leave dangling tool calls without results: providers reject that history.
                if (!clean) while (history.size > checkpoint + 1) history.removeAt(history.lastIndex)
                trim()
                onHistory(history.toList())
            }
        }
    }

    /** Keep recent context, cutting only at user-turn boundaries so tool call/result pairs stay intact. */
    private fun trim() {
        compactOldToolResults()
        if (history.size <= maxHistory) return
        var cut = history.size - maxHistory
        while (cut < history.size && history[cut] !is Msg.User) cut++
        repeat(cut) { history.removeAt(0) }
    }

    /**
     * Tool results from earlier turns (web pages, search digests) are only needed in full for the turn
     * that fetched them. Shrinking them keeps every later request small — the main cost lever.
     */
    private fun compactOldToolResults() {
        val lastUser = history.indexOfLast { it is Msg.User }
        for (i in 0 until lastUser.coerceAtLeast(0)) {
            val m = history[i]
            if (m is Msg.ToolResult && m.content.length > COMPACT_TO) {
                history[i] = m.copy(content = m.content.take(COMPACT_TO) + " …[trimmed]")
            }
        }
    }

    companion object { const val COMPACT_TO = 600 }
}
