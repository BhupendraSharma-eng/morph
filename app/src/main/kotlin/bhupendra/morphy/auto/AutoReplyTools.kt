package bhupendra.morphy.auto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import bhupendra.morphy.core.agent.Risk
import bhupendra.morphy.core.agent.Tool
import bhupendra.morphy.core.agent.ToolOutcome
import bhupendra.morphy.core.agent.ToolSpec
import bhupendra.morphy.core.agent.schema
import bhupendra.morphy.core.automation.ReplyMode
import bhupendra.morphy.core.automation.ReplyRule
import bhupendra.morphy.core.surface.Action
import bhupendra.morphy.core.surface.Block
import bhupendra.morphy.core.surface.ButtonSpec
import bhupendra.morphy.core.surface.ButtonStyle
import bhupendra.morphy.core.surface.Item
import bhupendra.morphy.core.surface.Surface
import bhupendra.morphy.core.surface.Tone
import bhupendra.morphy.device.AppCatalog
import bhupendra.morphy.device.DeviceTools
import bhupendra.morphy.device.NotificationFeed
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

class AutoReplyTools(
    private val context: Context,
    private val store: RuleStore,
    private val catalog: AppCatalog,
) {
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull

    private fun tool(spec: ToolSpec, body: suspend (JsonObject) -> ToolOutcome) = object : Tool {
        override val spec = spec
        override suspend fun run(args: JsonObject) = body(args)
    }

    private fun turnOff(id: String) = Action.RunTool("remove_auto_reply", """{"id":"$id"}""")

    val add = tool(ToolSpec(
        name = "add_auto_reply",
        label = "Setting up auto-reply",
        risk = Risk.Act,
        description = "Create a rule that answers incoming chat messages for the user (WhatsApp, Telegram, SMS… anything " +
            "with an inline reply), or watches them for emergencies. Every AI-mode rule also detects emergencies: " +
            "it sends a calm holding reply and rings a loud alarm so the user attends. " +
            "mode: 'ai' (you write replies following `text` as the instruction), 'fixed' (send `text` verbatim), " +
            "'watch' (never reply; only alarm on emergencies).",
        parameters = schema(required = listOf("mode")) {
            string("mode", "ai | fixed | watch", listOf("ai", "fixed", "watch"))
            string("text", "AI instruction (e.g. 'say I'm driving and will call back') or the fixed reply")
            string("sender", "Contact or chat name to match (omit for everyone)")
            string("app", "App name or package (omit for all messaging apps)")
            string("keyword", "Only when the message contains this")
            string("from", "Start time HH:mm (optional)")
            string("until", "End time HH:mm (optional, may be past midnight)")
            boolean("include_groups", "Also reply in group chats (default false)")
        },
    )) { a ->
        val mode = ReplyMode.parse(a.s("mode"))
        if (mode == ReplyMode.Fixed && a.s("text") == null) return@tool ToolOutcome("Error: fixed mode needs `text`.")
        val app = a.s("app")?.let { catalog.resolve(it) }
        val window = if (a.s("from") != null && a.s("until") != null) "${a.s("from")}-${a.s("until")}" else null
        val rule = ReplyRule(
            appPackage = app?.pkg, appLabel = app?.label ?: a.s("app"),
            sender = a.s("sender"), keyword = a.s("keyword"), mode = mode, text = a.s("text"),
            window = window, includeGroups = a.b("include_groups") ?: false,
        )
        store.add(rule)

        val blocks = mutableListOf<Block>(Block.Text(rule.describe()))
        val missing = mutableListOf<ButtonSpec>()
        if (!NotificationFeed.hasAccess(context)) missing += ButtonSpec("Allow notification access",
            Action.RequestPermission(DeviceTools.PERMISSION_NOTIFICATIONS), ButtonStyle.Filled)
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing += ButtonSpec("Allow alerts", Action.RequestPermission(Manifest.permission.POST_NOTIFICATIONS), ButtonStyle.Tonal)
        if (missing.isNotEmpty()) {
            blocks += Block.Text("I need a couple of permissions before this can work:")
            blocks += Block.Buttons(missing)
        }
        blocks += Block.Buttons(listOf(ButtonSpec("Turn off", turnOff(rule.id), ButtonStyle.Outlined)))
        ToolOutcome(
            "Rule ${rule.id} active: ${rule.describe()}." + if (missing.isNotEmpty()) " Permissions still needed; buttons shown." else "",
            Surface(title = if (mode == ReplyMode.Watch) "Watching for emergencies" else "Auto-reply on",
                icon = if (mode == ReplyMode.Watch) "🛡️" else "💬", tone = Tone.Secondary, blocks = blocks),
        )
    }

    val list = tool(ToolSpec(name = "list_auto_replies", label = "Your auto-replies",
        description = "Show the user's auto-reply and watch rules.", parameters = schema { })) {
        val rules = store.rules.value
        if (rules.isEmpty()) return@tool ToolOutcome("No auto-reply rules.")
        ToolOutcome(
            rules.joinToString("\n") { "${it.id}: ${it.describe()}${if (!it.enabled) " (off)" else ""}" },
            Surface(title = "Auto-replies", icon = "💬", blocks = listOf(
                Block.ListBlock(rules.map {
                    Item(it.describe(), if (it.enabled) "Tap to turn off" else "Off", icon = if (it.mode == ReplyMode.Watch) "🛡️" else "💬",
                        action = turnOff(it.id))
                }),
            )),
        )
    }

    val remove = tool(ToolSpec(name = "remove_auto_reply", label = "Turning off auto-reply",
        description = "Delete an auto-reply rule by id, or 'all'.", risk = Risk.Act,
        parameters = schema(listOf("id")) { string("id", "Rule id from list_auto_replies, or 'all'") })) { a ->
        val id = a.s("id") ?: return@tool ToolOutcome("Error: id required")
        store.remove(id)
        ToolOutcome(if (id == "all") "All auto-replies are off." else "Auto-reply $id is off.")
    }

    val stopAlarm = tool(ToolSpec(name = "stop_alarm", label = "Silencing", description = "Silence the urgent-message alarm.",
        parameters = schema { }, risk = Risk.Act)) {
        UrgentAlert.acknowledge(context)
        ToolOutcome("Alarm silenced.")
    }

    val all: List<Tool> = listOf(add, list, remove, stopAlarm)
}
