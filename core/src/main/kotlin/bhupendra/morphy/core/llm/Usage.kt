package bhupendra.morphy.core.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** What a model call was for — the rows of the usage page. */
enum class UsageKind(val label: String) {
    Agent("Conversations"), Operator("Operator"), Background("Background"), Decision("Decisions"),
    Web("Web search"), Voice("Voice (TTS)"), Live("Hands-free"),
}

/**
 * One model call. [costUsd] is what the provider reported (OpenRouter, Jev) or, when [estimated], computed from
 * published/assumed rates; null when there's no way to know (e.g. a free tier or a local model reports nothing).
 */
data class UsageRecord(
    val at: Long, val kind: UsageKind, val model: String,
    val inTokens: Int, val outTokens: Int, val costUsd: Double?, val estimated: Boolean = false,
    /** Wall time of the call and time to the first streamed output (ms), when known. */
    val ms: Long? = null, val firstMs: Long? = null,
    /** Prompt tokens served from the provider's cache (cheaper), when reported. */
    val cachedTokens: Int = 0,
)

/**
 * Every model call in the app reports here; the app installs [sink] to keep the ledger (Settings → Usage & cost).
 * Providers call [record] once per completed call, so nothing is double-counted.
 */
object UsageMeter {
    @Volatile var sink: ((UsageRecord) -> Unit)? = null

    fun record(
        kind: UsageKind, model: String, inTokens: Int, outTokens: Int, costUsd: Double?, estimated: Boolean = false,
        ms: Long? = null, firstMs: Long? = null, cachedTokens: Int = 0,
    ) {
        runCatching { sink?.invoke(UsageRecord(System.currentTimeMillis(), kind, model, inTokens, outTokens, costUsd, estimated, ms, firstMs, cachedTokens)) }
    }

    /** OpenAI-style `usage` (prompt_tokens / completion_tokens, cached prompt tokens, + OpenRouter's `cost`). */
    fun fromOpenAi(kind: UsageKind, model: String, usage: JsonObject, ms: Long? = null, firstMs: Long? = null) {
        fun n(k: String) = (usage[k] as? JsonPrimitive)?.intOrNull ?: 0
        val cached = ((usage["prompt_tokens_details"] as? JsonObject)?.get("cached_tokens") as? JsonPrimitive)?.intOrNull ?: 0
        record(kind, model, n("prompt_tokens"), n("completion_tokens"), (usage["cost"] as? JsonPrimitive)?.doubleOrNull,
            ms = ms, firstMs = firstMs, cachedTokens = cached)
    }
}
