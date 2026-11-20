package bhupendra.morph.core.surface

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
        Action.OpenSettings -> obj("type" to "settings")
        Action.Dismiss -> obj("type" to "dismiss")
    }

    private fun obj(vararg p: Pair<String, String?>) = buildJsonObject {
        p.forEach { (k, v) -> if (v != null) put(k, v) }
    }
}
