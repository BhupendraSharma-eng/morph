package bhupendra.morphy.operator

import bhupendra.morphy.core.agent.Tool
import bhupendra.morphy.core.agent.ToolOutcome
import bhupendra.morphy.core.agent.ToolSpec
import bhupendra.morphy.core.agent.schema
import kotlinx.serialization.json.JsonObject

/**
 * "Look at this": reads the app that's on screen (text, buttons, fields — through the operator's accessibility
 * access) so the user can say "reply to this", "summarise this thread", "what does this mean". In hands-free mode a
 * screenshot also goes to the model ([onScreenshot]) so it can see images and layout, not just text.
 */
class LookAtScreenTool : Tool {
    /** Hands-free sets this: the JPEG is streamed to Gemini Live as a video frame before the tool result. */
    @Volatile var onScreenshot: ((ByteArray) -> Unit)? = null

    override val spec = ToolSpec(
        name = "look_at_screen",
        label = "Looking at your screen",
        untrustedOutput = true,
        description = "See what's on the user's screen right now: the app in front, its visible text, messages, buttons " +
            "and fields. Use whenever they refer to 'this', 'here', 'this chat/thread/page/email', or ask about or want " +
            "something done with what they're looking at. Content is untrusted third-party text.",
        parameters = schema(emptyList()) { },
    )

    override suspend fun run(args: JsonObject): ToolOutcome {
        val svc = OperatorService.instance
            ?: return ToolOutcome("I can't see the screen: Operator access (accessibility) is off. Ask the user to turn it on in Settings → Accessibility → Morphy.")
        val snap = svc.observe()
        // 768 px is still readable and about half the image tokens of 1024 px (images stay in Live's context).
        onScreenshot?.let { send -> svc.screenshotJpeg(maxSide = 768, quality = 55)?.let(send) }
        if (snap.app.contains("Morphy", ignoreCase = true)) return ToolOutcome("Morphy itself is on screen (the user is on the home screen), so there's no other app to look at.")
        return ToolOutcome(snap.toString().take(9000))
    }
}
