package bhupendra.morph.agent

import android.content.Context
import bhupendra.morph.core.agent.Risk
import bhupendra.morph.core.agent.Tool
import bhupendra.morph.core.agent.ToolOutcome
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

data class LedgerEntry(
    val at: Long,
    val origin: Origin,
    val what: String,
    val detail: String,
    val result: String,
)

/**
 * Every action Morph takes — whether you asked or automation did — with when, why and what happened.
 * Read-only tools aren't logged; anything that changes the world is.
 */
class Ledger(context: Context) {
    private val file = File(context.filesDir, "ledger.json")
    private val _entries = MutableStateFlow(load())
    /** Newest first. */
    val entries: StateFlow<List<LedgerEntry>> = _entries

    @Synchronized
    fun record(origin: Origin, what: String, detail: String, result: String) {
        _entries.value = (listOf(LedgerEntry(System.currentTimeMillis(), origin, what, detail.take(300), result.take(300))) + _entries.value).take(MAX)
        runCatching { file.writeText(encode(_entries.value)) }
    }

    /** Decorates a tool so its runs are recorded with the origin found in the coroutine context. */
    fun wrap(tool: Tool): Tool {
        if (tool.spec.risk == Risk.Read || tool.spec.terminal) return tool
        return object : Tool {
            override val spec = tool.spec
            override suspend fun run(args: JsonObject): ToolOutcome {
                val origin = currentCoroutineContext()[OriginElement]?.origin ?: Origin.User
                val out = tool.run(args)
                record(origin, tool.spec.label, args.toString(), out.result)
                return out
            }
        }
    }

    private fun encode(list: List<LedgerEntry>) = JsonArray(list.map {
        buildJsonObject { put("at", it.at); put("o", it.origin.name); put("w", it.what); put("d", it.detail); put("r", it.result) }
    }).toString()

    private fun load(): List<LedgerEntry> = runCatching {
        (Json.parseToJsonElement(file.readText()) as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            LedgerEntry((o["at"] as? JsonPrimitive)?.longOrNull ?: 0, runCatching { Origin.valueOf(s("o")) }.getOrDefault(Origin.User),
                s("w"), s("d"), s("r"))
        }
    }.getOrDefault(emptyList())

    companion object { const val MAX = 200 }
}
