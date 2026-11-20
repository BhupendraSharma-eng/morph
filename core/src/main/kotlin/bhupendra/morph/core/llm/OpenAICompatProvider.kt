package bhupendra.morph.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Streaming client for any OpenAI-compatible /chat/completions endpoint:
 * OpenAI, Gemini (openai shim), OpenRouter, OpenCode Zen, Ollama, LM Studio...
 */
class OpenAICompatProvider(
    private val baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    override val id: String = "openai",
    private val client: OkHttpClient = Http.client,
) : LlmProvider {

    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("max_tokens", request.maxTokens)
            put("messages", encodeMessages(request))
            request.reasoningEffort?.let { effort ->
                // OpenRouter normalises reasoning control across models; OpenAI uses its own field.
                when {
                    baseUrl.contains("openrouter.ai") -> putJsonObject("reasoning") { put("effort", effort) }
                    baseUrl.contains("api.openai.com") -> put("reasoning_effort", effort)
                }
            }
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    request.tools.forEach { t ->
                        add(buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", t.parameters)
                            }
                        })
                    }
                }
            }
        }
        val http = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .header("Accept", "text/event-stream")
            .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
            .build()

        val text = StringBuilder()
        val calls = sortedMapOf<Int, PartialCall>()
        var thinkingSignalled = false
        var finish: String? = null

        client.streamSse(http) { _, data ->
            val chunk = parseObject(data) ?: return@streamSse true
            (chunk["error"] as? JsonObject)?.let { throw LlmException(it.raw("message") ?: it.toString()) }
            val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@streamSse true
            choice.raw("finish_reason")?.let { finish = it }
            val delta = choice["delta"] as? JsonObject ?: return@streamSse true

            delta.raw("content")?.takeIf { it.isNotEmpty() }?.let { text.append(it); emit(LlmEvent.TextDelta(it)) }
            if (!thinkingSignalled && (delta.raw("reasoning_content") != null || delta.raw("reasoning") != null)) {
                thinkingSignalled = true
                emit(LlmEvent.Thinking)
            }
            (delta["tool_calls"] as? JsonArray)?.forEach { e ->
                val tc = e as? JsonObject ?: return@forEach
                val p = calls.getOrPut(tc.int("index") ?: calls.size) { PartialCall() }
                tc.raw("id")?.let { p.id = it }
                (tc["function"] as? JsonObject)?.let { fn ->
                    fn.raw("name")?.let { if (p.name.isEmpty()) p.name = it }
                    fn.raw("arguments")?.let { p.args.append(it) }
                }
                (tc["extra_content"] as? JsonObject)?.let { p.extra = it }
            }
            true
        }

        val toolCalls = calls.values.filter { it.name.isNotEmpty() }.mapIndexed { i, p ->
            ToolCall(p.id ?: "call_$i", p.name, p.args.toString().ifBlank { "{}" }, p.extra)
        }
        emit(LlmEvent.Completed(text.toString(), toolCalls, finish))
    }.flowOn(Dispatchers.IO)

    private fun encodeMessages(request: LlmRequest) = buildJsonArray {
        add(buildJsonObject { put("role", "system"); put("content", request.system) })
        request.messages.forEach { m ->
            when (m) {
                is Msg.User -> add(buildJsonObject { put("role", "user"); put("content", m.text) })
                is Msg.Assistant -> add(buildJsonObject {
                    put("role", "assistant")
                    if (m.text.isEmpty() && m.toolCalls.isNotEmpty()) put("content", JsonNull) else put("content", m.text)
                    if (m.toolCalls.isNotEmpty()) putJsonArray("tool_calls") {
                        m.toolCalls.forEach { c ->
                            add(buildJsonObject {
                                put("id", c.id)
                                put("type", "function")
                                putJsonObject("function") { put("name", c.name); put("arguments", c.argumentsJson) }
                                c.extra?.let { put("extra_content", it) }
                            })
                        }
                    }
                })
                is Msg.ToolResult -> add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", m.callId)
                    put("name", m.name)
                    put("content", m.content)
                })
            }
        }
    }

    private class PartialCall {
        var id: String? = null
        var name: String = ""
        val args = StringBuilder()
        var extra: JsonObject? = null
    }
}
