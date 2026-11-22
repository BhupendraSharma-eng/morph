package bhupendra.morph.core.llm

import bhupendra.morph.core.agent.ToolSpec
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.Base64
import java.util.concurrent.TimeUnit

/** What a Gemini Live session tells us. */
sealed interface LiveEvent {
    data class Ready(val resumable: Boolean) : LiveEvent
    /** Model speech: 16-bit mono PCM at 24 kHz. */
    class Audio(val pcm: ByteArray) : LiveEvent
    /** Running transcription of what the user is saying. */
    data class Heard(val text: String) : LiveEvent
    /** Running transcription of what the model is saying. */
    data class Said(val text: String) : LiveEvent
    data class ToolCalls(val calls: List<LiveCall>) : LiveEvent
    data class ToolsCancelled(val ids: List<String>) : LiveEvent
    /** The user talked over the model: stop playback now. */
    data object Interrupted : LiveEvent
    data object TurnComplete : LiveEvent
    data class Usage(val promptTokens: Int, val responseTokens: Int, val totalTokens: Int, val cachedTokens: Int = 0) : LiveEvent
    /** New handle to resume this conversation on a fresh connection (sessions are capped at ~10–15 min). */
    data class Resumption(val handle: String) : LiveEvent
    /** The server will close soon; reconnect with the last [Resumption] handle. */
    data class GoingAway(val timeLeft: String?) : LiveEvent
    data class Closed(val code: Int, val reason: String, val error: String? = null) : LiveEvent
}

data class LiveCall(val id: String, val name: String, val argsJson: String)

/**
 * Gemini Live (BidiGenerateContent) over a WebSocket: stream the mic up, get speech, transcripts and tool calls back.
 * Tools are declared from Morph's own [ToolSpec]s (their JSON Schemas go in `parametersJsonSchema` as-is); tools named
 * in [nonBlocking] run in the background while the conversation continues (the operator).
 */
class GeminiLive(
    private val apiKey: String,
    private val model: String = MODEL,
    private val client: OkHttpClient = Http.client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var socket: WebSocket? = null

    /**
     * Opens the session. The flow ends when the socket closes. [resumeHandle] continues an earlier conversation.
     */
    fun connect(system: String, tools: List<ToolSpec>, nonBlocking: Set<String>, voice: String, resumeHandle: String? = null): Flow<LiveEvent> = callbackFlow {
        val req = Request.Builder()
            .url("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
            .header("x-goog-api-key", apiKey)
            .build()
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setup(system, tools, nonBlocking, voice, resumeHandle).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) = parse(text).forEach { trySendBlocking(it) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = parse(bytes.utf8()).forEach { trySendBlocking(it) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trySendBlocking(LiveEvent.Closed(code, reason)); channel.close()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySendBlocking(LiveEvent.Closed(response?.code ?: -1, response?.message.orEmpty(), t.message ?: t.javaClass.simpleName))
                channel.close()
            }
        })
        socket = ws
        awaitClose { ws.close(1000, null); if (socket === ws) socket = null }
    }

    /** 16-bit mono PCM at 16 kHz from the mic. */
    fun sendAudio(pcm: ByteArray, length: Int = pcm.size) {
        socket?.send(buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonObject("audio") {
                    put("data", Base64.getEncoder().encodeToString(if (length == pcm.size) pcm else pcm.copyOf(length)))
                    put("mimeType", "audio/pcm;rate=16000")
                }
            }
        }.toString())
    }

    /** A JPEG frame (e.g. a screenshot) the model can look at, sent as realtime video input. */
    fun sendImage(jpeg: ByteArray) {
        socket?.send(buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonObject("video") { put("data", Base64.getEncoder().encodeToString(jpeg)); put("mimeType", "image/jpeg") }
            }
        }.toString())
    }

    /** A typed message into the live conversation (e.g. context, or a tapped suggestion). */
    fun sendText(text: String) {
        socket?.send(buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") { addJsonObject { put("role", "user"); putJsonArray("parts") { addJsonObject { put("text", text) } } } }
                put("turnComplete", true)
            }
        }.toString())
    }

    /**
     * Results for tool calls. [whenIdle]: for a background (non-blocking) tool, let the model mention the result once
     * it's not talking rather than cutting itself off.
     */
    fun sendToolResults(results: List<Triple<LiveCall, String, Boolean>>) {
        socket?.send(buildJsonObject {
            putJsonObject("toolResponse") {
                putJsonArray("functionResponses") {
                    results.forEach { (call, result, whenIdle) ->
                        addJsonObject {
                            put("id", call.id); put("name", call.name)
                            putJsonObject("response") { put("result", result); if (whenIdle) put("scheduling", "WHEN_IDLE") }
                        }
                    }
                }
            }
        }.toString())
    }

    fun close() { socket?.close(1000, "bye"); socket = null }

    private fun setup(system: String, tools: List<ToolSpec>, nonBlocking: Set<String>, voice: String, resume: String?) = buildJsonObject {
        putJsonObject("setup") {
            put("model", "models/$model")
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add("AUDIO") }
                putJsonObject("speechConfig") { putJsonObject("voiceConfig") { putJsonObject("prebuiltVoiceConfig") { put("voiceName", voice) } } }
            }
            putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", system) } } }
            putJsonArray("tools") {
                addJsonObject {
                    putJsonArray("functionDeclarations") {
                        tools.forEach { t ->
                            addJsonObject {
                                put("name", t.name); put("description", t.description)
                                put("parametersJsonSchema", t.parameters)
                                if (t.name in nonBlocking) put("behavior", "NON_BLOCKING")
                            }
                        }
                    }
                }
            }
            putJsonObject("inputAudioTranscription") {}
            putJsonObject("outputAudioTranscription") {}
            putJsonObject("sessionResumption") { if (resume != null) put("handle", resume) }
            // Keep the re-read context small: past ~8k tokens, older turns are dropped down to ~3k. Measured: the context
            // grows ~400 tokens a turn (spoken audio), so this caps the per-turn cost (avg ~5k instead of growing).
            putJsonObject("contextWindowCompression") {
                put("triggerTokens", 8_000)
                putJsonObject("slidingWindow") { put("targetTokens", 3_000) }
            }
        }
    }

    private fun parse(text: String): List<LiveEvent> {
        val m = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return emptyList()
        val out = mutableListOf<LiveEvent>()
        fun JsonElement?.obj() = this as? JsonObject
        fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull
        if (m["setupComplete"] != null) out += LiveEvent.Ready(true)
        m["sessionResumptionUpdate"].obj()?.let { u ->
            val h = u["newHandle"].str()
            if (!h.isNullOrBlank() && (u["resumable"] as? JsonPrimitive)?.booleanOrNull != false) out += LiveEvent.Resumption(h)
        }
        m["goAway"].obj()?.let { out += LiveEvent.GoingAway(it["timeLeft"].str()) }
        m["toolCall"].obj()?.let { tc ->
            val calls = (tc["functionCalls"] as? JsonArray).orEmpty().mapNotNull { c ->
                val o = c.obj() ?: return@mapNotNull null
                LiveCall(o["id"].str().orEmpty(), o["name"].str() ?: return@mapNotNull null, (o["args"] ?: JsonObject(emptyMap())).toString())
            }
            if (calls.isNotEmpty()) out += LiveEvent.ToolCalls(calls)
        }
        m["toolCallCancellation"].obj()?.let { c -> out += LiveEvent.ToolsCancelled((c["ids"] as? JsonArray).orEmpty().mapNotNull { it.str() }) }
        m["serverContent"].obj()?.let { sc ->
            sc["inputTranscription"].obj()?.get("text").str()?.let { out += LiveEvent.Heard(it) }
            sc["outputTranscription"].obj()?.get("text").str()?.let { out += LiveEvent.Said(it) }
            (sc["modelTurn"].obj()?.get("parts") as? JsonArray).orEmpty().forEach { p ->
                p.obj()?.get("inlineData").obj()?.get("data").str()?.let { out += LiveEvent.Audio(Base64.getDecoder().decode(it)) }
            }
            if ((sc["interrupted"] as? JsonPrimitive)?.booleanOrNull == true) out += LiveEvent.Interrupted
            if ((sc["turnComplete"] as? JsonPrimitive)?.booleanOrNull == true) out += LiveEvent.TurnComplete
        }
        m["usageMetadata"].obj()?.let { u ->
            fun n(k: String) = (u[k] as? JsonPrimitive)?.intOrNull ?: 0
            out += LiveEvent.Usage(n("promptTokenCount"), n("responseTokenCount"), n("totalTokenCount"), n("cachedContentTokenCount"))
        }
        return out
    }

    companion object { const val MODEL = "gemini-3.8-live" }
}
