package bhupendra.morph.operator

import bhupendra.morph.core.agent.Autonomy
import bhupendra.morph.core.agent.Risk
import bhupendra.morph.core.agent.Tool
import bhupendra.morph.core.agent.ToolOutcome
import bhupendra.morph.core.agent.ToolSpec
import bhupendra.morph.core.agent.schema
import bhupendra.morph.core.operator.UiAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** A tool that wants the user's OK before certain calls; the caller asks (out loud, in hands-free) and then runs it. */
interface ConfirmsFirst {
    /** The question to ask before running [args], or null if no OK is needed. */
    fun confirmQuestion(args: JsonObject): String?
}

/**
 * Hands-free drives apps itself: one step per call against the numbered elements from look_at_screen (tap, type,
 * scroll, back, home), each result showing the fresh screen — no separate operator agent, so it's as fast as the
 * conversation. Same code-level guards as the operator: never types into password/PIN/OTP fields, and taps that look
 * final (pay, send, delete… per the autonomy dial) need the user's OK first ([confirmQuestion]).
 */
class ScreenTool(private val autonomy: () -> Autonomy) : Tool, ConfirmsFirst {
    override val spec = ToolSpec(
        name = "screen",
        label = "Using your apps",
        risk = Risk.Act,
        untrustedOutput = true,
        description = "Act on the app on screen, one step at a time, using element numbers from look_at_screen (or the " +
            "previous step's result). action: tap | long_press | type (id, text, enter) | scroll (direction up/down, " +
            "optional id) | back | home. Returns what happened plus the new screen, so you can continue. Use open_app " +
            "to switch apps. Never for passwords or PINs.",
        parameters = schema(listOf("action")) {
            string("action", "What to do", listOf("tap", "long_press", "type", "scroll", "back", "home"))
            integer("id", "Element number from the screen")
            string("text", "type: the text to enter")
            boolean("enter", "type: press enter/send after typing")
            string("direction", "scroll: up or down", listOf("up", "down"))
        },
    )

    override fun confirmQuestion(args: JsonObject): String? {
        val action = parse(args) as? UiAction.Tap ?: return null
        val label = OperatorService.instance?.target(action.id)?.label ?: return null
        return if (autonomy().operatorConfirm.containsMatchIn(label)) "tap “${label.take(60)}”" else null
    }

    override suspend fun run(args: JsonObject): ToolOutcome {
        val svc = OperatorService.instance
            ?: return ToolOutcome("I can't touch the screen: Operator access (accessibility) is off. Ask the user to turn it on in Settings → Accessibility → Morph.")
        val action = parse(args) ?: return ToolOutcome("Error: unknown or incomplete action.")
        if (action is UiAction.Type) {
            val t = svc.target(action.id) ?: return ToolOutcome("No element [${action.id}] on this screen — look_at_screen again.")
            if (t.isPassword) return ToolOutcome("Refused: I never type into password, PIN or OTP fields. Ask the user to do it.")
        }
        val outcome = svc.act(action)
        svc.settle(action)
        return ToolOutcome("$outcome\n\nNow on screen:\n" + svc.observe().toString().take(9000))
    }

    private fun parse(args: JsonObject): UiAction? {
        fun s(k: String) = (args[k] as? JsonPrimitive)?.contentOrNull
        fun i(k: String) = (args[k] as? JsonPrimitive)?.intOrNull
        return when (s("action")) {
            "tap" -> i("id")?.let { UiAction.Tap(it) }
            "long_press" -> i("id")?.let { UiAction.LongPress(it) }
            "type" -> i("id")?.let { UiAction.Type(it, s("text").orEmpty(), s("enter") == "true") }
            "scroll" -> UiAction.Scroll(s("direction") != "up", i("id"))
            "back" -> UiAction.Back
            "home" -> UiAction.Home
            else -> null
        }
    }
}
