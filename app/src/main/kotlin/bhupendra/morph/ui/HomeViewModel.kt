package bhupendra.morph.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import bhupendra.morph.MorphApp
import bhupendra.morph.auto.AutoEvent
import bhupendra.morph.data.CanvasItem
import bhupendra.morph.data.CrashReporter
import bhupendra.morph.data.Onboarding
import bhupendra.morph.agent.Busy
import bhupendra.morph.core.agent.AgentEvent
import bhupendra.morph.core.surface.Action
import bhupendra.morph.core.surface.Block
import bhupendra.morph.core.surface.ButtonSpec
import bhupendra.morph.core.surface.ButtonStyle
import bhupendra.morph.core.surface.Item
import bhupendra.morph.core.surface.Option
import bhupendra.morph.core.surface.Surface
import bhupendra.morph.core.surface.Tone
import bhupendra.morph.device.AppEntry
import bhupendra.morph.device.DeviceTools
import bhupendra.morph.device.Network
import bhupendra.morph.device.NotificationFeed
import bhupendra.morph.device.Voice
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.LocalTime
import java.util.UUID

sealed interface AgentMood {
    data object Idle : AgentMood
    data object Listening : AgentMood
    data object Thinking : AgentMood
    data class Acting(val label: String) : AgentMood
    data object Speaking : AgentMood
}

sealed interface UiEvent {
    data class RequestPermission(val permission: String) : UiEvent
    data class StartActivity(val intent: Intent) : UiEvent
    data object OpenSettings : UiEvent
    data object OpenApps : UiEvent
    data object NeedMicPermission : UiEvent
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    val graph = (app as MorphApp).graph
    private val json = Json { ignoreUnknownKeys = true }

    /** Oldest first; the UI renders it bottom-anchored so the newest thing sits right above your thumb. */
    val canvas get() = graph.canvas.items
    var mood by mutableStateOf<AgentMood>(AgentMood.Idle)
        private set
    var input by mutableStateOf("")
    var voiceLevel by mutableFloatStateOf(0f)
        private set
    var glance by mutableStateOf<Surface?>(null)
        private set
    /** App whose long-press menu is open. */
    var appMenu by mutableStateOf<AppEntry?>(null)

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val voice = Voice(app)

    init {
        // Mirror the runtime's state into the orb, unless we're listening.
        viewModelScope.launch {
            graph.runtime.busy.collect { b ->
                if (mood != AgentMood.Listening) mood = when (b) {
                    Busy.Idle -> AgentMood.Idle
                    Busy.Thinking -> AgentMood.Thinking
                    is Busy.Acting -> AgentMood.Acting(b.label)
                    Busy.Speaking -> AgentMood.Speaking
                }
            }
        }
        // Run onboarding once settings have loaded from disk.
        viewModelScope.launch {
            graph.settings.state.collect { if (it.loaded) graph.onboarding.refresh() }
        }
        refreshGlance()
        viewModelScope.launch { NotificationFeed.items.collect { refreshGlance() } }
        viewModelScope.launch { graph.autoReply.events.collect { refreshGlance() } }
        CrashReporter.consumeUnseen(app)?.let { report ->
            val lines = report.lines()
            val cause = lines.firstOrNull { it.contains("Exception") || it.contains("Error") }?.trim() ?: "Unknown error"
            canvas += CanvasItem.Card(Surface(
                title = "I crashed last time", icon = "🩹", tone = Tone.Error,
                blocks = listOf(
                    Block.Text(cause.take(220)),
                    Block.Text("${lines.firstOrNull().orEmpty()}\nFull report saved in Android/data/bhupendra.morph/files/crashes."),
                ),
            ))
        }
    }

    // ---- Intent entry points -------------------------------------------------------------------------

    fun submit(raw: String = input) {
        val text = raw.trim()
        if (text.isEmpty()) return
        input = ""

        if (graph.onboarding.awaitingName) { graph.onboarding.acceptName(text); return }

        graph.local.match(text)?.let { m ->
            viewModelScope.launch {
                val out = m.tool.run(m.args)
                val surface = out.surface
                when {
                    surface != null -> canvas += CanvasItem.Card(surface)
                    // Launching an app speaks for itself; everything else gets a quiet confirmation.
                    m.tool.spec.name != "open_app" -> canvas += CanvasItem.Notice(out.result, error = false)
                }
            }
            return
        }

        val provider = graph.settings.state.value.provider()
        if (provider == null) {
            canvas += CanvasItem.UserEcho(text)
            canvas += CanvasItem.Notice(
                "I can open apps, the torch and timers on my own — for everything else, give me a brain.",
                error = false, action = "Connect AI" to Action.OpenSettings,
            )
            return
        }

        if (!Network.online(getApplication())) {
            canvas += CanvasItem.UserEcho(text)
            canvas += CanvasItem.Notice(
                "You're offline. I can still open apps, search, flip the torch and set timers — the rest needs a connection.",
                error = false,
            )
            return
        }

        // The turn lives in the app-scoped runtime: it keeps going if you leave home mid-answer.
        graph.runtime.ask(text, provider)
    }

    fun stop() {
        graph.runtime.cancel()
        voice.stop()
        mood = AgentMood.Idle
    }

    fun perform(action: Action, from: String? = null) {
        when (action) {
            is Action.Say -> submit(action.text)
            is Action.Launch -> {
                val e = graph.catalog.resolve(action.app)
                if (e == null || !graph.catalog.launch(e)) canvas += CanvasItem.Notice("Couldn't find ${action.app}", error = true)
            }
            is Action.OpenUrl -> send(UiEvent.StartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(action.url))))
            is Action.RunTool -> if (action.name == Onboarding.INTERNAL_TOOL) {
                val args = runCatching { json.parseToJsonElement(action.argsJson) as JsonObject }.getOrNull()
                (args?.get("skip") as? kotlinx.serialization.json.JsonPrimitive)?.content?.let(graph.onboarding::skip)
            } else viewModelScope.launch {
                val tool = graph.registry[action.name] ?: return@launch
                val args = runCatching { json.parseToJsonElement(action.argsJson) as JsonObject }.getOrDefault(JsonObject(emptyMap()))
                val out = tool.run(args)
                canvas += out.surface?.let { CanvasItem.Card(it) } ?: CanvasItem.Notice(out.result, error = false)
            }
            is Action.RequestPermission -> send(UiEvent.RequestPermission(action.permission))
            Action.OpenSettings -> send(UiEvent.OpenSettings)
            Action.Dismiss -> from?.let(::dismiss)
        }
    }

    fun dismiss(key: String) { canvas.removeAll { it.key == key } }

    fun clearCanvas() {
        stop()
        canvas.clear()
        viewModelScope.launch { graph.runtime.reset() }
    }

    fun onHome() {
        input = ""
        if (mood is AgentMood.Listening) stopListening()
    }

    // ---- Voice ---------------------------------------------------------------------------------------

    fun startListening(hasMicPermission: Boolean) {
        if (!hasMicPermission) { send(UiEvent.NeedMicPermission); return }
        if (!voice.available) { canvas += CanvasItem.Notice("No speech recognizer on this phone.", error = true); return }
        graph.runtime.cancel()
        mood = AgentMood.Listening
        input = ""
        voice.start(object : Voice.Listener {
            override fun onPartial(text: String) { input = text }
            override fun onLevel(level: Float) { voiceLevel = level }
            override fun onFinal(text: String) {
                voiceLevel = 0f; mood = AgentMood.Idle
                if (text.isNotBlank()) submit(text)
            }
            override fun onError(message: String) {
                voiceLevel = 0f; mood = AgentMood.Idle
                if (input.isNotBlank()) submit(input) else canvas += CanvasItem.Notice(message, error = false)
            }
        })
    }

    fun stopListening() {
        voice.finish()
    }

    // ---- Proactive glance ----------------------------------------------------------------------------

    /** Built locally (no model call): what a good assistant would mention the moment you look at your phone. */
    fun refreshGlance() {
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

        val hasAccess = NotificationFeed.hasAccess(getApplication())
        val notifs = NotificationFeed.items.value
        if (hasAccess) {
            blocks += Block.Metric("${notifs.size}", if (notifs.size == 1) "Notification" else "Notifications", "🔔")
            if (notifs.isNotEmpty()) blocks += Block.ListBlock(notifs.take(3).map {
                Item(it.title.ifBlank { it.app }, it.text.lineSequence().firstOrNull()?.take(80), icon = "•", trailing = it.app)
            })
        }

        // While you were away: what Morph did on your behalf.
        val recent = graph.autoReply.log.value.filter { System.currentTimeMillis() - it.at < 6 * 3_600_000 }
        if (recent.isNotEmpty()) {
            blocks += Block.Heading("${recent.size} handled for you", eyebrow = "While you were away")
            blocks += Block.ListBlock(recent.take(3).map { e ->
                when (e) {
                    is AutoEvent.Replied -> Item("Replied to ${e.to}", e.reply, icon = "💬", trailing = e.app)
                    is AutoEvent.Urgent -> Item("${e.emergency.who}: ${e.emergency.reason}", e.emergency.message, icon = "🚨", trailing = e.emergency.app)
                    is AutoEvent.Failed -> Item("Couldn't reply to ${e.to}", e.why, icon = "⚠️", trailing = e.app)
                }
            })
        }
        val activeRules = graph.rules.rules.value.count { it.enabled }

        val chips = buildList {
            if (activeRules > 0) add(Option("Auto-replies ($activeRules)", Action.RunTool("list_auto_replies", "{}")))
            else add(Option("Auto-reply while busy", Action.Say("While I'm busy, auto-reply to my messages and alert me if anything is urgent")))
            if (hasAccess && notifs.isNotEmpty()) add(Option("What did I miss?", Action.Say("Brief me on my notifications — what matters?")))
            when (hour) {
                in 5..11 -> add(Option("Plan my day", Action.Say("Help me plan my day")))
                in 12..17 -> add(Option("Focus 25 min", Action.Say("timer 25 min")))
                in 18..23 -> add(Option("Alarm for 7", Action.Say("Set an alarm for 7:00 tomorrow morning")))
                else -> add(Option("Alarm for 8", Action.Say("Set an alarm for 8:00")))
            }
            add(Option("Torch", Action.Say("torch")))
            add(Option("Surprise me", Action.Say("Show me something delightful I can do with my phone right now")))
        }
        blocks += Block.Chips(chips)
        if (!hasAccess) blocks += Block.Buttons(listOf(
            ButtonSpec("Let me see notifications", Action.RequestPermission(DeviceTools.PERMISSION_NOTIFICATIONS), ButtonStyle.Tonal)
        ))
        glance = Surface(id = "glance", tone = Tone.Neutral, blocks = blocks)
    }

    // ---- Helpers -------------------------------------------------------------------------------------

    private inline fun replace(key: String, f: (CanvasItem) -> CanvasItem) {
        val i = canvas.indexOfFirst { it.key == key }
        if (i >= 0) canvas[i] = f(canvas[i])
    }

    private fun send(e: UiEvent) { viewModelScope.launch { _events.send(e) } }

    override fun onCleared() { voice.stop() }
}
