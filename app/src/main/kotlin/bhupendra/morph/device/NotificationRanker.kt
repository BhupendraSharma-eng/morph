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

    fun idOf(n: Notif) = "${n.key}#${(n.title + n.text).hashCode()}"

    suspend fun rank(items: List<Notif>) {
        val fresh = items.take(15).filter { idOf(it) !in _scores.value }
        if (fresh.isEmpty()) return
        val qid = fresh.mapIndexed { i, n -> "n$i" to n }.toMap()
        val result = runtime.decide(PriorityLane.Priority.Proactive, Origin.Proactive) { d ->
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
    }
}
