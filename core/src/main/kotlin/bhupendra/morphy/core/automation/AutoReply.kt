package bhupendra.morphy.core.automation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.LocalTime
import java.util.UUID

enum class ReplyMode {
    /** Send [ReplyRule.text] verbatim. */
    Fixed,
    /** Let the model write the reply following [ReplyRule.text] as the owner's instruction. Also detects emergencies. */
    Ai,
    /** Never reply — only watch for emergencies and raise the alarm. */
    Watch;

    companion object {
        fun parse(s: String?): ReplyMode = when (s?.lowercase()) {
            "fixed", "static", "text" -> Fixed
            "watch", "guard", "alert", "monitor" -> Watch
            else -> Ai
        }
    }
}

data class ReplyRule(
    val id: String = UUID.randomUUID().toString().take(8),
    /** Package to match, or null for any messaging app. */
    val appPackage: String? = null,
    val appLabel: String? = null,
    /** Sender / conversation name; case-insensitive substring, or a regex. */
    val sender: String? = null,
    /** Only when the message matches this (substring or regex). */
    val keyword: String? = null,
    val mode: ReplyMode = ReplyMode.Ai,
    /** Fixed reply text, or the owner's instruction for AI replies. */
    val text: String? = null,
    /** "HH:mm-HH:mm", may wrap past midnight. Null = always. */
    val window: String? = null,
    val includeGroups: Boolean = false,
    val enabled: Boolean = true,
) {
    fun describe(): String = buildString {
        append(when (mode) { ReplyMode.Fixed -> "Reply"; ReplyMode.Ai -> "AI-reply"; ReplyMode.Watch -> "Watch for emergencies" })
        append(" to ")
        append(sender ?: "anyone")
        append(" on ")
        append(appLabel ?: appPackage ?: "any app")
        keyword?.let { append(" mentioning “$it”") }
        window?.let { append(", $it") }
        when (mode) {
            ReplyMode.Fixed -> text?.let { append(": “$it”") }
            ReplyMode.Ai -> text?.let { append(" — $it") }
            ReplyMode.Watch -> Unit
        }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id); appPackage?.let { put("app", it) }; appLabel?.let { put("appLabel", it) }
        sender?.let { put("sender", it) }; keyword?.let { put("keyword", it) }
        put("mode", mode.name); text?.let { put("text", it) }; window?.let { put("window", it) }
        put("groups", includeGroups); put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JsonObject): ReplyRule? {
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            fun b(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull
            return ReplyRule(
                id = s("id") ?: return null,
                appPackage = s("app"), appLabel = s("appLabel"), sender = s("sender"), keyword = s("keyword"),
                mode = ReplyMode.parse(s("mode")), text = s("text"), window = s("window"),
                includeGroups = b("groups") ?: false, enabled = b("enabled") ?: true,
            )
        }
    }
}

/** A message extracted from a notification, ready for rule matching. */
data class IncomingMessage(
    val pkg: String,
    val appLabel: String,
    /** Chat title: the person for 1:1 chats, the group name for groups. */
    val conversation: String,
    val sender: String,
    val text: String,
    val isGroup: Boolean,
    /** Recent thread, oldest first: sender to text. */
    val history: List<Pair<String, String>> = emptyList(),
    /** The app's own timestamp for this message (0 if unknown) — two identical "Hi"s are still two messages. */
    val time: Long = 0,
) {
    val threadKey: String get() = "$pkg/$conversation"
    val identity: String get() = "$text@$time"
}

object RuleMatcher {

    fun firstMatch(rules: List<ReplyRule>, msg: IncomingMessage, now: LocalTime = LocalTime.now()): ReplyRule? =
        rules.firstOrNull { matches(it, msg, now) }

    fun matches(rule: ReplyRule, msg: IncomingMessage, now: LocalTime = LocalTime.now()): Boolean {
        if (!rule.enabled) return false
        if (msg.isGroup && !rule.includeGroups && rule.mode != ReplyMode.Watch) return false
        if (rule.appPackage != null && rule.appPackage != msg.pkg) return false
        if (!inWindow(rule.window, now)) return false
        if (rule.sender != null && listOf(msg.sender, msg.conversation).none { textMatches(rule.sender, it) }) return false
        if (rule.keyword != null && !textMatches(rule.keyword, msg.text)) return false
        return true
    }

    fun textMatches(pattern: String, text: String): Boolean {
        if (text.contains(pattern, ignoreCase = true)) return true
        return runCatching { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text) }.getOrDefault(false)
    }

    fun inWindow(window: String?, now: LocalTime): Boolean {
        if (window.isNullOrBlank()) return true
        val parts = window.split('-').map { it.trim() }
        if (parts.size != 2) return true
        val (start, end) = parts.map { p ->
            runCatching { LocalTime.parse(if (p.length == 4) "0$p" else p) }.getOrNull() ?: return true
        }
        return if (start <= end) now in start..end else now >= start || now <= end
    }
}

/**
 * Keeps auto-replies conversational and loop-proof. A person can send several messages in a row and each
 * burst still gets an answer (the engine coalesces bursts); what's capped is volume: a short gap between
 * replies and a per-hour ceiling, which is what stops two auto-repliers ping-ponging forever.
 */
class RateLimiter(
    private val minGapMs: Long = 8_000,
    private val maxPerHour: Int = 20,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sent = HashMap<String, ArrayDeque<Long>>()
    private val seenIds = HashMap<String, ArrayDeque<String>>()

    @Synchronized
    fun allow(threadKey: String): Boolean {
        val now = clock()
        val times = sent.getOrPut(threadKey) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() > 3_600_000) times.removeFirst()
        if (times.isNotEmpty() && now - times.last() < minGapMs) return false
        return times.size < maxPerHour
    }

    @Synchronized
    fun record(threadKey: String) { sent.getOrPut(threadKey) { ArrayDeque() }.addLast(clock()) }

    /**
     * True the first time a message (by identity: text + the app's timestamp) is seen in a thread. Apps re-post
     * the same notification on unrelated updates; genuinely repeated messages ("Hi", "Hi") have new timestamps.
     */
    @Synchronized
    fun seen(threadKey: String, identity: String): Boolean {
        val ids = seenIds.getOrPut(threadKey) { ArrayDeque() }
        if (identity in ids) return false
        ids.addLast(identity)
        if (ids.size > 50) ids.removeFirst()
        return true
    }
}

data class Verdict(val urgent: Boolean, val reason: String?, val reply: String)

object VerdictParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val urgentTag = Regex("""\[URGENT(?::\s*([^\]]*))?]""", RegexOption.IGNORE_CASE)

    /** Accepts the JSON we ask for, the legacy "[URGENT: reason] reply" format, or plain text. */
    fun parse(raw: String): Verdict {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val obj = runCatching { json.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject }.getOrNull()
            if (obj != null) {
                fun s(k: String) = (obj[k] as? JsonPrimitive)?.contentOrNull
                return Verdict(
                    urgent = (obj["urgent"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } ?: false,
                    reason = s("reason")?.takeIf { it.isNotBlank() },
                    reply = s("reply")?.trim()?.trim('"').orEmpty(),
                )
            }
        }
        urgentTag.find(text)?.let { m ->
            return Verdict(true, m.groupValues[1].trim().ifEmpty { null }, text.replace(m.value, "").trim().trim('"'))
        }
        if (text.startsWith("[") && text.endsWith("]")) return Verdict(false, null, "")
        return Verdict(false, null, text.trim('"'))
    }
}

fun rulesToJson(rules: List<ReplyRule>): String = JsonArray(rules.map { it.toJson() }).toString()

fun rulesFromJson(s: String): List<ReplyRule> =
    runCatching { (Json.parseToJsonElement(s) as JsonArray).mapNotNull { (it as? JsonObject)?.let(ReplyRule::fromJson) } }
        .getOrDefault(emptyList())
