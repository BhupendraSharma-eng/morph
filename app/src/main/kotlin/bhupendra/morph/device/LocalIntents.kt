package bhupendra.morph.device

import bhupendra.morph.core.agent.Tool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Zero-latency fast path. Things that are unambiguous never wait for a model:
 * "spotify", "open maps", "torch on", "timer 10 min". Everything else goes to the agent.
 */
class LocalIntents(private val catalog: AppCatalog, private val tools: DeviceTools) {

    data class Match(val tool: Tool, val args: JsonObject)

    private val openVerb = Regex("^(open|launch|start|run|go to)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val torch = Regex("^(torch|flash ?light|flash|light)\\s*(on|off)?$|^(turn|switch)\\s+(on|off)\\s+(the\\s+)?(torch|flash ?light)$", RegexOption.IGNORE_CASE)
    private val timer = Regex("^(?:set\\s+(?:a\\s+)?)?timer\\s+(?:for\\s+)?(\\d+)\\s*(s|sec|secs|seconds?|m|min|mins|minutes?|h|hr|hours?)?$", RegexOption.IGNORE_CASE)

    private val alarm = Regex("^(?:set\\s+(?:an?\\s+)?alarm|alarm|wake\\s+me(?:\\s+up)?)\\s+(?:for\\s+|at\\s+)?(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.?m\\.?|p\\.?m\\.?)?(?:\\s+(?:tomorrow|today))?(?:\\s+(?:in\\s+the\\s+)?(morning|evening|night))?$", RegexOption.IGNORE_CASE)
    // Short and plain only: "call mom", "call +91 98…". Anything longer ("call mom and say…") is a job for the agent.
    private val call = Regex("^(?:call|dial|phone|ring)\\s+([\\p{L}][\\p{L}'.]*(?:\\s+[\\p{L}'.]+){0,2}|\\+?[\\d][\\d\\s-]{2,})$", RegexOption.IGNORE_CASE)
    private val media = Regex("^(play|pause|resume|stop|next|skip|previous|prev)(?:\\s+(?:the\\s+)?(?:music|song|track|it|this))?$", RegexOption.IGNORE_CASE)

    fun match(input: String): Match? {
        val text = input.trim().trimEnd('.', '!')
        alarm.find(text)?.let { m ->
            var h = m.groupValues[1].toInt(); val min = m.groupValues[2].toIntOrNull() ?: 0
            val mer = m.groupValues[3].lowercase().replace(".", ""); val part = m.groupValues[4].lowercase()
            if (h > 23 || min > 59) return@let
            when {
                mer == "pm" || part == "evening" || part == "night" -> if (h < 12) h += 12
                mer == "am" || part == "morning" -> if (h == 12) h = 0
                h in 1..11 -> {   // "alarm 7": the next 7 o'clock that hasn't passed yet
                    val now = java.time.LocalTime.now()
                    if (java.time.LocalTime.of(h, min) < now && java.time.LocalTime.of(h + 12, min) > now) h += 12
                }
            }
            return Match(tools.alarm, JsonObject(mapOf("hour" to JsonPrimitive(h), "minute" to JsonPrimitive(min))))
        }
        call.find(text)?.let { m ->
            val who = m.groupValues[1].trim()
            return if (who.first().isDigit() || who.startsWith("+")) Match(tools.dial, obj("number" to who.filter { it.isDigit() || it == '+' }))
            else Match(tools.callContact, obj("name" to who))
        }
        media.find(text)?.let { m ->
            val action = when (m.groupValues[1].lowercase()) { "next", "skip" -> "next"; "previous", "prev" -> "previous"; else -> "play_pause" }
            return Match(tools.media, obj("action" to action))
        }
        openVerb.find(text)?.let { m ->
            val target = m.groupValues[2].trim()
            val app = catalog.resolve(target)
            if (app != null) return Match(tools.openApp, obj("app" to app.pkg))
        }
        torch.find(text)?.let { m ->
            val word = (m.groupValues[2].ifEmpty { m.groupValues[4] }).lowercase()
            val on = when (word) { "on" -> true; "off" -> false; else -> !tools.torchOn }
            return Match(tools.flashlight, JsonObject(mapOf("on" to JsonPrimitive(on))))
        }
        timer.find(text)?.let { m ->
            val n = m.groupValues[1].toInt()
            val unit = m.groupValues[2].lowercase()
            val minutes = when {
                unit.startsWith("h") -> n * 60.0
                unit.startsWith("s") -> n / 60.0
                else -> n.toDouble()
            }
            return Match(tools.timer, JsonObject(mapOf("minutes" to JsonPrimitive(minutes))))
        }
        // A bare app name ("spot", "whatsapp") — only when it's a confident single-app hit.
        if (!text.contains(' ') || text.length <= 14) {
            val hits = catalog.search(text, 2)
            val top = hits.firstOrNull()
            if (top != null && (top.norm == normalize(text) || (top.norm.startsWith(normalize(text)) && text.length >= 3 && hits.size == 1))) {
                return Match(tools.openApp, obj("app" to top.pkg))
            }
        }
        return null
    }

    private fun obj(vararg p: Pair<String, String>) = JsonObject(p.associate { it.first to JsonPrimitive(it.second) })
}
