package bhupendra.morph.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Native streaming client for the Anthropic Messages API, with tool use. */
class AnthropicProvider(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = "https://api.anthropic.com/v1",
    private val client: OkHttpClient = Http.client,
) : LlmProvider {
    override val id = "anthropic"

    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", request.maxTokens)
            put("stream", true)
            put("system", request.system)
            put("messages", encodeMessages(request.messages))
            if (request.tools.isNotEmpty()) putJsonArray("tools") {
                request.tools.forEach { t ->
                    add(buildJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                        put("input_schema", t.parameters)
                    })
                }
            }
        }
        val http = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/messages")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Accept", "text/event-stream")
            .build()

        val text = StringBuilder()
        val blocks = sortedMapOf<Int, ToolBlock>()
        var stop: String? = null

        client.streamSse(http) { _, data ->
            val ev = parseObject(data) ?: return@streamSse true
            when (ev.raw("type")) {
                "content_block_start" -> {
                    val idx = ev.int("index") ?: 0
                    val block = ev["content_block"] as? JsonObject
                    when (block?.raw("type")) {
                        "tool_use" -> blocks[idx] = ToolBlock(block.raw("id") ?: "tool_$idx", block.raw("name") ?: "")
                        "thinking", "redacted_thinking" -> emit(LlmEvent.Thinking)
                    }
                }
                "content_block_delta" -> {
                    val idx = ev.int("index") ?: 0
                    val delta = ev["delta"] as? JsonObject
                    when (delta?.raw("type")) {
                        "text_delta" -> delta.raw("text")?.let { text.append(it); emit(LlmEvent.TextDelta(it)) }
                        "input_json_delta" -> delta.raw("partial_json")?.let { blocks[idx]?.json?.append(it) }
                    }
                }
                "message_delta" -> ((ev["delta"] as? JsonObject)?.raw("stop_reason"))?.let {
                    stop = if (it == "max_tokens") "length" else it
                }
                "error" -> throw LlmException((ev["error"] as? JsonObject)?.raw("message") ?: "stream error")
                "message_stop" -> return@streamSse false
            }
            true
        }

        emit(LlmEvent.Completed(
            text.toString(),
            blocks.values.map { ToolCall(it.id, it.name, it.json.toString().ifBlank { "{}" }) },
            stop,
        ))
    }.flowOn(Dispatchers.IO)

    /** Anthropic requires strict user/assistant alternation; consecutive same-role turns are merged. */
    private fun encodeMessages(messages: List<Msg>): JsonArray {
        val out = mutableListOf<Pair<String, MutableList<JsonElement>>>()
        fun push(role: String, block: JsonElement) {
            if (out.lastOrNull()?.first == role) out.last().second += block else out += role to mutableListOf(block)
        }
        messages.forEach { m ->
            when (m) {
                is Msg.User -> push("user", buildJsonObject { put("type", "text"); put("text", m.text) })
                is Msg.Assistant -> {
                    if (m.text.isNotBlank()) push("assistant", buildJsonObject { put("type", "text"); put("text", m.text) })
                    m.toolCalls.forEach { c ->
                        push("assistant", buildJsonObject {
                            put("type", "tool_use"); put("id", c.id); put("name", c.name)
                            put("input", parseObject(c.argumentsJson) ?: JsonObject(emptyMap()))
                        })
                    }
                }
                is Msg.ToolResult -> push("user", buildJsonObject {
                    put("type", "tool_result"); put("tool_use_id", m.callId); put("content", m.content)
                })
            }
        }
        return buildJsonArray {
            out.forEach { (role, content) ->
                add(buildJsonObject { put("role", role); put("content", JsonArray(content)) })
            }
        }
    }

    private class ToolBlock(val id: String, val name: String) { val json = StringBuilder() }
}
