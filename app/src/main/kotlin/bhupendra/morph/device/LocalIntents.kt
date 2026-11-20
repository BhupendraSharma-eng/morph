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

    fun match(input: String): Match? {
        val text = input.trim()
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
