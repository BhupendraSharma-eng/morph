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
import bhupendra.morph.core.decide.decide
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

sealed interface AutoEvent {
    val at: Long
    data class Replied(val to: String, val app: String, val incoming: String, val reply: String, val rule: ReplyRule,
                       override val at: Long = System.currentTimeMillis()) : AutoEvent
    data class Urgent(val emergency: Emergency, val reply: String?, override val at: Long = System.currentTimeMillis()) : AutoEvent
    data class Failed(val to: String, val app: String, val why: String, override val at: Long = System.currentTimeMillis()) : AutoEvent
    /** Someone said "urgent", explained, and it could wait: Morph handled it and tells you calmly. */
    data class Screened(val who: String, val app: String, val explanation: String, val reply: String?,
                        override val at: Long = System.currentTimeMillis()) : AutoEvent
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
    private val forYou: ForYou,
) {
    private val limiter = RateLimiter()
    /** adb shell cat /sdcard/Android/data/bhupendra.morph/files/autoreply-trace.log */
    private val trace = bhupendra.morph.operator.OperatorTrace.sink(context, "autoreply-trace.log", "MorphAutoReply")
    /** Threads where someone claimed urgency and Morph asked what it's about. */
    private data class Screening(val since: Long, var nudges: Int = 0)
    private val screening = java.util.concurrent.ConcurrentHashMap<String, Screening>()
    private fun activeScreening(key: String) = screening[key]?.takeIf { System.currentTimeMillis() - it.since < SCREEN_MS }

    /** Latest pending reply per thread (burst coalescing). */
    private val pendingReply = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val _events = MutableSharedFlow<AutoEvent>(replay = 0, extraBufferCapacity = 32)
    val events: SharedFlow<AutoEvent> = _events
    /** Recent activity, newest first — shown on the home screen so nothing happens behind your back. */
    private val _log = MutableStateFlow<List<AutoEvent>>(emptyList())
    val log: StateFlow<List<AutoEvent>> = _log

    /** Called from the notification listener's binder thread: extract fast, then work off-thread. */
    fun onPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName == context.packageName) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (isLiveCall(sbn)) return   // a ringing or ongoing call (yours or theirs) isn't a message; missed calls still count
        val msg = extract(sbn)
        if (msg == null) {
            // The newest message in the chat is yours: anything "for you" from that chat is handled.
            ownerLatest(sbn)?.let { (key, text) -> forYou.ownerReplied(key, text) }
            return
        }
        val reply = findReplyAction(n)
        val open = n.contentIntent
        forYou.remember(msg.threadKey, msg.conversation, msg.appLabel, reply, open)   // keeps instant replies fresh
        // Curate what people need from you — every chat, rules or not.
        if (limiter.seen("foryou:" + msg.threadKey, msg.identity)) forYou.onMessage(msg, reply, open)

        val rules = store.rules.value
        if (rules.none { it.enabled }) return
        val rule = RuleMatcher.firstMatch(rules, msg) ?: return

        if (rule.mode != ReplyMode.Watch && reply == null) return   // can't answer from this notification
        if (!limiter.seen(msg.threadKey, msg.identity)) return

        scope.launch(Dispatchers.IO) {
            try {
                handle(rule, msg, reply, open)
            } catch (e: Exception) {
                Log.w(TAG, "auto-reply failed", e)
                emit(AutoEvent.Failed(msg.conversation, msg.appLabel, e.message ?: "error"))
            }
        }
    }

    /** What the decision model concluded about an incoming message. */
    private data class Triage(val urgent: Double, val urgentAsk: Double, val kind: String?, val needsReply: Double, val reason: String?) {
        /** Real danger, or a person sincerely asking you to call/respond urgently ("kuch urgent hai, jaldi call karo"). */
        val emergency get() = urgent >= URGENT_THRESHOLD || urgentAsk >= URGENT_ASK_THRESHOLD
        val automated get() = kind == "automated"
    }

    /**
     * Decision-first: one fast typed call answers "is this an emergency? what kind? does it need a reply?".
     * Thresholds come from a 16-message eval on typesafe/jev-1.14 (5/5 emergencies ≥ 0.75, 0/11 false alarms ≤ 0.08).
     */
    private suspend fun triage(msg: IncomingMessage): Triage? = runtime.decide(PriorityLane.Priority.Urgent, Origin.Automation) { d ->
        val state = buildJsonObject {
            put("app", msg.appLabel); put("from", msg.sender.ifBlank { msg.conversation }); put("message", msg.text)
            if (msg.isGroup) put("group", msg.conversation)
            if (msg.history.isNotEmpty()) put("recent_thread", msg.history.takeLast(4).joinToString("\n") { "${it.first}: ${it.second}" })
        }
        val r = d.decide(state) {
            yesNo("urgent",
                "Is someone in danger or in a crisis right now (medical emergency, accident, injury, fire, safety threat) so that " +
                    "the phone's owner must drop everything and respond immediately? Impatience, work urgency and marketing words " +
                    "like URGENT do not count.",
                yes = "a real emergency involving a person's safety or health",
                no = "anything else, including impatient or pushy messages and promotions")
            pick("kind", "What kind of message is this?",
                "emergency" to "someone's safety or health is at risk right now",
                "request" to "asks the owner to do something or send something",
                "question" to "asks the owner a question or where they are",
                "plan" to "making or confirming plans",
                "chit_chat" to "greetings and casual conversation",
                "automated" to "sent by a business or system: OTP, bank alert, promotion, delivery update")
            yesNo("urgent_ask",
                "Is the sender, a real person, explicitly and sincerely asking the owner to call or respond urgently because " +
                    "something important happened? Marketing words like URGENT and mere impatience ('reply fast', 'where are you??') do not count.",
                yes = "a person sincerely says it is urgent / asks to call immediately about something important",
                no = "casual messages, impatience, promotions, automated alerts")
            yesNo("needs_reply", "Would a person normally reply to this message?")
            pick("what", "If this were an emergency, what kind would it be?",
                "medical" to "illness, injury, hospital", "accident" to "road or other accident",
                "danger" to "fire, break-in, threat, someone unsafe", "other" to "anything else")
        }
        val danger = r.yes("urgent")
        Triage(
            urgent = danger, urgentAsk = r.yes("urgent_ask"), kind = r.pick("kind")?.choice, needsReply = r.yes("needs_reply"),
            reason = if (danger >= URGENT_THRESHOLD) when (r.pick("what")?.choice) {
                "medical" -> "Medical emergency"; "accident" -> "Accident"; "danger" -> "Someone may be in danger"; else -> null
            } else "Asked you to call urgently",
        )
    }

    private suspend fun handle(rule: ReplyRule, msg: IncomingMessage, reply: Notification.Action?, open: PendingIntent?) {
        val t = triage(msg)
        trace("${msg.conversation} [${rule.mode}] \"${msg.text.take(60)}\" → " + (t?.let {
            "danger=%.2f ask=%.2f kind=%s reply=%.2f%s".format(it.urgent, it.urgentAsk, it.kind, it.needsReply, if (it.emergency) " ⇒ EMERGENCY" else "")
        } ?: "no decision model (LLM fallback)"))

        val key = msg.threadKey
        if (t != null) {
            // Real danger described: alarm now — never make someone in an emergency explain themselves.
            if (t.urgent >= URGENT_THRESHOLD) { screening.remove(key); return emergency(rule, msg, reply, open, t.reason) }
            val inScreening = activeScreening(key) != null
            // "It's urgent, call me" with no reason: ask what it's about, then decide (watch rules can't ask, so they alarm).
            if (!inScreening && t.urgentAsk >= URGENT_ASK_THRESHOLD) {
                return if (rule.mode == ReplyMode.Watch || reply == null) emergency(rule, msg, reply, open, "Asked you to call urgently")
                else startScreening(rule, msg, reply)
            }
            if (!inScreening) {
                if (rule.mode == ReplyMode.Watch) return                       // watching only: nothing else to do
                if (t.automated || t.needsReply < NEEDS_REPLY_MIN) { trace("  ↳ no reply needed"); return }   // OTPs, promos, "ok" — no LLM call
            }
        }

        // Coalesce bursts: wait a beat; if more messages arrive in this thread, only the newest one answers
        // (with the whole thread in view), like a person reading several texts and replying once.
        val myTurn = java.util.UUID.randomUUID().toString()
        pendingReply[msg.threadKey] = myTurn
        kotlinx.coroutines.delay(BURST_MS)
        if (pendingReply[msg.threadKey] != myTurn) { trace("  ↳ superseded by a newer message in the burst"); return }

        // They were asked what the urgency is about: this burst is their explanation.
        activeScreening(key)?.let { sc -> if (reply != null) return judgeScreening(rule, msg, reply, open, sc) }

        val text: String = when (rule.mode) {
            ReplyMode.Fixed -> rule.text.orEmpty()
            ReplyMode.Ai, ReplyMode.Watch -> {
                val v = ask(rule, msg) ?: return
                // Without a decision model, the LLM's own urgency verdict is the fallback.
                if (t == null && v.urgent) return emergency(rule, msg, reply, open, v.reason, v.reply)
                if (rule.mode == ReplyMode.Watch) return
                v.reply
            }
        }

        if (text.isBlank() || reply == null) return
        if (!limiter.allow(msg.threadKey)) { trace("  ↳ rate-limited (cap reached)"); return }
        if (send(msg.threadKey, reply, text)) {
            limiter.record(msg.threadKey)
            trace("  ↳ replied: ${text.take(80)}")
            ledger.record(Origin.Automation, "Auto-replied to ${msg.conversation}", "${msg.appLabel}: ${msg.text}", text)
            emit(AutoEvent.Replied(msg.conversation, msg.appLabel, msg.text, text, rule))
        } else {
            emit(AutoEvent.Failed(msg.conversation, msg.appLabel, "the app refused the reply"))
        }
    }

    /** Ask the person what the urgent matter is, in their language, before deciding whether to interrupt the owner. */
    private suspend fun startScreening(rule: ReplyRule, msg: IncomingMessage, reply: Notification.Action) {
        screening[msg.threadKey] = Screening(System.currentTimeMillis())
        val question = ask(rule, msg, goal = "They say it's urgent and want ${ownerName()} to call. Kindly ask what it's about so you " +
            "can decide whether to interrupt ${ownerName()} right now. One warm, short sentence in their language and script. " +
            "Don't promise a call yet. This is not an emergency verdict: set urgent=false.")?.reply?.takeIf { it.isNotBlank() }
            ?: "${ownerName()} is busy right now — what is it about? If it's important I'll tell them immediately."
        trace("  ↳ screening: asked what it's about")
        if (send(msg.threadKey, reply, question)) {
            limiter.record(msg.threadKey)
            ledger.record(Origin.Automation, "Asked ${msg.conversation} what's urgent", "${msg.appLabel}: ${msg.text}", question)
            emit(AutoEvent.Replied(msg.conversation, msg.appLabel, msg.text, question, rule))
        }
    }

    /**
     * Judge the explanation with the decision model. Interrupt-worthy (health, safety, police, money at risk, can't wait
     * an hour) → alarm. Still vague → ask once more; vague again → alarm (persistence counts). Otherwise Morph handles it
     * and leaves the owner a calm note. Eval on Jev: 10/10 (interrupt ≥ 0.83 vs ≤ 0.05; vague 0.96).
     */
    private suspend fun judgeScreening(rule: ReplyRule, msg: IncomingMessage, reply: Notification.Action, open: PendingIntent?, sc: Screening) {
        val who = msg.sender.ifBlank { msg.conversation }
        val j = runtime.decide(PriorityLane.Priority.Urgent, Origin.Automation) { d ->
            val r = d.decide(buildJsonObject {
                put("from", who)
                put("thread", (msg.history.takeLast(6) + (who to msg.text)).joinToString("\n") { "${it.first}: ${it.second}" })
            }) {
                yesNo("interrupt",
                    "The sender said it was urgent and was asked what it's about. Judging by their explanation, should the phone's owner be interrupted RIGHT NOW?",
                    yes = "health, safety, accident, police or legal trouble, money lost or at risk right now, a family crisis, or something time-critical that cannot wait an hour",
                    no = "it can wait a few hours: routine requests, errands, plans, documents, questions, chit-chat, curiosity")
                yesNo("vague", "Is the sender still NOT saying what the matter is (e.g. only 'just call', 'it's urgent', 'tell him to call')?")
            }
            r.yes("interrupt") to r.yes("vague")
        }
        trace("  ↳ screening verdict: " + (j?.let { "interrupt=%.2f vague=%.2f".format(it.first, it.second) } ?: "unavailable → alarm (safe default)"))
        val (interrupt, vague) = j ?: (1.0 to 0.0)
        when {
            interrupt >= INTERRUPT_THRESHOLD -> {
                screening.remove(msg.threadKey)
                emergency(rule, msg, reply, open, "Urgent: ${msg.text.take(60)}",
                    ask(rule, msg, goal = "They explained an important, urgent matter. Tell them calmly that you've alerted " +
                        "${ownerName()} right now and they'll respond as soon as possible. One or two short sentences, their language.")?.reply)
            }
            vague >= VAGUE_THRESHOLD && sc.nudges >= 1 -> {          // asked twice, still insisting without a reason: respect it
                screening.remove(msg.threadKey)
                emergency(rule, msg, reply, open, "Insists it's urgent (won't say why)")
            }
            vague >= VAGUE_THRESHOLD -> {
                sc.nudges++
                val again = ask(rule, msg, goal = "They still haven't said what it's about. Gently ask once more, briefly, so you can " +
                    "tell ${ownerName()} right away if it's important. Their language.")?.reply?.takeIf { it.isNotBlank() }
                    ?: "Could you tell me what it's about? I'll pass it on right away if it's important."
                if (send(msg.threadKey, reply, again)) { limiter.record(msg.threadKey); trace("  ↳ screening: asked again") }
            }
            else -> {                                                   // it can wait: handle it, and tell the owner calmly
                screening.remove(msg.threadKey)
                val handled = ask(rule, msg, goal = "They explained, and it can wait. Reassure them warmly that you'll pass it on and " +
                    "${ownerName()} will get back when free; if you can help directly, do. Don't promise a time. Their language.")?.reply
                    ?.takeIf { it.isNotBlank() }
                val sent = handled != null && send(msg.threadKey, reply, handled)
                if (sent) limiter.record(msg.threadKey)
                trace("  ↳ screening: handled, not urgent")
                ledger.record(Origin.Automation, "Handled ${who}'s \"urgent\" request", "${msg.appLabel}: ${msg.text}",
                    "Not urgent enough to interrupt${if (sent) " · replied: $handled" else ""}")
                emit(AutoEvent.Screened(who, msg.appLabel, msg.text, if (sent) handled else null))
            }
        }
    }

    /** The alarm fires first — before any text generation — then a calm holding reply goes out if allowed. */
    private suspend fun emergency(rule: ReplyRule, msg: IncomingMessage, reply: Notification.Action?, open: PendingIntent?,
                                  reason: String?, holdingFromLlm: String? = null) {
        val e = Emergency(msg.sender.ifBlank { msg.conversation }, msg.appLabel, reason ?: "Urgent message", msg.text, open = open)
        UrgentAlert.raise(context, e)
        var sent = false
        var holding: String? = null
        if (rule.mode != ReplyMode.Watch && reply != null) {
            holding = holdingFromLlm?.takeIf { it.isNotBlank() }
                ?: (if (rule.mode == ReplyMode.Ai) ask(rule, msg)?.reply?.takeIf { it.isNotBlank() } else null)
                ?: "I've alerted ${ownerName()} that this is urgent — they'll get back to you as soon as possible."
            // An emergency always gets its holding reply, cooldown or not.
            sent = send(msg.threadKey, reply, holding)
            if (sent) limiter.record(msg.threadKey)
        }
        ledger.record(Origin.Automation, "Emergency alarm", "${e.who} on ${e.app}: ${msg.text}",
            "${e.reason}${if (sent) " · replied: $holding" else ""}")
        emit(AutoEvent.Urgent(e, if (sent) holding else null))
    }

    private fun ownerName() = settings.state.value.userName.ifBlank { "the owner" }

    /** One tool-less model call returning a structured verdict. */
    private suspend fun ask(rule: ReplyRule, msg: IncomingMessage, goal: String? = null): Verdict? =
        // Emergency detection is time-critical: it jumps the background queue.
        runtime.background(PriorityLane.Priority.Urgent, Origin.Automation) { provider -> askWith(provider, rule, msg, goal) }

    private suspend fun askWith(provider: LlmProvider, rule: ReplyRule, msg: IncomingMessage, goal: String? = null): Verdict? {
        val owner = ownerName()
        val system = """
You handle incoming messages on behalf of $owner, through their phone's assistant (Morph).
Respond with ONLY a JSON object: {"urgent": boolean, "reason": string, "reply": string}

urgent = true ONLY when a human must attend right now: medical emergency, accident, injury, someone in danger or
distress, a safety/security threat, a crisis, or a genuine "call me now / emergency" with real stakes.
Casual impatience ("reply fast", "where are you??") is NOT urgent. When urgent: "reason" is 2–5 words, and "reply"
is a calm, short holding message saying you've alerted $owner and they'll respond as soon as possible.

Otherwise ${if (rule.mode == ReplyMode.Watch) "set reply to \"\" (watch only, never reply)." else
            "write the reply following the owner's instruction: \"${goal ?: rule.text ?: "Reply naturally and briefly on their behalf."}\""}
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
        // effort=low: a one-line reply needs no deep thinking, and some models (GLM) otherwise think for 10 s+.
        val t0 = android.os.SystemClock.uptimeMillis()
        val done = withTimeoutOrNull(45_000) {
            provider.stream(LlmRequest(system, listOf(Msg.User(user)), tools = emptyList(), maxTokens = 600, reasoningEffort = "low"))
                .filterIsInstance<LlmEvent.Completed>().first()
        } ?: return null
        trace("  ↳ reply drafted in ${android.os.SystemClock.uptimeMillis() - t0} ms")
        return VerdictParser.parse(done.text)
    }

    private fun send(threadKey: String, action: Notification.Action, text: String): Boolean =
        sendRaw(action, text).also { if (it) forYou.noteMorphSent(threadKey, text) }

    private fun sendRaw(action: Notification.Action, text: String): Boolean {
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

    /** If the newest message in a chat notification is the owner's, its thread key and text. */
    /** The phone app's call notifications while a call rings or is in progress — in either direction. */
    private fun isLiveCall(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        val callStyle = n.extras.getString(Notification.EXTRA_TEMPLATE) == "android.app.Notification\$CallStyle"
        return n.category == Notification.CATEGORY_CALL || callStyle || (sbn.isOngoing && n.category != Notification.CATEGORY_MESSAGE)
    }

    /** The conversation a notification belongs to, keyed like [IncomingMessage.threadKey]. */
    private fun threadKeyOf(sbn: StatusBarNotification): String? {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
        val conversation = style?.conversationTitle?.toString()
            ?: sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        return "${sbn.packageName}/$conversation"
    }

    /** Keep only "for you" items whose chat still has a notification (startup reconciliation). */
    fun reconcile(active: List<StatusBarNotification>) = forYou.keepOnly(active.mapNotNull(::threadKeyOf).toSet())

    /** A chat's notification went away: if it was you (opened, dismissed, read it in the app), For You lets go of it. */
    fun onRemoved(sbn: StatusBarNotification, reason: Int) {
        if (sbn.packageName == context.packageName) return
        threadKeyOf(sbn)?.let { forYou.notificationGone(it, reason) }
    }

    private fun ownerLatest(sbn: StatusBarNotification): Pair<String, String?>? {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification) ?: return null
        val last = style.messages.lastOrNull() ?: return null
        val me = style.user.name?.toString()
        if (last.person != null && last.person?.name?.toString() != me) return null
        val conversation = style.conversationTitle?.toString()
            ?: sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        return "${sbn.packageName}/$conversation" to last.text?.toString()
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
                time = last.timestamp,
            )
        }

        // Non-MessagingStyle apps (some SMS apps): treat title as sender.
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() || text.isEmpty() || Regex("^\\d+ new messages?$").matches(text)) return null
        return IncomingMessage(sbn.packageName, app, title, title, text, isGroup = false, time = sbn.postTime)
    }

    companion object {
        private const val TAG = "AutoReply"
        /** Calibrated on typesafe/jev-1.14: emergencies scored 0.75–0.98, everything else ≤ 0.08. */
        const val URGENT_THRESHOLD = 0.5
        const val NEEDS_REPLY_MIN = 0.35
        /** Calibrated: Maa "kuch urgent hai, jaldi call karo" 0.86, Papa "call me right now" 0.83, boss "call me asap, need the deck" 0.67. */
        const val URGENT_ASK_THRESHOLD = 0.75
        const val BURST_MS = 4_000L
        /** Calibrated on Jev: interrupt-worthy explanations 0.83–0.98, can-wait ones ≤ 0.05; vague insistence 0.96. */
        const val INTERRUPT_THRESHOLD = 0.6
        const val VAGUE_THRESHOLD = 0.6
        /** A screening stays open this long waiting for the explanation. */
        const val SCREEN_MS = 20 * 60_000L
    }
}
