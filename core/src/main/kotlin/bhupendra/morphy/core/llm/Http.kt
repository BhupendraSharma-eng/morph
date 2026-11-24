package bhupendra.morphy.core.llm

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

internal val JSON_MEDIA = "application/json".toMediaType()
internal val json = Json { ignoreUnknownKeys = true; isLenient = true }

object Http {
    /** One shared client: one connection pool, HTTP/2 multiplexing, kept-alive TLS to the model API. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var warmedAt = 0L

    /**
     * Opens (or refreshes) the TLS connection to the model API ahead of the first request — called the moment
     * the user starts to ask something, so the request itself skips DNS + TCP + TLS (~150–250 ms on mobile).
     * Fire-and-forget, at most once every 30 s; any HTTP response at all leaves a warm pooled connection.
     */
    fun prewarm(baseUrl: String) {
        val now = System.currentTimeMillis()
        if (now - warmedAt < 30_000 || !baseUrl.startsWith("https://")) return
        warmedAt = now
        runCatching {
            client.newCall(Request.Builder().url(baseUrl.trimEnd('/') + "/").head().build()).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: java.io.IOException) { warmedAt = 0L }
                override fun onResponse(call: Call, response: Response) = response.close()
            })
        }
    }
}

/**
 * Executes [request] so that cancelling the calling coroutine cancels the HTTP call,
 * then hands each SSE `data:` payload to [onData].
 */
internal suspend fun OkHttpClient.streamSse(request: Request, onData: suspend (event: String?, data: String) -> Boolean) {
    val call: Call = newCall(request)
    val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
    try {
        call.execute().use { resp: Response ->
            if (!resp.isSuccessful) {
                val body = runCatching { resp.body.string() }.getOrDefault("")
                throw LlmException("HTTP ${resp.code}: ${extractError(body)}")
            }
            val source = resp.body.source()
            var event: String? = null
            while (true) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        if (!onData(event, data)) break
                    }
                    line.isEmpty() -> event = null
                }
            }
        }
    } finally {
        handle?.dispose()
    }
}

private fun extractError(body: String): String {
    val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    val err = obj?.get("error")
    val msg = when (err) {
        is JsonObject -> err.raw("message")
        is JsonPrimitive -> err.contentOrNull
        else -> null
    } ?: obj?.raw("message")
    return (msg ?: body).take(240)
}

internal fun JsonObject.raw(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun parseObject(s: String): JsonObject? = runCatching { json.parseToJsonElement(s) as? JsonObject }.getOrNull()
