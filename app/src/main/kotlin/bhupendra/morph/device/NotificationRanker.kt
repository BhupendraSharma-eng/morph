package bhupendra.morph.device

import bhupendra.morph.agent.AgentRuntime
import bhupendra.morph.agent.Origin
import bhupendra.morph.core.agent.PriorityLane
import bhupendra.morph.core.decide.decide
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Ranks notifications by how soon you should look at them, using the decision model: every new notification
 * becomes one question in a single batched call (~1 s, fractions of a cent), and each is scored only once.
 */
class NotificationRanker(private val runtime: AgentRuntime) {
    /** Notification identity (key + content) → importance, 0 (ignore) … 1 (now). */
    private val _scores = MutableStateFlow<Map<String, Double>>(emptyMap())
    val scores: StateFlow<Map<String, Double>> = _scores

    /** Chats are scored per conversation by For You (same call that decides "needs you"); others per notification. */
    fun idOf(n: Notif) = n.thread?.let { "t:$it" } ?: "${n.key}#${(n.title + n.text).hashCode()}"

    /** For You's verdict on a chat's latest message, folded in so one decision call covers both. */
    fun scoreChat(thread: String, fraction: Double) {
        _scores.value = (_scores.value + ("t:$thread" to fraction)).entries.toList().takeLast(200).associate { it.toPair() }
    }

    suspend fun rank(items: List<Notif>) {
        // Media has its own player card and its text changes with every track — ranking it is pure waste.
        // Chats get their score from For You's call a few seconds after they arrive; only rank one ourselves if that
        // never came (e.g. it was already in the shade when Morph started).
        val now = System.currentTimeMillis()
        val fresh = items.filterNot { it.isMedia || (it.thread != null && now - it.time < CHAT_GRACE_MS) }
            .take(15).filter { idOf(it) !in _scores.value }
        if (fresh.isEmpty()) return
        val qid = fresh.mapIndexed { i, n -> "n$i" to n }.toMap()
        val result = runtime.decide(PriorityLane.Priority.Proactive, Origin.Proactive, preferLocal = true) { d ->
            d.decide(buildJsonObject {
                put("notifications", buildJsonArray {
                    qid.forEach { (id, n) -> add(buildJsonObject { put("id", id); put("app", n.app); put("title", n.title); put("text", n.text.take(300)) }) }
                })
            }) {
                qid.keys.forEach { id ->
                    rate(id, "Consider ONLY the notification with id $id in the state. How soon should the phone's owner look at it?", LEVELS)
                }
            }
        } ?: return
        _scores.value = (_scores.value + qid.mapNotNull { (id, n) -> result.rate(id)?.let { idOf(n) to it.fraction } })
            .entries.toList().takeLast(200).associate { it.toPair() }
    }

    companion object {
        val LEVELS = listOf("can be ignored", "worth a look today", "should see within the hour", "needs attention now")
        /** ≥ "worth a look today, leaning sooner" — calibrated on the live ranking test (Mom/bank papers 0.63, autopay 0.53, delivery 0.17). */
        const val NEEDS_YOU = 0.5
        private const val CHAT_GRACE_MS = 60_000L
    }
}
