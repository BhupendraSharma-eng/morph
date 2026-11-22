package bhupendra.morph.core.llm

import bhupendra.morph.core.agent.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.serialization.json.JsonObject

/** Provider-neutral conversation messages. */
sealed interface Msg {
    data class User(val text: String) : Msg
    data class Assistant(val text: String, val toolCalls: List<ToolCall> = emptyList()) : Msg
    data class ToolResult(val callId: String, val name: String, val content: String) : Msg
}

data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
    /** Provider-specific data that must be echoed back verbatim (e.g. Gemini thought signatures). */
    val extra: JsonObject? = null,
)

data class LlmRequest(
    val system: String,
    val messages: List<Msg>,
    val tools: List<ToolSpec>,
    val maxTokens: Int = 2048,
    /** "low" | "medium" | "high", or null for the model's default. Ignored by providers that don't support it. */
    val reasoningEffort: String? = null,
    /** What the call is for, for the usage ledger. */
    val kind: UsageKind = UsageKind.Agent,
)

sealed interface LlmEvent {
    data class TextDelta(val text: String) : LlmEvent
    /** The model is reasoning (no visible text yet). */
    data object Thinking : LlmEvent
    /** [finishReason]: "stop", "tool_calls", "length" (cut off by max_tokens)… when the provider reports it. */
    data class Completed(val text: String, val toolCalls: List<ToolCall>, val finishReason: String? = null) : LlmEvent
}

/**
 * A streaming chat model. Collecting the flow performs the request; cancelling the
 * collector cancels the HTTP call (no orphaned streams burning battery).
 */
interface LlmProvider {
    val id: String
    fun stream(request: LlmRequest): Flow<LlmEvent>
}

class LlmException(message: String) : Exception(message)

/** One-shot answer, no tools: the whole reply text. For quick background jobs (titles, summaries, drafts). */
suspend fun LlmProvider.complete(
    system: String, user: String, maxTokens: Int = 400, effort: String? = "low", kind: UsageKind = UsageKind.Background,
): String =
    stream(LlmRequest(system, listOf(Msg.User(user)), emptyList(), maxTokens, effort, kind))
        .filterIsInstance<LlmEvent.Completed>().first().text

/** The first {...} object in a model's reply (models wrap JSON in prose or code fences), or null. */
fun jsonObjectIn(text: String): kotlinx.serialization.json.JsonObject? {
    val a = text.indexOf('{'); val b = text.lastIndexOf('}')
    if (a < 0 || b <= a) return null
    return runCatching { kotlinx.serialization.json.Json { isLenient = true }.parseToJsonElement(text.substring(a, b + 1)) as? kotlinx.serialization.json.JsonObject }.getOrNull()
}
