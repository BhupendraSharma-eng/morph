package bhupendra.morph.auto

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import bhupendra.morph.agent.AgentRuntime
import bhupendra.morph.agent.Origin
import bhupendra.morph.core.agent.PriorityLane
import bhupendra.morph.core.automation.IncomingMessage
import bhupendra.morph.core.decide.decide
import bhupendra.morph.core.llm.LlmEvent
import bhupendra.morph.core.llm.LlmRequest
import bhupendra.morph.core.llm.Msg
import bhupendra.morph.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One thing a person needs from you, distilled to a line, with replies you can send in a tap. */
data class ForYouItem(
    val threadKey: String,
    val who: String,
    val app: String,
    val pkg: String,
    val summary: String,
    val replies: List<String>,
    val at: Long,
    /** Optional control for a structured answer (time, choice, number…) — the agent drawing UI for your input. */
    val ask: bhupendra.morph.core.surface.Block.Input? = null,
    /** Reply in the sender's language with "{value}" where your answer goes, e.g. "Main {value} tak aa jaunga". */
    val template: String? = null,
)

/** Sends text through a notification's inline-reply action (WhatsApp, Telegram, SMS…). */
object RemoteReply {
    fun send(context: Context, action: Notification.Action, text: String): Boolean {
        val inputs = action.remoteInputs ?: return false
        val intent = Intent()
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return runCatching { action.actionIntent.send(context, 0, intent); true }.getOrDefault(false)
    }
}

/**
 * "For you": what people need from you, curated from every chat — not a log of what Morph did.
 * A cheap decision-model check runs on each incoming burst; only messages that need *you* get a one-line
 * summary (fast model). One live item per conversation; it clears itself when you reply.
 */
class ForYou(
    private val context: Context,
    private val runtime: AgentRuntime,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val file = File(context.filesDir, "for_you.json")
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<ForYouItem>> = _items

    private val replyActions = ConcurrentHashMap<String, Notification.Action>()
    private val openIntents = ConcurrentHashMap<String, PendingIntent>()
    private val pending = ConcurrentHashMap<String, Long>()
    /** What Morph itself sent per thread recently — so its own auto-replies don't count as "you replied". */
    private val sentByMorph = ConcurrentHashMap<String, Pair<String, Long>>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val trace = bhupendra.morph.operator.OperatorTrace.sink(context, "foryou-trace.log", "MorphForYou")

    /** Every chat we've seen with an inline-reply action: display name → thread. Refreshed on each notification. */
    private val chats = ConcurrentHashMap<String, Triple<String, String, Long>>()   // threadKey → (conversation, app, seenAt)

    fun remember(threadKey: String, conversation: String, app: String, reply: Notification.Action?, open: PendingIntent?) {
        reply?.let { replyActions[threadKey] = it; chats[threadKey] = Triple(conversation, app, System.currentTimeMillis()) }
        open?.let { openIntents[threadKey] = it }
        if (chats.size > 40) chats.entries.minByOrNull { it.value.third }?.let { chats.remove(it.key) }
    }

    /** Chats that can be answered instantly from their notification, most recent first. */
    fun replyable(): List<Pair<String, String>> =
        chats.entries.sortedByDescending { it.value.third }.map { it.key to "${it.value.first} (${it.value.second})" }

    /**
     * Reply instantly through the conversation's notification — no app opened. [to] is matched against chat names.
     * Returns null on success, or a reason it couldn't.
     */
    fun replyTo(to: String, text: String): String? {
        val q = to.trim().lowercase()
        val matches = chats.entries.filter { it.value.first.lowercase() == q }.ifEmpty {
            chats.entries.filter { it.value.first.lowercase().contains(q) || q.contains(it.value.first.lowercase()) }
        }
        if (matches.isEmpty()) return "no recent chat notification for \"$to\""
        if (matches.size > 1) return "ambiguous: " + matches.joinToString { it.value.first }
        val key = matches.single().key
        val action = replyActions[key] ?: return "that chat has no reply action"
        if (!RemoteReply.send(context, action, text)) return "the app refused the reply (notification may have expired)"
        remove(key)                                   // you answered: nothing left "for you" there
        return null
    }

    fun noteMorphSent(threadKey: String, text: String) { sentByMorph[threadKey] = text to System.currentTimeMillis() }

    /** The newest message in a thread is yours (and not something Morph just sent): you've handled it. */
    fun ownerReplied(threadKey: String, text: String?) {
        val morph = sentByMorph[threadKey]
        if (morph != null && morph.first == text && System.currentTimeMillis() - morph.second < 5 * 60_000) return
        if (_items.value.any { it.threadKey == threadKey }) { trace("cleared (you replied): $threadKey"); remove(threadKey) }
    }

    /**
     * The chat's notification is gone. Opening it, swiping it away, clearing all, or the app withdrawing it (you read
     * the chat in the app, or on another device) all mean you've seen it — so it no longer needs you here. The app
     * withdrawing it right after *Morph* replied doesn't count; snoozes and timeouts don't either.
     */
    fun notificationGone(threadKey: String, reason: Int) {
        if (_items.value.none { it.threadKey == threadKey }) return
        val byYou = when (reason) {
            android.service.notification.NotificationListenerService.REASON_CLICK,
            android.service.notification.NotificationListenerService.REASON_CANCEL,
            android.service.notification.NotificationListenerService.REASON_CANCEL_ALL,
            android.service.notification.NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED -> true
            android.service.notification.NotificationListenerService.REASON_APP_CANCEL,
            android.service.notification.NotificationListenerService.REASON_APP_CANCEL_ALL -> {
                val morph = sentByMorph[threadKey]
                morph == null || System.currentTimeMillis() - morph.second > 20_000
            }
            else -> false
        }
        if (byYou) { trace("cleared (handled outside Morph, reason $reason): $threadKey"); remove(threadKey) }
    }

    fun keepOnly(liveThreads: Set<String>) {
        val now = System.currentTimeMillis()
        val keep = _items.value.filter { it.threadKey in liveThreads || now - it.at < 60_000 }
        if (keep.size != _items.value.size) { trace("cleared ${_items.value.size - keep.size} handled while away"); save(keep) }
    }

    /** Things nobody acted on for a day stop being "for you" — they're stale, not urgent. */
    fun dropStale(maxAgeMs: Long = 24 * 60 * 60_000L) {
        val now = System.currentTimeMillis()
        val keep = _items.value.filter { now - it.at < maxAgeMs }
        if (keep.size != _items.value.size) { trace("cleared ${_items.value.size - keep.size} stale"); save(keep) }
    }

    /** Receives each chat's urgency (0..1) from the For You decision; wired to the notification ranker. */
    var onUrgency: ((thread: String, fraction: Double) -> Unit)? = null

    fun onMessage(msg: IncomingMessage, reply: Notification.Action?, open: PendingIntent?) {
        remember(msg.threadKey, msg.conversation, msg.appLabel, reply, open)
        val stamp = System.currentTimeMillis()
        pending[msg.threadKey] = stamp
        scope.launch(Dispatchers.IO) {
            delay(BURST_MS)                                   // read the whole burst, once
            if (pending[msg.threadKey] != stamp) return@launch
            runCatching { consider(msg) }.onFailure { trace("failed: ${it.message}") }
        }
    }

    private suspend fun consider(msg: IncomingMessage) {
        val who = msg.sender.ifBlank { msg.conversation }
        val thread = (msg.history.takeLast(6) + (who to msg.text)).joinToString("\n") { "${it.first}: ${it.second}" }
        val needs = runtime.decide(PriorityLane.Priority.Proactive, Origin.Proactive, preferLocal = true) { d ->
            val r = d.decide(buildJsonObject { put("app", msg.appLabel); put("from", who); put("recent_messages", thread) }) {
                yesNo("for_owner",
                    "Does the latest message need the phone owner's personal attention: a question only they can answer, a request " +
                        "or favour from them, a decision, or short information they should be told?",
                    yes = "asks the owner something, wants something from them, needs their decision, or tells them something they should know",
                    no = "greetings, reactions, small talk, 'ok'/'thanks', forwards and memes, automated or promotional messages")
                yesNo("automated", "Is this sent by a business, a bot or a system rather than a person?")
                // Same call also ranks the chat for home (saves the ranker a second request per message).
                rate("urgency", "Consider the latest message. How soon should the phone's owner look at it?",
                    bhupendra.morph.device.NotificationRanker.LEVELS)
            }
            // A chat only ranks as "needs you" if it actually does: small talk and bots are capped below the bar,
            // whatever the urgency question said (on Kev a "good morning" can rate ~0.55).
            val forOwner = r.yes("for_owner"); val automated = r.yes("automated")
            r.rate("urgency")?.let { u ->
                val needs = forOwner >= FOR_OWNER && automated < 0.6
                onUrgency?.invoke(msg.threadKey, if (needs) maxOf(u.fraction, 0.5) else minOf(u.fraction, 0.35))
            }
            forOwner to automated
        } ?: return
        // Message text goes in by concatenation, never into the format string ("40% off" would be read as a pattern).
        trace("${msg.conversation}: \"${msg.text.take(50)}\" " + "for_owner=%.2f automated=%.2f".format(needs.first, needs.second))
        if (needs.first < FOR_OWNER || needs.second >= 0.6) return

        val owner = settings.state.value.userName.ifBlank { "the owner" }
        val t0 = android.os.SystemClock.uptimeMillis()
        val done = runtime.background(PriorityLane.Priority.Proactive, Origin.Proactive) { provider ->
            withTimeoutOrNull(30_000) {
                provider.stream(LlmRequest(
                    system = "You curate a phone owner's inbox. From the chat below, write what $who needs from $owner as ONE line of " +
                        "at most 12 words, third person, specific (e.g. \"Rishi asks if you're up for cycling tomorrow\", " +
                        "\"Maa wants you to buy milk on the way home\"). Then 2-3 very short replies $owner could send, in the " +
                        "sender's language AND script (Hinglish written in English letters stays in English letters — never switch to " +
                        "Devanagari unless they wrote in it). If answering needs a specific value (a time, a date, a number, or picking between " +
                        "options they gave), ALSO add \"ask\": {\"kind\": \"time|date|stepper|choice|text\", \"label\": \"...\", " +
                        "\"options\": [...] (choice only), \"default\": \"...\"} and \"template\": a reply in their language with " +
                        "{value} where the answer goes ({value} is filled like \"7:30 PM\", \"Sat, 22 Nov\", \"3\" or the chosen option — " +
                        "don't add words like baje/o'clock around it). Reply with ONLY JSON: {\"summary\": \"...\", \"replies\": [\"...\"], " +
                        "\"ask\": {...} (optional), \"template\": \"...\" (optional)}",
                    messages = listOf(Msg.User("${msg.appLabel} chat with $who:\n$thread")),
                    tools = emptyList(), maxTokens = 600, reasoningEffort = "low",
                )).filterIsInstance<LlmEvent.Completed>().first()
            }
        } ?: return
        val raw = done.text.let { t -> t.substring(t.indexOf('{').coerceAtLeast(0), (t.lastIndexOf('}') + 1).coerceAtLeast(0)) }
        val o = runCatching { json.parseToJsonElement(raw) as JsonObject }.getOrNull() ?: return
        val summary = (o["summary"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() } ?: return
        val replies = (o["replies"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotBlank() } }
            .orEmpty().take(3)
        val ask = (o["ask"] as? JsonObject)?.let { a ->
            bhupendra.morph.core.surface.SurfaceParser.parse(buildJsonObject {
                put("blocks", JsonArray(listOf(JsonObject(a + ("type" to JsonPrimitive("input")) + ("id" to JsonPrimitive("value"))))))
            })?.blocks?.firstOrNull() as? bhupendra.morph.core.surface.Block.Input
        }
        val template = (o["template"] as? JsonPrimitive)?.contentOrNull?.takeIf { "{value}" in it }
        upsert(ForYouItem(msg.threadKey, who, msg.appLabel, msg.pkg, summary, replies, System.currentTimeMillis(),
            ask = if (template != null) ask else null, template = if (ask != null) template else null))
        trace("  ↳ for you (${android.os.SystemClock.uptimeMillis() - t0} ms): $summary")
    }

    /** Send a quick reply straight into the chat; false if the conversation's notification is gone. */
    fun quickReply(item: ForYouItem, text: String): Boolean {
        val action = replyActions[item.threadKey] ?: return false
        val ok = RemoteReply.send(context, action, text)
        if (ok) remove(item.threadKey)   // you replied (through Morph): done
        return ok
    }

    fun openIntent(item: ForYouItem): PendingIntent? = openIntents[item.threadKey]

    fun done(item: ForYouItem) = remove(item.threadKey)
    fun clearAll() = save(emptyList())

    @Synchronized private fun upsert(item: ForYouItem) =
        save((listOf(item) + _items.value.filterNot { it.threadKey == item.threadKey }).take(MAX))

    @Synchronized private fun remove(threadKey: String) = save(_items.value.filterNot { it.threadKey == threadKey })

    private fun save(list: List<ForYouItem>) {
        _items.value = list
        runCatching {
            file.writeText(buildJsonArray {
                list.forEach { i ->
                    add(buildJsonObject {
                        put("t", i.threadKey); put("w", i.who); put("a", i.app); put("p", i.pkg); put("s", i.summary); put("at", i.at)
                        put("r", JsonArray(i.replies.map(::JsonPrimitive)))
                        i.template?.let { put("tp", it) }
                        i.ask?.let { a ->
                            put("ask", bhupendra.morph.core.surface.SurfaceCodec.encode(
                                bhupendra.morph.core.surface.Surface(id = "a", blocks = listOf(a))))
                        }
                    })
                }
            }.toString())
        }
    }

    private fun load(): List<ForYouItem> = runCatching {
        (Json.parseToJsonElement(file.readText()) as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            ForYouItem(s("t"), s("w"), s("a"), s("p"), s("s"),
                (o["r"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                (o["at"] as? JsonPrimitive)?.longOrNull ?: 0,
                ask = (o["ask"] as? JsonObject)?.let { bhupendra.morph.core.surface.SurfaceCodec.decode(it)?.blocks?.firstOrNull() }
                    as? bhupendra.morph.core.surface.Block.Input,
                template = s("tp").ifBlank { null })
        }
    }.getOrDefault(emptyList())

    companion object {
        const val BURST_MS = 5_000L
        /** Calibrated on Jev: needs-you messages 0.94–0.98; greetings/emoji/thanks/forwards 0.04–0.06. */
        const val FOR_OWNER = 0.6
        const val MAX = 12
    }
}

/** The agent's instant reply path: straight through the chat notification, no app opened. */
class ReplyInChatTool(private val forYouRef: () -> ForYou) : bhupendra.morph.core.agent.Tool {
    override val spec = bhupendra.morph.core.agent.ToolSpec(
        name = "reply_in_chat",
        label = "Replying",
        risk = bhupendra.morph.core.agent.Risk.Act,
        description = "Send a reply into a chat INSTANTLY through its notification (WhatsApp, Telegram, SMS…) without opening " +
            "the app. Always prefer this over operate_phone when the chat is in the 'Replyable chats' list. Use the chat's name.",
        parameters = bhupendra.morph.core.agent.schema(listOf("to", "text")) {
            string("to", "Chat or person name as listed, e.g. 'Maa' or 'Walk+Cycle'")
            string("text", "The message to send, in the language the chat uses")
        },
    )

    override suspend fun run(args: JsonObject): bhupendra.morph.core.agent.ToolOutcome {
        val to = (args["to"] as? JsonPrimitive)?.contentOrNull ?: return bhupendra.morph.core.agent.ToolOutcome("Error: to required")
        val text = (args["text"] as? JsonPrimitive)?.contentOrNull ?: return bhupendra.morph.core.agent.ToolOutcome("Error: text required")
        val error = kotlinx.coroutines.withContext(Dispatchers.Main) { forYouRef().replyTo(to, text) }
        return bhupendra.morph.core.agent.ToolOutcome(
            if (error == null) "Sent to $to: \"$text\" (instantly, via the notification)."
            else "Couldn't reply instantly ($error). If needed, use operate_phone to send it in the app."
        )
    }
}
