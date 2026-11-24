package bhupendra.morphy.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Persists conversation history so the agent remembers the thread across restarts. */
object MsgCodec {
    fun encode(messages: List<Msg>): String = JsonArray(messages.map { m ->
        when (m) {
            is Msg.User -> buildJsonObject { put("r", "user"); put("text", m.text) }
            is Msg.Assistant -> buildJsonObject {
                put("r", "assistant"); put("text", m.text)
                put("calls", JsonArray(m.toolCalls.map { c ->
                    buildJsonObject {
                        put("id", c.id); put("name", c.name); put("args", c.argumentsJson)
                        c.extra?.let { put("extra", it) }
                    }
                }))
            }
            is Msg.ToolResult -> buildJsonObject { put("r", "tool"); put("id", m.callId); put("name", m.name); put("text", m.content) }
        }
    }).toString()

    fun decode(s: String): List<Msg> = runCatching {
        (Json.parseToJsonElement(s) as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            when (str("r")) {
                "user" -> Msg.User(str("text").orEmpty())
                "assistant" -> Msg.Assistant(str("text").orEmpty(), (o["calls"] as? JsonArray).orEmpty().mapNotNull { ce ->
                    val c = ce as? JsonObject ?: return@mapNotNull null
                    fun cs(k: String) = (c[k] as? JsonPrimitive)?.contentOrNull
                    ToolCall(cs("id") ?: return@mapNotNull null, cs("name").orEmpty(), cs("args") ?: "{}", c["extra"] as? JsonObject)
                })
                "tool" -> Msg.ToolResult(str("id").orEmpty(), str("name").orEmpty(), str("text").orEmpty())
                else -> null
            }
        }
    }.getOrDefault(emptyList())
}
