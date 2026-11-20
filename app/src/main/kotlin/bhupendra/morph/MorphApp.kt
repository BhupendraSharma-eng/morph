package bhupendra.morph

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import bhupendra.morph.auto.AutoReplyEngine
import bhupendra.morph.auto.AutoReplyTools
import bhupendra.morph.auto.RuleStore
import bhupendra.morph.auto.autoCard
import bhupendra.morph.core.agent.Agent
import bhupendra.morph.core.agent.RenderUiTool
import bhupendra.morph.core.agent.ToolRegistry
import bhupendra.morph.core.llm.MsgCodec
import bhupendra.morph.data.Brain
import bhupendra.morph.data.CanvasRepo
import bhupendra.morph.data.CrashReporter
import bhupendra.morph.data.SettingsStore
import bhupendra.morph.device.AppCatalog
import bhupendra.morph.device.DeviceTools
import bhupendra.morph.device.LocalIntents
import bhupendra.morph.device.NotificationFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MorphApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        graph = AppGraph(this)
    }
}

/** Manual DI: one place that wires the agent runtime to Android capabilities. */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(app, scope)
    val catalog = AppCatalog(app, scope)
    val tools = DeviceTools(app, catalog, settings)
    val local = LocalIntents(catalog, tools)
    val canvas = CanvasRepo(app, scope)
    val rules = RuleStore(app)
    val autoReply = AutoReplyEngine(app, rules, settings, scope)
    private val autoTools = AutoReplyTools(app, rules, catalog)
    val registry = ToolRegistry(tools.all + autoTools.all + RenderUiTool())

    private val historyFile = File(app.filesDir, "conversation.json")
    val agent = Agent(registry, systemPrompt = ::systemPrompt, onHistory = { msgs ->
        val text = MsgCodec.encode(msgs)
        scope.launch(Dispatchers.IO) { runCatching { historyFile.writeText(text) } }
    })

    init {
        scope.launch(Dispatchers.IO) {
            if (historyFile.exists()) agent.restore(MsgCodec.decode(historyFile.readText()))
        }
        // Background automation posts straight to the persisted canvas, UI or not.
        scope.launch {
            autoReply.events.collect { e -> withContext(Dispatchers.Main) { canvas.add(autoCard(e)) } }
        }
    }

    private fun systemPrompt(): String {
        val s = settings.state.value
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm"))
        val notifs = if (NotificationFeed.hasAccess(app)) "${NotificationFeed.items.value.size} in the shade" else "no access yet"
        val frequent = catalog.frequent(10).joinToString { it.label }.ifEmpty { "unknown yet" }
        return """
You are Morph — the user's home screen. There are no app grids here: you ARE the interface.
The user tells you what they want (typed or spoken) and you make it happen, then get out of the way.

# How you respond
- Act first. If a request maps to a tool, call it immediately; don't ask permission for harmless things.
- Keep spoken text to one or two short, warm sentences. Light, playful, never robotic. No markdown headings.
- Whenever the answer has structure — options, steps, a plan, a summary, status, comparisons, choices —
  call render_ui to compose a native card instead of writing it out. Don't repeat the card's content in text.
- Offer 2–3 smart next steps as chips when it helps the user keep going without typing.
- Messages and calls: if given a name, find_contact first, then compose_message / call. The user confirms in that app.
- For "what did I miss" / briefings: get_notifications, then render_ui a grouped summary with what matters first.
- Use remember for durable preferences the user reveals (names, routines, likes). Never store secrets.
- You can answer chats for the user while they're busy: add_auto_reply ("tell anyone who texts I'm driving",
  "if Mom messages tonight say I'll call after 9"). AI rules also detect emergencies and ring an alarm.
  Use mode 'watch' when they only want emergency detection. Restate the rule in one short line after creating it.
- Never claim you did something unless a tool confirmed it. If something isn't possible yet, say so briefly.

# Context
Time: $now
Battery: ${tools.batteryPercent()}%${if (tools.isCharging()) " charging" else ""}
Next alarm: ${tools.nextAlarm() ?: "none"}
Notifications: $notifs
User's name: ${s.userName.ifBlank { "unknown" }}
Active auto-replies: ${rules.rules.value.filter { it.enabled }.joinToString("; ") { it.describe() }.ifEmpty { "none" }}
Frequently opened apps: $frequent
What you remember about the user:
${s.memories.joinToString("\n") { "- $it" }.ifEmpty { "- nothing yet" }}
""".trim()
    }
}

/**
 * Configure Morph from a computer (DUMP permission => adb shell only):
 *   adb shell am broadcast -n bhupendra.morph/.ConfigReceiver --es provider gemini --es api_key KEY [--es model M] [--es base_url U] [--es name N]
 */
class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as MorphApp).graph
        val pending = goAsync()
        graph.scope.launch {
            try {
                val current = graph.settings.state.value
                val brain = Brain.parse(intent.getStringExtra("provider")) ?: current.brain
                val switched = brain != current.brain
                graph.settings.saveBrain(
                    brain,
                    intent.getStringExtra("base_url") ?: if (switched) brain.baseUrl else current.baseUrl,
                    intent.getStringExtra("model") ?: if (switched) brain.model else current.model,
                    intent.getStringExtra("api_key"),
                )
                intent.getStringExtra("name")?.let { graph.settings.setName(it) }
                // After goAsync() the result must go through the PendingResult, not the receiver.
                pending.resultData = "ok brain=${brain.name}"
            } catch (e: Exception) {
                pending.resultData = "error: ${e.message}"
            } finally {
                pending.finish()
            }
        }
    }
}
