package bhupendra.morphy.data

import android.Manifest
import android.app.role.RoleManager
import android.content.pm.PackageManager
import bhupendra.morphy.AppGraph
import bhupendra.morphy.core.surface.Action
import bhupendra.morphy.core.surface.Block
import bhupendra.morphy.core.surface.ButtonSpec
import bhupendra.morphy.core.surface.ButtonStyle
import bhupendra.morphy.core.surface.Item
import bhupendra.morphy.core.surface.Option
import bhupendra.morphy.core.surface.Surface
import bhupendra.morphy.core.surface.Tone
import bhupendra.morphy.device.DeviceTools
import bhupendra.morphy.device.NotificationFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First run as a conversation on the canvas. Purely local (no model calls), and it only asks for
 * what's actually missing — so it adapts whether this is a fresh install or an existing setup.
 */
class Onboarding(private val g: AppGraph) {
    enum class Step { Name, Brain, Senses, Home, Done }

    private val skipped = mutableSetOf<Step>()
    private var current: Step? = null
    val awaitingName: Boolean get() = current == Step.Name

    /** Call on the main thread whenever the home screen appears or a permission changes. */
    private var finished = false

    fun refresh() {
        val s = g.settings.state.value
        if (!s.loaded || s.onboarded || finished) return
        val next = Step.entries.firstOrNull { it == Step.Done || (it !in skipped && missing(it)) } ?: Step.Done
        if (next == current && next != Step.Senses) return
        current = next
        show(next)
    }

    fun skip(step: String) {
        runCatching { Step.valueOf(step) }.getOrNull()?.let { skipped += it }
        refresh()
    }

    fun acceptName(text: String) {
        val name = text.trim().removePrefix("I'm ").removePrefix("i'm ").removePrefix("call me ").trim().trimEnd('.', '!')
            .split(' ').take(3).joinToString(" ").replaceFirstChar { it.uppercase() }
        g.canvas.add(CanvasItem.UserEcho(text))
        g.scope.launch {
            g.settings.setName(name)
            withContext(Dispatchers.Main) { current = null; refresh() }
        }
    }

    private fun missing(step: Step): Boolean {
        val s = g.settings.state.value
        val ctx = g.app
        return when (step) {
            Step.Name -> s.userName.isBlank()
            Step.Brain -> !s.aiReady && s.aiEnabled
            Step.Senses -> senses().any { !it.granted }
            Step.Home -> !ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
            Step.Done -> true
        }
    }

    private data class Sense(val emoji: String, val title: String, val why: String, val permission: String, val granted: Boolean)

    private fun senses(): List<Sense> {
        val ctx = g.app
        fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return listOf(
            Sense("🔔", "Notifications", "So I can brief you and handle messages", DeviceTools.PERMISSION_NOTIFICATIONS, NotificationFeed.hasAccess(ctx)),
            Sense("📇", "Contacts", "Text and call people by name", Manifest.permission.READ_CONTACTS, has(Manifest.permission.READ_CONTACTS)),
            Sense("🎙️", "Microphone", "Talk to me instead of typing", Manifest.permission.RECORD_AUDIO, has(Manifest.permission.RECORD_AUDIO)),
            Sense("📣", "Alerts", "So an emergency can always reach you", Manifest.permission.POST_NOTIFICATIONS, has(Manifest.permission.POST_NOTIFICATIONS)),
        )
    }

    private fun skipChip(step: Step, label: String) =
        Option(label, Action.RunTool(INTERNAL_TOOL, """{"skip":"${step.name}"}"""))

    private fun show(step: Step) {
        val name = g.settings.state.value.userName
        val surface = when (step) {
            Step.Name -> Surface(id = CARD, tone = Tone.Primary, blocks = listOf(
                Block.Heading("Hi, I'm Morphy 👋", eyebrow = "Your new home screen"),
                Block.Text("No grids, no digging through menus — just tell me what you want. Type it, or tap the orb to talk."),
                Block.Text("First things first: **what should I call you?**"),
                Block.Chips(listOf(skipChip(step, "Skip"))),
            ))
            Step.Brain -> Surface(id = CARD, tone = Tone.Primary, blocks = listOf(
                Block.Heading("Give me a brain 🧠", eyebrow = if (name.isNotBlank()) "Nice to meet you, $name" else "Next"),
                Block.Text("I can open apps, search and set timers on my own. To understand anything you ask, connect an AI. " +
                    "Paste a key once; it's encrypted on this phone."),
                Block.Buttons(listOf(ButtonSpec("Connect an AI", Action.OpenSettings, ButtonStyle.Filled))),
                Block.Chips(listOf(skipChip(step, "Later"))),
            ))
            Step.Senses -> {
                val missing = senses().filterNot { it.granted }
                Surface(id = CARD, tone = Tone.Secondary, blocks = listOf(
                    Block.Heading("A few senses, if that's okay", eyebrow = if (name.isNotBlank()) "Nice to meet you, $name" else "Almost there"),
                    Block.Text("Tap any you're comfortable with — each one unlocks something specific."),
                    Block.ListBlock(missing.map { Item(it.title, it.why, icon = it.emoji, trailing = "Allow", action = Action.RequestPermission(it.permission)) }),
                    Block.Chips(listOf(skipChip(step, "That's enough for now"))),
                ))
            }
            Step.Home -> Surface(id = CARD, tone = Tone.Tertiary, blocks = listOf(
                Block.Heading("Make me your home 🏠"),
                Block.Text("Press Home and you'll land here. You can always switch back in system settings."),
                Block.Buttons(listOf(ButtonSpec("Make Morphy my home", Action.RequestPermission(HOME_ROLE), ButtonStyle.Filled))),
                Block.Chips(listOf(skipChip(step, "Not now"))),
            ))
            Step.Done -> Surface(id = "onboarding-done", tone = Tone.Primary, blocks = listOf(
                Block.Heading("You're all set${if (name.isNotBlank()) ", $name" else ""} ✨"),
                Block.Text("Some things to try — or just say whatever's on your mind:"),
                Block.Chips(listOf(
                    Option("What did I miss?", Action.Say("Brief me on my notifications — what matters?")),
                    Option("Auto-reply while I drive", Action.Say("While I'm driving, auto-reply to my messages and alert me if anything is urgent")),
                    Option("Focus 25 min", Action.Say("timer 25 min")),
                    Option("What can you do?", Action.Say("What can you do for me? Show me with a card.")),
                )),
            ))
        }
        g.canvas.items.removeAll { it.key == CARD }
        g.canvas.add(CanvasItem.Card(surface))
        if (step == Step.Done) {
            current = null
            finished = true
            g.scope.launch { g.settings.setOnboarded() }
        }
    }

    companion object {
        const val CARD = "onboarding"
        const val INTERNAL_TOOL = "morphy.onboarding"
        const val HOME_ROLE = "morphy.home_role"
    }
}
