package bhupendra.morphy.core.agent

import bhupendra.morphy.core.surface.SurfaceParser
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The generative-UI primitive: the model composes a surface from the block vocabulary
 * and the launcher renders it natively. Schema kept within the subset Gemini/OpenAI/Anthropic all accept.
 */
class RenderUiTool : Tool {

    override val spec = ToolSpec(
        name = "render_ui",
        label = "Composing",
        terminal = true,
        description = "Show the user a native UI card on their home screen. Prefer this over long text " +
            "whenever information is structured, comparable, actionable, or offers next steps. " +
            "Blocks: heading{text,eyebrow}, text{text}, metric{value,label,icon}, " +
            "list{items:[{title,subtitle,icon,trailing,action}]}, chips{options:[{label,action}]}, " +
            "buttons{buttons:[{label,style(filled|tonal|outlined),action}]}, apps{apps:[package or app name]}, " +
            "progress{value 0..1,label}. Icons are single emoji. " +
            "Actions: {type:'say',text} sends text back to you as the user; {type:'launch',app}; {type:'url',url}. " +
            "Every item that comes from a web source (news, article, search result, citation) MUST have " +
            "action {type:'url', url:<its exact source URL from the tool result>} so tapping it opens the page.",
        parameters = schema(required = listOf("blocks")) {
            string("title", "Short card title (optional)")
            string("icon", "One emoji for the card (optional)")
            string("tone", "Color emphasis", enum = listOf("neutral", "primary", "secondary", "tertiary", "error"))
            raw("blocks", buildJsonObject {
                put("type", "array")
                put("items", blockSchema())
            })
        },
    )

    override suspend fun run(args: JsonObject): ToolOutcome {
        val parsed = SurfaceParser.parse(args) ?: return ToolOutcome("Error: surface needs at least one valid block.")
        // A shown card isn't a form (that's ask_user): input fields here would just be read-only boxes with greyed
        // hint text. Show their content as plain text instead.
        val inputs = parsed.blocks.filterIsInstance<bhupendra.morphy.core.surface.Block.Input>()
        val shown = if (inputs.isEmpty() || parsed.form != null) parsed else parsed.copy(blocks = parsed.blocks.mapNotNull { b ->
            if (b !is bhupendra.morphy.core.surface.Block.Input) b
            else listOfNotNull(b.initial, b.placeholder, b.label).firstOrNull { it.isNotBlank() }?.let { bhupendra.morphy.core.surface.Block.Text(it) }
        })
        // Source rows the model left without a link: recover it from what the web tools returned this session.
        val surface = shown.copy(blocks = shown.blocks.map { b ->
            if (b !is bhupendra.morphy.core.surface.Block.ListBlock) b
            else b.copy(items = b.items.map { item ->
                if (item.action != null) item
                else bhupendra.morphy.core.web.SourceLinks.find(item.title + " " + item.subtitle.orEmpty())
                    ?.let { item.copy(action = bhupendra.morphy.core.surface.Action.OpenUrl(it)) } ?: item
            })
        })
        return ToolOutcome("Displayed to the user." + if (inputs.isNotEmpty() && parsed.form == null)
            " (Input fields were shown as text: render_ui is display-only — use ask_user when you need the user's input.)" else "", surface)
    }

    /** [inputs]: include the form-input block fields — only ask_user needs them (render_ui is display-only). */
    fun blockSchema(inputs: Boolean = false): JsonObject {
        val action = obj(
            "type" to enumStr("say", "launch", "url"),
            "text" to str(), "app" to str(), "url" to str(),
        )
        return buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(mapOf(
                "type" to if (inputs) enumStr("heading", "text", "metric", "list", "chips", "buttons", "apps", "progress", "input")
                    else enumStr("heading", "text", "metric", "list", "chips", "buttons", "apps", "progress"),
                "text" to str(), "eyebrow" to str(), "value" to str(), "label" to str(), "icon" to str(),
                "items" to arr(obj("title" to str(), "subtitle" to str(), "icon" to str(), "trailing" to str(), "action" to action)),
                "options" to arr(obj("label" to str(), "action" to action)),
                "buttons" to arr(obj("label" to str(), "style" to enumStr("filled", "tonal", "outlined"), "action" to action)),
                "apps" to arr(str()),
            ) + if (!inputs) emptyMap() else mapOf(
                "id" to str(), "kind" to enumStr("text", "long_text", "slider", "toggle", "choice", "multi_choice", "date", "time", "stepper", "rating"),
                "placeholder" to str(), "min" to num(), "max" to num(), "step" to num(), "unit" to str(), "default" to str(),
            )))
            putJsonArray("required") { add(JsonPrimitive("type")) }
        }
    }

    private fun str() = buildJsonObject { put("type", "string") }
    private fun num() = buildJsonObject { put("type", "number") }
    private fun enumStr(vararg v: String) = buildJsonObject {
        put("type", "string"); putJsonArray("enum") { v.forEach { add(JsonPrimitive(it)) } }
    }
    private fun arr(items: JsonObject) = buildJsonObject { put("type", "array"); put("items", items) }
    private fun obj(vararg p: Pair<String, JsonObject>) = buildJsonObject {
        put("type", "object"); put("properties", JsonObject(p.toMap()))
    }
}
