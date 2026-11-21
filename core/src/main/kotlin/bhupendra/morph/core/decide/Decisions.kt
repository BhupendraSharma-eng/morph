package bhupendra.morph.core.decide

import bhupendra.morph.core.llm.Http
import bhupendra.morph.core.llm.LlmEvent
import bhupendra.morph.core.llm.LlmProvider
import bhupendra.morph.core.llm.LlmRequest
import bhupendra.morph.core.llm.Msg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/*
 * Decision models ("System One"): instead of generating text, they answer closed questions with
 * calibrated probabilities in a few hundred ms, and output is free. Morph uses them wherever it needs a
 * *choice* (is this urgent? what kind of message? home or stay?) rather than *words*.
 */

sealed interface Question { val instructions: String }
/** Probability that the statement is true. */
data class YesNo(override val instructions: String, val yes: String? = null, val no: String? = null) : Question
/** Pick one option. Keys are returned; descriptions guide the model (option keys are never shown to it alone). */
data class Pick(override val instructions: String, val options: Map<String, String>) : Question
/** Ordered levels, lowest first; the answer is a probability-weighted level index. */
data class Rate(override val instructions: String, val levels: List<String>) : Question

data class Picked(val choice: String, val probabilities: Map<String, Double>, val confidence: Double)
data class Rated(val score: Double, val levels: Int, val confidence: Double) {
    /** Score normalised to 0..1. */
    val fraction: Double get() = if (levels <= 1) 0.0 else score / (levels - 1)
}

/** Typed answers, keyed by question name. */
class Decision(private val answers: Map<String, Any>, val model: String, val costUsd: Double? = null) {
    fun yes(key: String): Double = answers[key] as? Double ?: 0.0
    fun pick(key: String): Picked? = answers[key] as? Picked
    fun rate(key: String): Rated? = answers[key] as? Rated
}

/** Builder so call sites read like the decision they're making. */
class Questions {
    internal val all = LinkedHashMap<String, Question>()
    fun yesNo(key: String, instructions: String, yes: String? = null, no: String? = null) { all[key] = YesNo(instructions, yes, no) }
    fun pick(key: String, instructions: String, vararg options: Pair<String, String>) { all[key] = Pick(instructions, options.toMap()) }
    fun rate(key: String, instructions: String, levels: List<String>) { all[key] = Rate(instructions, levels) }
}

interface DecisionModel {
    val id: String
    suspend fun decide(state: JsonElement, questions: Map<String, Question>): Decision
}

suspend fun DecisionModel.decide(state: JsonElement, build: Questions.() -> Unit): Decision =
    decide(state, Questions().apply(build).all)

class DecisionException(message: String) : Exception(message)

/**
 * TypeSafe Jev through OpenRouter's Decisions endpoint. Pinned to a version: the floating alias moves with
 * releases, and calibrated thresholds (e.g. "urgent ≥ 0.5") belong to a specific model.
 */
class JevDecisions(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val endpoint: String = "https://openrouter.ai/api/alpha/decisions",
    private val client: OkHttpClient = Http.client,
) : DecisionModel {
    override val id = model
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun decide(state: JsonElement, questions: Map<String, Question>): Decision = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", model)
            put("state", state)
            put("questions", JsonObject(questions.mapValues { (_, q) -> encode(q) }))
        }
        val req = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = client.newCall(req).execute().use { resp ->
            val t = resp.body.string()
            if (!resp.isSuccessful) throw DecisionException("HTTP ${resp.code}: ${t.take(200)}")
            t
        }
        parse(json.parseToJsonElement(text) as JsonObject, questions)
    }

    companion object {
        const val DEFAULT_MODEL = "typesafe/jev-1.14"

        fun encode(q: Question): JsonObject = buildJsonObject {
            when (q) {
                is YesNo -> {
                    put("type", "noul"); put("instructions", q.instructions)
                    if (q.yes != null && q.no != null) put("criteria", buildJsonObject { put("true", q.yes); put("false", q.no) })
                }
                is Pick -> {
                    put("type", "choice"); put("instructions", q.instructions)
                    put("criteria", JsonObject(q.options.mapValues { JsonPrimitive(it.value) }))
                }
                is Rate -> {
                    put("type", "score"); put("instructions", q.instructions)
                    put("criteria", buildJsonArray { q.levels.forEach { add(JsonPrimitive(it)) } })
                }
            }
        }

        fun parse(root: JsonObject, questions: Map<String, Question>): Decision {
            val answers = root["answers"] as? JsonObject ?: throw DecisionException("no answers")
            fun d(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
            val out = HashMap<String, Any>()
            questions.forEach { (key, q) ->
                val a = answers[key] as? JsonObject ?: return@forEach
                when (q) {
                    is YesNo -> d(a, "noul")?.let { out[key] = it }
                    is Pick -> (a["choice"] as? JsonPrimitive)?.contentOrNull?.let { c ->
                        val probs = (a["probabilities"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it } }?.toMap().orEmpty()
                        out[key] = Picked(c, probs, d(a, "confidence") ?: probs[c] ?: 0.0)
                    }
                    is Rate -> d(a, "score")?.let { out[key] = Rated(it, q.levels.size, d(a, "confidence") ?: 0.0) }
                }
            }
            val usage = root["usage"] as? JsonObject
            return Decision(out, (root["model"] as? JsonPrimitive)?.contentOrNull ?: "", usage?.let { d(it, "cost") })
        }
    }
}

/**
 * Fallback for brains without a decision endpoint: the same typed questions, answered by the fast LLM as
 * JSON. Slower and costlier than Jev, but every call site works with any provider.
 */
class LlmDecisions(private val provider: LlmProvider) : DecisionModel {
    override val id = "llm:${provider.id}"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun decide(state: JsonElement, questions: Map<String, Question>): Decision {
        val spec = questions.entries.joinToString("\n") { (k, q) ->
            when (q) {
                is YesNo -> "\"$k\": probability 0..1 that this is true — ${q.instructions}"
                is Pick -> "\"$k\": one of ${q.options.keys.joinToString { "\"$it\"" }} — ${q.instructions} (" +
                    q.options.entries.joinToString("; ") { "${it.key}: ${it.value}" } + ")"
                is Rate -> "\"$k\": a number 0..${q.levels.size - 1} — ${q.instructions} (levels: " +
                    q.levels.mapIndexed { i, l -> "$i=$l" }.joinToString(", ") + ")"
            }
        }
        val sys = "Answer each question about the state. Reply with ONLY a JSON object with exactly these keys:\n$spec"
        val done = provider.stream(LlmRequest(sys, listOf(Msg.User(state.toString())), emptyList(), maxTokens = 800, reasoningEffort = "low"))
            .filterIsInstance<LlmEvent.Completed>().first()
        val raw = done.text.let { t -> t.substring(t.indexOf('{').coerceAtLeast(0), (t.lastIndexOf('}') + 1).coerceAtLeast(0)) }
        val o = runCatching { json.parseToJsonElement(raw) as JsonObject }.getOrNull() ?: throw DecisionException("unparseable")
        val out = HashMap<String, Any>()
        questions.forEach { (k, q) ->
            val v = o[k] as? JsonPrimitive ?: return@forEach
            when (q) {
                is YesNo -> v.doubleOrNull?.let { out[k] = it.coerceIn(0.0, 1.0) }
                is Pick -> v.contentOrNull?.takeIf { it in q.options }?.let { out[k] = Picked(it, mapOf(it to 1.0), 0.7) }
                is Rate -> v.doubleOrNull?.let { out[k] = Rated(it.coerceIn(0.0, q.levels.size - 1.0), q.levels.size, 0.7) }
            }
        }
        return Decision(out, id)
    }
}

