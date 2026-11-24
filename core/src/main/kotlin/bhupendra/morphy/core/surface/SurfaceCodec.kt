package bhupendra.morphy.core.surface

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Encodes surfaces into the same shape the model produces, so [SurfaceParser] is the single decoder.
 * decode(encode(s)) == s for every surface.
 */
object SurfaceCodec {

    fun encode(s: Surface): JsonObject = buildJsonObject {
        put("id", s.id)
        s.title?.let { put("title", it) }
        s.icon?.let { put("icon", it) }
        put("tone", s.tone.name.lowercase())
        put("blocks", JsonArray(s.blocks.map(::block)))
        s.form?.let { f -> put("form", buildJsonObject { put("submit_label", f.submitLabel); put("cancel_label", f.cancelLabel) }) }
    }

    fun decode(o: JsonObject): Surface? = SurfaceParser.parse(o)

    private fun block(b: Block): JsonObject = when (b) {
        is Block.Heading -> obj("type" to "heading", "text" to b.text, "eyebrow" to b.eyebrow)
        is Block.Text -> obj("type" to "text", "text" to b.text)
        is Block.Metric -> obj("type" to "metric", "value" to b.value, "label" to b.label, "icon" to b.icon)
        is Block.ListBlock -> buildJsonObject {
            put("type", "list")
            put("items", JsonArray(b.items.map { i ->
                buildJsonObject {
                    put("title", i.title); i.subtitle?.let { put("subtitle", it) }; i.icon?.let { put("icon", it) }
                    i.trailing?.let { put("trailing", it) }; i.action?.let { put("action", action(it)) }
                    i.dismiss?.let { put("dismiss", action(it)) }; if (i.compact) put("compact", true)
                }
            }))
        }
        is Block.Chips -> buildJsonObject {
            put("type", "chips")
            put("options", JsonArray(b.options.map { buildJsonObject { put("label", it.label); put("action", action(it.action)) } }))
        }
        is Block.Buttons -> buildJsonObject {
            put("type", "buttons")
            put("buttons", JsonArray(b.buttons.map {
                buildJsonObject { put("label", it.label); put("style", it.style.name.lowercase()); put("action", action(it.action)) }
            }))
        }
        is Block.Apps -> buildJsonObject { put("type", "apps"); put("apps", JsonArray(b.apps.map(::JsonPrimitive))) }
        is Block.Progress -> buildJsonObject { put("type", "progress"); put("value", b.value); b.label?.let { put("label", it) } }
        is Block.Input -> buildJsonObject {
            put("type", "input"); put("id", b.id); put("kind", b.kind.name.lowercase().let { if (it == "longtext") "long_text" else if (it == "multichoice") "multi_choice" else it })
            put("label", b.label); b.placeholder?.let { put("placeholder", it) }
            b.min?.let { put("min", it) }; b.max?.let { put("max", it) }; b.step?.let { put("step", it) }; b.unit?.let { put("unit", it) }
            if (b.options.isNotEmpty()) put("options", JsonArray(b.options.map(::JsonPrimitive)))
            b.initial?.let { put("default", it) }; if (b.required) put("required", "true")
        }
    }

    fun action(a: Action): JsonObject = when (a) {
        is Action.Say -> obj("type" to "say", "text" to a.text)
        is Action.Launch -> obj("type" to "launch", "app" to a.app)
        is Action.OpenUrl -> obj("type" to "url", "url" to a.url)
        is Action.RunTool -> buildJsonObject {
            put("type", "tool"); put("name", a.name)
            put("args", runCatching { Json.parseToJsonElement(a.argsJson) as JsonObject }.getOrDefault(JsonObject(emptyMap())))
        }
        is Action.RequestPermission -> obj("type" to "permission", "permission" to a.permission)
        is Action.OpenNotification -> obj("type" to "notification", "key" to a.key, "app" to a.app)
        Action.OpenSettings -> obj("type" to "settings")
        is Action.OpenPage -> obj("type" to "page", "page" to a.page)
        Action.Dismiss -> obj("type" to "dismiss")
        is Action.ShelveNotifications -> buildJsonObject {
            put("type", "shelve_notifications")
            put("keys", kotlinx.serialization.json.JsonArray(a.keys.map { JsonPrimitive(it) }))
        }
        is Action.DismissNotifications -> buildJsonObject {
            put("type", "dismiss_notifications")
            put("keys", kotlinx.serialization.json.JsonArray(a.keys.map { JsonPrimitive(it) }))
        }
    }

    private fun obj(vararg p: Pair<String, String?>) = buildJsonObject {
        p.forEach { (k, v) -> if (v != null) put(k, v) }
    }
}
