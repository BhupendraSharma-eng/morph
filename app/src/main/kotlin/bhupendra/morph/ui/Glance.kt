package bhupendra.morph.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import bhupendra.morph.device.Notif
import bhupendra.morph.core.surface.Action
import bhupendra.morph.core.surface.Block
import bhupendra.morph.core.surface.ButtonSpec
import bhupendra.morph.core.surface.ButtonStyle
import bhupendra.morph.core.surface.Item
import bhupendra.morph.core.surface.Option
import bhupendra.morph.core.surface.Surface
import bhupendra.morph.core.surface.Tone
import bhupendra.morph.device.DeviceTools
import bhupendra.morph.device.NotificationFeed
import bhupendra.morph.device.NotificationRanker
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import java.time.LocalTime
import android.content.Context
import bhupendra.morph.AppGraph

/**
 * The home glance card, built locally from what's on the phone (no model call): greeting, battery, what needs you
 * from the shade (needs-you rows in full, the rest collapsed per app with ✕ to shelve), contextual suggestion chips
 * learned from use, and the apps you're likeliest to want now.
 */
class Glance(private val graph: AppGraph, private val context: Context) {
    class Built(val surface: Surface, val chipKeys: Map<Action, String>)

    fun build(): Built {
        val t = graph.tools
        val s = graph.settings.state.value
        val hour = LocalTime.now().hour
        val name = s.userName.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""
        val greeting = when (hour) {
            in 5..11 -> "Good morning$name"
            in 12..16 -> "Good afternoon$name"
            in 17..21 -> "Good evening$name"
            else -> "Hey night owl$name"
        }
        val battery = t.batteryPercent()
        val blocks = mutableListOf<Block>()
        blocks += Block.Heading(greeting, eyebrow = when {
            battery <= 20 && !t.isCharging() -> "Battery is getting low"
            hour in 5..11 -> "Here's your start"
            hour >= 22 || hour < 5 -> "Winding down"
            else -> "Here's what's up"
        })
        blocks += Block.Metric("$battery%", if (t.isCharging()) "Charging" else "Battery", if (t.isCharging()) "⚡" else "🔋")
        t.nextAlarm()?.let { blocks += Block.Metric(it, "Next alarm", "⏰") }

        val hasAccess = Demo.active || NotificationFeed.hasAccess(context)
        val notifs = if (Demo.active) Demo.notifications else NotificationFeed.items.value
        var quietKeys: List<String> = emptyList()
        if (hasAccess) {
            blocks += Block.Metric("${notifs.size}", if (notifs.size == 1) "Notification" else "Notifications", "🔔")
            // Ranked by the decision model when scores are in; newest-first until then.
            val scores = if (Demo.active) Demo.scores.mapKeys { (k, _) -> Demo.notifications.first { it.key == k }.let(graph.ranker::idOf) }
                else graph.ranker.scores.value
            val ranked = notifs.sortedByDescending { scores[graph.ranker.idOf(it)] ?: -1.0 }
            val needYou = ranked.count { (scores[graph.ranker.idOf(it)] ?: 0.0) >= NotificationRanker.NEEDS_YOU }
            if (needYou > 0) blocks += Block.Metric("$needYou", if (needYou == 1) "Needs you" else "Need you", "❗")
            // Media gets its own player card (live state + controls). The rest splits by what deserves room:
            // what needs you gets full rows; everything else collapses to one slim line per app (chats per person).
            val people = ranked.filterNot { it.isMedia }
            fun score(n: Notif) = scores[graph.ranker.idOf(n)]
            val important = people.filter { (score(it) ?: 0.0) >= NotificationRanker.NEEDS_YOU }.take(3)
            val quiet = people - important.toSet()
            val rows = mutableListOf<Item>()
            important.forEach {
                rows += Item(it.title.ifBlank { it.app }, chatSubtitle(it), icon = "❗", trailing = it.app,
                    action = Action.OpenNotification(it.key, it.pkg), dismiss = Action.DismissNotifications(listOf(it.key)))
            }
            val groups = quiet.groupBy { it.thread ?: it.pkg }.values   // ranked order is kept within and across groups
            groups.take(if (important.isEmpty()) 4 else 2).forEach { g ->
                val top = g.first()
                rows += if (g.size == 1 || top.thread != null) Item(
                    top.title.ifBlank { top.app }, chatSubtitle(top), trailing = top.app, compact = true,
                    action = Action.OpenNotification(top.key, top.pkg), dismiss = Action.ShelveNotifications(g.map { it.key }),
                ) else Item(
                    "${top.app} · ${g.size}", "${top.title}: ${top.text.lineSequence().firstOrNull().orEmpty()}", compact = true,
                    action = Action.OpenNotification(top.key, top.pkg), dismiss = Action.ShelveNotifications(g.map { it.key }),
                )
            }
            if (rows.isNotEmpty()) blocks += Block.ListBlock(rows)
            // "Clear what can wait": only notifications the ranker actually scored below the bar — never unjudged ones.
            quietKeys = quiet.filter { (score(it) ?: 1.0) < NotificationRanker.NEEDS_YOU }.map { it.key }
        }

        // What Morph handled is a quiet one-liner (details live in Activity); what needs you is the For You card.
        val handled = if (Demo.active) 7 else graph.autoReply.log.value.count { System.currentTimeMillis() - it.at < 12 * 3_600_000 }
        val activeRules = if (Demo.active) 2 else graph.rules.rules.value.count { it.enabled }

        val habits = graph.catalog.habits
        val usageAccess = habits.hasUsageAccess()
        val shelf = graph.later.items.value.size
        val chips = listOfNotNull(
            quietKeys.takeIf { it.size >= 2 }?.let { Option("🗂 Move ${it.size} to Later", Action.ShelveNotifications(it)) },
            shelf.takeIf { it > 0 && !Demo.active }?.let { Option("Later · $it", Action.OpenPage("later")) },
        ) +
            suggestions(hour, notifs, hasAccess, handled, activeRules) +
            listOfNotNull(Option("Learn the apps I use", Action.RequestPermission(DeviceTools.PERMISSION_USAGE)).takeIf { !usageAccess && !Demo.active })
        blocks += Block.Chips(chips)
        if (!hasAccess) blocks += Block.Buttons(listOf(
            ButtonSpec("Let me see notifications", Action.RequestPermission(DeviceTools.PERMISSION_NOTIFICATIONS), ButtonStyle.Tonal)
        ))
        // The apps you're likeliest to want now, last — the bottom of the card is nearest your thumb.
        val apps = if (Demo.active) emptyList() else graph.catalog.frequent(4)
        if (apps.isNotEmpty()) blocks += Block.Apps(apps.map { it.pkg })
        return Built(Surface(id = "glance", tone = Tone.Neutral, blocks = blocks), chipKeys)
    }

    /** Which chip key each suggestion's action belongs to (filled by [suggestions]). */
    private var chipKeys: Map<Action, String> = emptyMap()

    /**
     * Suggestions that fit *now*: built from context (time, alarms, battery, headphones, what's in the shade) and
     * from what you actually do (repeated asks, your usual alarm time, your music app), each scored and then
     * weighted by whether you use it — chips you keep ignoring sink and drop off; ones you tap rise. Only what can
     * run is offered: with AI off, just the on-device ones.
     */
    private fun suggestions(hour: Int, notifs: List<Notif>, hasAccess: Boolean, handled: Int, activeRules: Int): List<Option> {
        val habits = graph.catalog.habits
        val t = graph.tools
        val aiOn = graph.settings.state.value.aiOn || Demo.active
        data class C(val key: String, val option: Option, val score: Double, val learnable: Boolean = true)
        val c = mutableListOf<C>()
        val night = hour >= 20 || hour < 2
        val chats = notifs.count { it.thread != null }

        // Status first: things Morph did or is doing for you.
        if (handled > 0) c += C("handled", Option("✓ Handled $handled", Action.OpenPage("activity")), 3.0, learnable = false)
        if (activeRules > 0) c += C("rules", Option("Auto-replies ($activeRules)", Action.RunTool("list_auto_replies", "{}")), 2.8, learnable = false)

        // What you keep asking for, likeliest-at-this-hour first.
        if (!Demo.active) habits.topTasks(3).forEachIndexed { i, task ->
            val label = "↻ " + task.replaceFirstChar(Char::uppercase).let { if (it.length > 26) it.take(25).trimEnd() + "…" else it }
            c += C("task:" + task.lowercase(), Option(label, Action.Say(task)), 2.6 - i * 0.2)
        }

        // Context.
        if (t.batteryPercent() <= 20 && !t.isCharging())
            c += C("battery", Option("🔋 Battery saver", Action.RunTool("open_settings", "{\"panel\":\"battery\"}")), 2.7)
        val nextAlarm = t.nextAlarmAt()
        if (night && (nextAlarm == null || nextAlarm - System.currentTimeMillis() > 14 * 3_600_000L)) {
            val (h, m) = habits.usualAlarm() ?: (7 to 0)
            val label = java.time.LocalTime.of(h, m).format(java.time.format.DateTimeFormatter.ofPattern(if (m == 0) "h a" else "h:mm a"))
            c += C("alarm", Option("⏰ Alarm for $label", Action.Say("alarm %d:%02d am".format(if (h % 12 == 0) 12 else h % 12, m).let {
                if (h >= 12) it.replace(" am", " pm") else it })), 2.4)
        }
        if (headphonesOn() && graph.media.players.value.none { it.playing }) graph.catalog.favouriteAudioApp()?.let { app ->
            c += C("music", Option("🎧 ${app.label}", Action.Launch(app.pkg)), 2.5)
        }
        if (hasAccess && (chats >= 3 || notifs.size >= 6)) c += C("miss", Option("What did I miss?", Action.Say("Brief me on my notifications — what matters?")), 2.0 + minOf(chats, 10) * 0.05)
        if (activeRules == 0 && chats >= 2 && hour in 9..20)
            c += C("busy", Option("Auto-reply while busy", Action.Say("While I'm busy, auto-reply to my messages and alert me if anything is urgent")), 1.4)
        if (hour in 6..10) c += C("plan", Option("Plan my day", Action.Say("Help me plan my day")), 1.3)
        if (hour >= 19 || hour < 6) c += C("torch", Option("Torch", Action.Say("torch")), 0.9)

        fun works(o: Option) = aiOn || (o.action as? Action.Say)?.let { graph.local.match(it.text) != null } ?: true
        val picked = c.filter { works(it.option) }
            .map { it to if (it.learnable) it.score * habits.chipWeight(it.key) else it.score }
            .sortedByDescending { it.second }
            .distinctBy { (it.first.option.action as? Action.Say)?.text?.lowercase() ?: it.first.key }
            .take(5).map { it.first }
            .ifEmpty { if (aiOn) listOf(C("surprise", Option("Surprise me", Action.Say("Show me something delightful I can do with my phone right now")), 0.1)) else emptyList() }
        if (!Demo.active) picked.filter { it.learnable }.forEach { habits.chipShown(it.key) }
        chipKeys = picked.associate { it.option.action to it.key }
        return picked.map { it.option }
    }

    /**
     * What a notification row says. Chats show the whole unread burst, not just the newest line: For You's one-line
     * summary when it wrote one, otherwise every message joined (the row expands to show it all).
     */
    private fun chatSubtitle(n: Notif): String? {
        if (n.thread == null || n.messages.size <= 1) return n.text.lineSequence().firstOrNull()?.take(120)
        graph.forYou.items.value.firstOrNull { it.threadKey == n.thread }?.let { return "${it.summary} · ${n.messages.size} messages" }
        return "${n.messages.size} messages · " + n.messages.joinToString(" · ")
    }

    private fun headphonesOn(): Boolean = runCatching {
        val am = context.getSystemService(android.media.AudioManager::class.java)
        am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type in setOf(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
                android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }.getOrDefault(false)
}
