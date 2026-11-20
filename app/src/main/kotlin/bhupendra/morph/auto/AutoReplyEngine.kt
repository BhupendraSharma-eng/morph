package bhupendra.morph.auto

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import bhupendra.morph.agent.AgentRuntime
import bhupendra.morph.agent.Ledger
import bhupendra.morph.agent.Origin
import bhupendra.morph.core.agent.PriorityLane
import bhupendra.morph.core.automation.IncomingMessage
import bhupendra.morph.core.llm.LlmProvider
import bhupendra.morph.core.automation.RateLimiter
import bhupendra.morph.core.automation.ReplyMode
import bhupendra.morph.core.automation.ReplyRule
import bhupendra.morph.core.automation.RuleMatcher
import bhupendra.morph.core.automation.Verdict
import bhupendra.morph.core.automation.VerdictParser
import bhupendra.morph.core.automation.rulesFromJson
import bhupendra.morph.core.automation.rulesToJson
import bhupendra.morph.core.llm.LlmEvent
import bhupendra.morph.core.llm.LlmRequest
import bhupendra.morph.core.llm.Msg
import bhupendra.morph.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

sealed interface AutoEvent {
    val at: Long
    data class Replied(val to: String, val app: String, val incoming: String, val reply: String, val rule: ReplyRule,
                       override val at: Long = System.currentTimeMillis()) : AutoEvent
    data class Urgent(val emergency: Emergency, val reply: String?, override val at: Long = System.currentTimeMillis()) : AutoEvent
    data class Failed(val to: String, val app: String, val why: String, override val at: Long = System.currentTimeMillis()) : AutoEvent
}

/** Persists auto-reply rules as a small JSON file in app-private storage. */
class RuleStore(context: Context) {
    private val file = File(context.filesDir, "auto_reply_rules.json")
    private val _rules = MutableStateFlow(if (file.exists()) rulesFromJson(file.readText()) else emptyList())
    val rules: StateFlow<List<ReplyRule>> = _rules

    @Synchronized fun add(rule: ReplyRule) = save(_rules.value + rule)
    @Synchronized fun remove(id: String) = save(_rules.value.filterNot { it.id == id || id == "all" })
    @Synchronized fun setEnabled(id: String, on: Boolean) = save(_rules.value.map { if (it.id == id) it.copy(enabled = on) else it })

    private fun save(list: List<ReplyRule>) {
        _rules.value = list
        runCatching { file.writeText(rulesToJson(list)) }
    }
}

class AutoReplyEngine(
    private val context: Context,
    private val store: RuleStore,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val runtime: AgentRuntime,
    private val ledger: Ledger,
) {
    private val limiter = RateLimiter()
    private val _events = MutableSharedFlow<AutoEvent>(replay = 0, extraBufferCapacity = 32)
    val events: SharedFlow<AutoEvent> = _events
    /** Recent activity, newest first — shown on the home screen so nothing happens behind your back. */
    private val _log = MutableStateFlow<List<AutoEvent>>(emptyList())
    val log: StateFlow<List<AutoEvent>> = _log

    /** Called from the notification listener's binder thread: extract fast, then work off-thread. */
    fun onPosted(sbn: StatusBarNotification) {
        val rules = store.rules.value
        if (rules.none { it.enabled }) return
        val n = sbn.notification ?: return
        if (sbn.packageName == context.packageName) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val msg = extract(sbn) ?: return
        val rule = RuleMatcher.firstMatch(rules, msg) ?: return
        val reply = findReplyAction(n)
        val open = n.contentIntent

        if (rule.mode != ReplyMode.Watch && reply == null) return   // can't answer from this notification
        if (!limiter.seen(msg.threadKey + "#seen", msg.text)) return

        scope.launch(Dispatchers.IO) {
            try {
                handle(rule, msg, reply, open)
            } catch (e: Exception) {
                Log.w(TAG, "auto-reply failed", e)
                emit(AutoEvent.Failed(msg.conversation, msg.appLabel, e.message ?: "error"))
            }
        }
    }

    private suspend fun handle(rule: ReplyRule, msg: IncomingMessage, reply: Notification.Action?, open: PendingIntent?) {
        val verdict: Verdict = when (rule.mode) {
            ReplyMode.Fixed -> Verdict(false, null, rule.text.orEmpty())
            ReplyMode.Ai, ReplyMode.Watch -> ask(rule, msg) ?: return
        }

        if (verdict.urgent) {
            val holding = verdict.reply.ifBlank {
                "I've alerted ${ownerName()} that this is urgent — they'll get back to you as soon as possible."
            }
            val sent = rule.mode != ReplyMode.Watch && reply != null && limiter.allow(msg.threadKey, holding) && send(reply, holding)
            if (sent) limiter.record(msg.threadKey, holding)
            val e = Emergency(msg.sender.ifBlank { msg.conversation }, msg.appLabel, verdict.reason ?: "Urgent message", msg.text, open = open)
            UrgentAlert.raise(context, e)
            ledger.record(Origin.Automation, "Emergency alarm", "${e.who} on ${e.app}: ${msg.text}",
                "${e.reason}${if (sent) " · replied: $holding" else ""}")
            emit(AutoEvent.Urgent(e, if (sent) holding else null))
            return
        }

        if (rule.mode == ReplyMode.Watch || verdict.reply.isBlank() || reply == null) return
        if (!limiter.allow(msg.threadKey, verdict.reply)) return
        if (send(reply, verdict.reply)) {
            limiter.record(msg.threadKey, verdict.reply)
            ledger.record(Origin.Automation, "Auto-replied to ${msg.conversation}", "${msg.appLabel}: ${msg.text}", verdict.reply)
            emit(AutoEvent.Replied(msg.conversation, msg.appLabel, msg.text, verdict.reply, rule))
        } else {
            emit(AutoEvent.Failed(msg.conversation, msg.appLabel, "the app refused the reply"))
        }
    }

    private fun ownerName() = settings.state.value.userName.ifBlank { "the owner" }

    /** One tool-less model call returning a structured verdict. */
    private suspend fun ask(rule: ReplyRule, msg: IncomingMessage): Verdict? =
        // Emergency detection is time-critical: it jumps the background queue.
        runtime.background(PriorityLane.Priority.Urgent, Origin.Automation) { provider -> askWith(provider, rule, msg) }

    private suspend fun askWith(provider: LlmProvider, rule: ReplyRule, msg: IncomingMessage): Verdict? {
        val owner = ownerName()
        val system = """
You handle incoming messages on behalf of $owner, through their phone's assistant (Morph).
Respond with ONLY a JSON object: {"urgent": boolean, "reason": string, "reply": string}

urgent = true ONLY when a human must attend right now: medical emergency, accident, injury, someone in danger or
distress, a safety/security threat, a crisis, or a genuine "call me now / emergency" with real stakes.
Casual impatience ("reply fast", "where are you??") is NOT urgent. When urgent: "reason" is 2–5 words, and "reply"
is a calm, short holding message saying you've alerted $owner and they'll respond as soon as possible.

Otherwise ${if (rule.mode == ReplyMode.Watch) "set reply to \"\" (watch only, never reply)." else
            "write the reply following the owner's instruction: \"${rule.text ?: "Reply naturally and briefly on their behalf."}\""}
Reply rules: sound like a human, 1–2 short sentences, match the sender's language and script (Hinglish stays Hinglish),
never invent plans, commitments, facts or share private info. If asked whether this is a bot, say you're $owner's assistant.
For automated messages (OTPs, promos, delivery updates, bank alerts) set reply to "".
""".trim()
        val thread = msg.history.takeLast(8).joinToString("\n") { (who, text) -> "$who: $text" }
        val user = buildString {
            appendLine("App: ${msg.appLabel}${if (msg.isGroup) " (group: ${msg.conversation})" else ""}")
            appendLine("From: ${msg.sender}")
            if (thread.isNotBlank()) appendLine("Recent thread:\n$thread")
            append("New message: ${msg.text}")
        }
        val done = withTimeoutOrNull(45_000) {
            provider.stream(LlmRequest(system, listOf(Msg.User(user)), tools = emptyList(), maxTokens = 600))
                .filterIsInstance<LlmEvent.Completed>().first()
        } ?: return null
        return VerdictParser.parse(done.text)
    }

    private fun send(action: Notification.Action, text: String): Boolean {
        val inputs = action.remoteInputs ?: return false
        val intent = Intent()
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return runCatching { action.actionIntent.send(context, 0, intent); true }.getOrDefault(false)
    }

    private suspend fun emit(e: AutoEvent) {
        _log.value = (listOf(e) + _log.value).take(30)
        _events.emit(e)
    }

    // ---- Notification parsing --------------------------------------------------------------------

    private fun findReplyAction(n: Notification): Notification.Action? {
        val candidates = (n.actions.orEmpty().toList() + Notification.WearableExtender(n).actions)
            .filter { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        return candidates.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY } ?: candidates.firstOrNull()
    }

    private fun extract(sbn: StatusBarNotification): IncomingMessage? {
        val n = sbn.notification
        val app = runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(sbn.packageName, PackageManager.ApplicationInfoFlags.of(0))
            ).toString()
        }.getOrDefault(sbn.packageName)

        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null && style.messages.isNotEmpty()) {
            val me = style.user.name?.toString()
            val last = style.messages.last()
            val lastSender = last.person?.name?.toString()
            // Never answer our own (or the owner's) messages — this is what prevents reply loops.
            if (last.person == null || lastSender == me) return null
            val text = last.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return null
            val conversation = style.conversationTitle?.toString()
                ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: lastSender.orEmpty()
            return IncomingMessage(
                pkg = sbn.packageName, appLabel = app,
                conversation = conversation, sender = lastSender.orEmpty(), text = text,
                isGroup = style.isGroupConversation,
                history = style.messages.takeLast(9).dropLast(1).map {
                    (it.person?.name?.toString() ?: me ?: "Me") to it.text?.toString().orEmpty()
                },
            )
        }

        // Non-MessagingStyle apps (some SMS apps): treat title as sender.
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() || text.isEmpty() || Regex("^\\d+ new messages?$").matches(text)) return null
        return IncomingMessage(sbn.packageName, app, title, title, text, isGroup = false)
    }

    companion object { private const val TAG = "AutoReply" }
}
