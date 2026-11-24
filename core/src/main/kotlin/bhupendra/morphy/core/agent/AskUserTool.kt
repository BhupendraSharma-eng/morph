package bhupendra.morphy.core.agent

import bhupendra.morphy.core.surface.Block
import bhupendra.morphy.core.surface.FormSpec
import bhupendra.morphy.core.surface.InputKind
import bhupendra.morphy.core.surface.Surface
import bhupendra.morphy.core.surface.SurfaceParser
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
                put("items", RenderUiTool().blockSchema(inputs = true))
            })
        },
    )

    /**
     * Models often express "pick one" as chips, buttons or a list instead of an input block. Rather than reject
     * that (which made the agent retry until it gave up), turn those options into a proper single-choice question.
     */
    private fun asQuestion(s: Surface): Surface? {
        if (s.blocks.any { it is Block.Input }) return s
        val options = s.blocks.flatMap { b ->
            when (b) {
                is Block.Chips -> b.options.map { it.label }
                is Block.Buttons -> b.buttons.map { it.label }
                is Block.ListBlock -> b.items.map { it.title }
                else -> emptyList()
            }
        }.distinct()
        if (options.isEmpty()) return null
        val rest = s.blocks.filterNot { it is Block.Chips || it is Block.Buttons || it is Block.ListBlock }
        return s.copy(blocks = rest + Block.Input(id = "choice", kind = InputKind.Choice, label = "Choose one", options = options, initial = options.first()))
    }

    override suspend fun run(args: JsonObject): ToolOutcome {
        val parsed = SurfaceParser.parse(args)?.let(::asQuestion)
            ?: return ToolOutcome("Error: provide blocks, e.g. {type:'input', id:'pick', kind:'choice', label:'…', options:['A','B']}.")
        val submit = (args["submit_label"] as? JsonPrimitive)?.contentOrNull ?: "Done"
        val answer = prompter.ask(parsed.copy(form = FormSpec(submit))) ?: return ToolOutcome("The user dismissed the question.")
        return ToolOutcome("User answered: $answer")
    }
}
