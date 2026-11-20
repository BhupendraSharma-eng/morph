package bhupendra.morph.core.agent

import bhupendra.morph.core.surface.Block
import bhupendra.morph.core.surface.FormSpec
import bhupendra.morph.core.surface.SurfaceParser
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The agent asks with UI instead of text: it composes input controls, the turn pauses until the user
 * submits, and the structured answer comes back as the tool result.
 */
class AskUserTool(private val prompter: UserPrompter) : Tool {

    override val spec = ToolSpec(
        name = "ask_user",
        label = "Asking you",
        description = "Ask the user for input with real controls instead of a text question, and WAIT for the answer. " +
            "Use when you need choices, numbers, dates or details to continue (plans, bookings, preferences, " +
            "filling forms, picking between options). Compose blocks like render_ui, plus input blocks: " +
            "{type:'input', id, kind, label, ...} with kind = text | long_text (placeholder) | slider (min,max,step,unit) | " +
            "toggle | choice / multi_choice (options:[...]) | date | time | stepper (min,max,step) | rating. " +
            "Put a sensible `default` on each. Returns {id: value, ...} or 'dismissed'. Keep it to ≤5 inputs.",
        parameters = schema(required = listOf("blocks")) {
            string("title", "Short question title")
            string("icon", "One emoji")
            string("submit_label", "Button text, e.g. 'Plan it' (default 'Done')")
            raw("blocks", buildJsonObject {
                put("type", "array")
                put("items", RenderUiTool().blockSchema())
            })
        },
    )

    override suspend fun run(args: JsonObject): ToolOutcome {
        val parsed = SurfaceParser.parse(args) ?: return ToolOutcome("Error: provide blocks with at least one input.")
        if (parsed.blocks.none { it is Block.Input }) return ToolOutcome("Error: ask_user needs at least one input block.")
        val submit = (args["submit_label"] as? JsonPrimitive)?.contentOrNull ?: "Done"
        val answer = prompter.ask(parsed.copy(form = FormSpec(submit))) ?: return ToolOutcome("The user dismissed the question.")
        return ToolOutcome("User answered: $answer")
    }
}
