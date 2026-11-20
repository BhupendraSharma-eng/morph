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
        )
    }

    private fun block(o: JsonObject): Block? = when (o.str("type")?.lowercase()) {
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
        "progress" -> {
            val v = (o["value"] as? JsonPrimitive)?.floatOrNull ?: 0f
            Block.Progress(if (v > 1f) v / 100f else v, o.str("label"))
        }
        else -> null
    }

    private fun item(e: JsonElement): Item? = when (e) {
        is JsonPrimitive -> e.contentOrNull?.let { Item(it) }
        is JsonObject -> e.str("title", "text", "label")?.let {
            Item(it, e.str("subtitle", "description"), e.str("icon"), e.str("trailing", "meta"), action(e["action"]))
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
                "settings" -> Action.OpenSettings
                "dismiss", "close" -> Action.Dismiss
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
