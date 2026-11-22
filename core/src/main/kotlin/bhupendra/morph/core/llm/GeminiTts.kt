package bhupendra.morph.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/**
 * Gemini text-to-speech, streamed: emits raw PCM chunks (16-bit, mono, 24 kHz) as they arrive over SSE, so playback
 * can start in about a second instead of waiting for the whole clip.
 */
class GeminiTts(
    private val apiKey: String,
    private val model: String = MODEL,
    private val client: OkHttpClient = Http.client,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun stream(text: String, voice: String): Flow<ByteArray> = flow {
        val body = buildJsonObject {
            putJsonArray("contents") { add(buildJsonObject { putJsonArray("parts") { add(buildJsonObject { put("text", text) }) } }) }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add("AUDIO") }
                putJsonObject("speechConfig") { putJsonObject("voiceConfig") { putJsonObject("prebuiltVoiceConfig") { put("voiceName", voice) } } }
            }
        }
        val req = Request.Builder()
            .url("$baseUrl/models/$model:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw LlmException("TTS HTTP ${resp.code}: ${resp.body.string().take(200)}")
            val src = resp.body.source()
            while (!src.exhausted() && currentCoroutineContext().isActive) {
                val line = src.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val parts = ((json.parseToJsonElement(line.substring(5)) as? JsonObject)?.get("candidates") as? JsonArray)
                    ?.firstOrNull()?.let { (it as? JsonObject)?.get("content") as? JsonObject }?.get("parts") as? JsonArray ?: continue
                for (p in parts) {
                    val data = ((p as? JsonObject)?.get("inlineData") as? JsonObject)?.get("data") as? JsonPrimitive ?: continue
                    emit(Base64.getDecoder().decode(data.contentOrNull.orEmpty()))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object { const val MODEL = "gemini-3.8-flash-lite-tts" }
}
