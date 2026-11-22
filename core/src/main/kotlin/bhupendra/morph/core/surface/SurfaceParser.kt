package bhupendra.morph.core.surface

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * Lenient parser for model-produced surfaces. Models vary in key names and sometimes
 * invent block types, so unknown blocks are dropped instead of failing the whole surface.
 */
object SurfaceParser {

    fun parse(obj: JsonObject): Surface? {
        val blocks = (obj["blocks"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::block) }
        if (blocks.isEmpty() && obj.str("title") == null) return null
        return Surface(
            id = obj.str("id") ?: java.util.UUID.randomUUID().toString(),
            title = obj.str("title"),
            icon = obj.str("icon"),
            tone = Tone.parse(obj.str("tone")),
            blocks = blocks,
            form = (obj["form"] as? JsonObject)?.let { f ->
                FormSpec(f.str("submit_label", "submit") ?: "Done", if (f.containsKey("cancel_label")) f.str("cancel_label") else "Skip")
            } ?: obj.str("submit_label")?.let { FormSpec(it) },
        )
    }

    /** Model output varies a lot; inputs are recognised by an explicit `kind` or an input-ish `type`. */
    private val INPUT_TYPES = setOf(
        "choice", "radio", "select", "single_choice", "segmented", "multi_choice", "multichoice", "checkboxes", "multi_select",
        "slider", "range", "toggle", "switch", "checkbox", "date", "time", "stepper", "rating", "stars",
        "text_field", "textfield", "textarea", "long_text", "multiline",
    )

    private fun block(o: JsonObject): Block? {
        if (InputKind.parse(o.str("kind", "input_type", "control")) != null) return input(o)   // e.g. type:"text", kind:"choice"
        val t = o.str("type")?.lowercase()
        if (t != null && t in INPUT_TYPES) return input(o, t)
        return blockByType(o)
    }

    private fun blockByType(o: JsonObject): Block? = when (o.str("type")?.lowercase()) {
        "heading", "header", "title" -> o.str("text", "title")?.let { Block.Heading(it, o.str("eyebrow", "subtitle")) }
        "text", "paragraph", "markdown" -> o.str("text", "content")?.let { Block.Text(it) }
        "metric", "stat", "number" -> o.str("value")?.let { Block.Metric(it, o.str("label") ?: "", o.str("icon")) }
        "list" -> o.arr("items").mapNotNull(::item).takeIf { it.isNotEmpty() }?.let { Block.ListBlock(it) }
        "chips", "suggestions", "choices" -> o.arr("options", "items", "chips").mapNotNull(::option)
            .takeIf { it.isNotEmpty() }?.let { Block.Chips(it) }
        "buttons", "actions" -> o.arr("buttons", "items", "actions").mapNotNull(::button)
            .takeIf { it.isNotEmpty() }?.let { Block.Buttons(it) }
        "apps", "app_row" -> o.arr("apps", "items", "packages").mapNotNull { e ->
            when (e) {
                is JsonPrimitive -> e.contentOrNull
                is JsonObject -> e.str("package", "name", "app", "title")
                else -> null
            }
        }.takeIf { it.isNotEmpty() }?.let { Block.Apps(it) }
        "input", "field" -> input(o)
        "progress" -> {
            val v = (o["value"] as? JsonPrimitive)?.floatOrNull ?: 0f
            Block.Progress(if (v > 1f) v / 100f else v, o.str("label"))
        }
        else -> null
    }

    private fun input(o: JsonObject, typeAsKind: String? = null): Block? {
        val kind = InputKind.parse(o.str("kind", "input_type", "control") ?: typeAsKind) ?: return null
        // A "text field" with no id, no label and no placeholder but with content is really just text the model wanted
        // to show (e.g. {"type":"paragraph","kind":"text","value":"…"}) — not a field to fill in.
        val shownValue = (o["value"] ?: o["default"] ?: o["content"])?.let { (it as? JsonPrimitive)?.contentOrNull }
        if ((kind == InputKind.Text || kind == InputKind.LongText) && o.str("id", "name", "key", "label", "title", "placeholder", "hint") == null &&
            !shownValue.isNullOrBlank()) return Block.Text(shownValue)
        // Models often omit ids: derive one from the label so the answer still has a stable key.
        val id = o.str("id", "name", "key")
            ?: o.str("label", "text", "title")?.lowercase()?.replace(Regex("[^a-z0-9]+"), "_")?.trim('_')?.take(32)?.ifBlank { null }
            ?: "answer"
        fun num(k: String) = (o[k] as? JsonPrimitive)?.floatOrNull
        val options = o.arr("options", "choices").mapNotNull { e ->
            when (e) { is JsonPrimitive -> e.contentOrNull; is JsonObject -> e.str("label", "value", "text"); else -> null }
        }
        val initial = when (val d = o["default"] ?: o["value"] ?: o["initial"]) {
            is JsonPrimitive -> d.contentOrNull
            is JsonArray -> d.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString("|")
            else -> null
        }
        return Block.Input(
            // Never show the internal fallback id ("answer") as a visible label.
            id = id, kind = kind, label = o.str("label", "text", "title") ?: if (id == "answer") "" else id,
            placeholder = o.str("placeholder", "hint"),
            min = num("min"), max = num("max"), step = num("step"), unit = o.str("unit"), options = options,
            initial = initial, required = (o["required"] as? JsonPrimitive)?.contentOrNull == "true",
        )
    }

    private fun item(e: JsonElement): Item? = when (e) {
        is JsonPrimitive -> e.contentOrNull?.let { Item(it) }
        is JsonObject -> e.str("title", "text", "label")?.let {
            Item(it, e.str("subtitle", "description"), e.str("icon"), e.str("trailing", "meta"), action(e["action"]),
                dismiss = action(e["dismiss"]), compact = (e["compact"] as? JsonPrimitive)?.contentOrNull == "true")
        }
        else -> null
    }

    private fun option(e: JsonElement): Option? = when (e) {
        is JsonPrimitive -> e.contentOrNull?.let { Option(it, Action.Say(it)) }
        is JsonObject -> e.str("label", "text", "title")?.let { Option(it, action(e["action"]) ?: Action.Say(it)) }
        else -> null
    }

    private fun button(e: JsonElement): ButtonSpec? = when (e) {
        is JsonPrimitive -> e.contentOrNull?.let { ButtonSpec(it, Action.Say(it)) }
        is JsonObject -> e.str("label", "text", "title")?.let {
            ButtonSpec(it, action(e["action"]) ?: Action.Say(it), ButtonStyle.parse(e.str("style")))
        }
        else -> null
    }

    fun action(e: JsonElement?): Action? {
        return when (e) {
            null -> null
            is JsonPrimitive -> e.contentOrNull?.let { Action.Say(it) }
            is JsonObject -> when (e.str("type", "kind")?.lowercase()) {
                "say", "ask", "message", "prompt" -> e.str("text", "value")?.let { Action.Say(it) }
                "launch", "open_app", "app" -> e.str("app", "package", "value", "name")?.let { Action.Launch(it) }
                "url", "open_url", "link" -> e.str("url", "value")?.let { Action.OpenUrl(it) }
                "tool" -> e.str("name", "tool")?.let { Action.RunTool(it, (e["args"] as? JsonObject)?.toString() ?: "{}") }
                "permission" -> e.str("permission")?.let { Action.RequestPermission(it) }
                "notification" -> e.str("key")?.let { Action.OpenNotification(it, e.str("app").orEmpty()) }
                "settings" -> Action.OpenSettings
                "page" -> e.str("page", "name")?.let { Action.OpenPage(it) }
                "dismiss", "close" -> Action.Dismiss
                "dismiss_notifications" -> (e["keys"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.let { Action.DismissNotifications(it) }
                else -> e.str("text")?.let { Action.Say(it) }
            }
            else -> null
        }
    }

    private fun JsonObject.str(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }

    private fun JsonObject.arr(vararg keys: String): List<JsonElement> =
        keys.firstNotNullOfOrNull { this[it] as? JsonArray }.orEmpty()
}
