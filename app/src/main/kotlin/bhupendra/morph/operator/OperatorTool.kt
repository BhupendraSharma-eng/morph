package bhupendra.morph.operator

import bhupendra.morph.core.agent.Risk
import bhupendra.morph.core.agent.Tool
import bhupendra.morph.core.agent.ToolOutcome
import bhupendra.morph.core.agent.ToolSpec
import bhupendra.morph.core.agent.schema
import bhupendra.morph.core.operator.OperatorLoop
import bhupendra.morph.core.operator.OperatorResult
import bhupendra.morph.core.surface.Action
import bhupendra.morph.core.surface.Block
import bhupendra.morph.core.surface.ButtonSpec
import bhupendra.morph.core.surface.ButtonStyle
import bhupendra.morph.core.surface.Surface
import bhupendra.morph.core.surface.Tone
import bhupendra.morph.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import bhupendra.morph.core.decide.decide
import kotlinx.serialization.json.put
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Timed step trace, kept in a file because logcat's ring buffer rotates within minutes on a busy phone.
 *   adb shell cat /sdcard/Android/data/bhupendra.morph/files/operator-trace.log
 */
object OperatorTrace {
    fun sink(context: android.content.Context, file: String = "operator-trace.log", tag: String = "MorphOperator"): (String) -> Unit = { line ->
        android.util.Log.i(tag, line)
        runCatching {
            val f = java.io.File(context.getExternalFilesDir(null), file)
            if (f.length() > 256_000) f.writeText(f.readText().takeLast(128_000))
            f.appendText("${java.time.LocalTime.now().withNano(0)} $line\n")
        }
    }
}

/** Lets the main agent hand a task to the operator, which drives other apps' UI step by step. */
class OperatorTool(private val settings: SettingsStore) : Tool {

    override val spec = ToolSpec(
        name = "operate_phone",
        label = "Using your apps",
        risk = Risk.Act,
        untrustedOutput = true,   // it reads other apps' screens
        description = "LAST RESORT. Operates the phone like a person (open apps, tap, type, scroll) — slow, and the user " +
            "can't use their phone while it runs. Only when no direct tool, app action (find_app_actions / run_app_action) " +
            "or deep link can do the job. If an app action got you to the right screen, this continues from there in a few " +
            "steps. Give ONE complete instruction (who, what, which app, exact text). The user sees every step and can stop it.",
        parameters = schema(listOf("task")) {
            string("task", "Complete instruction, e.g. 'In WhatsApp, send Maa: Reached home'")
            string("why_not_simpler", "One line: which faster routes you considered (direct tools, app actions, deep links) " +
                "and why they can't do this")
            string("end_on", "Where to leave the user afterwards: 'home' when the job is done in the background (message sent, " +
                "setting changed) — the default; 'app' only if they need to see/continue there (playing media, a page to read, " +
                "a form to finish).", listOf("home", "app"))
        },
    )

    override suspend fun run(args: JsonObject): ToolOutcome {
        val task = (args["task"] as? JsonPrimitive)?.contentOrNull ?: return ToolOutcome("Error: task required")
        val service = OperatorService.instance ?: return ToolOutcome(
            "Operator mode isn't enabled. A card asking the user to turn it on is shown.",
            Surface(title = "Let me use your apps", icon = "🦾", tone = Tone.Primary, blocks = listOf(
                Block.Text("Turn on **Morph operator** in Accessibility settings and I can do things inside your apps — " +
                    "step by step, shown on screen, asking before anything irreversible."),
                Block.Buttons(listOf(ButtonSpec("Open settings", Action.RequestPermission(ENABLE), ButtonStyle.Filled))),
            )),
        )
        // Fastest path first, enforced: if the apps themselves offer a matching action and it wasn't checked, send the
        // agent back once with the options. A shortcut opens the right screen instantly; the operator takes over the phone.
        val graph = (service.application as bhupendra.morph.MorphApp).graph
        if (!bhupendra.morph.device.ToolTiers.recentlyLooked() && bhupendra.morph.device.ToolTiers.shouldBounce(task)) {
            val options = graph.catalog.shortcutsForGoal(task, 6)
            if (options.isNotEmpty()) return ToolOutcome(
                "Not started — faster routes exist. These app actions match the task:\n" +
                    options.joinToString("\n") { "- id=${it.key} · ${it.app.label}: ${it.label}" } +
                    "\nIf one does the job (or gets to the right screen), call run_app_action first; operate_phone then " +
                    "continues from that screen. If none fits, call operate_phone again (same task) with why_not_simpler."
            )
        }
        val why = (args["why_not_simpler"] as? JsonPrimitive)?.contentOrNull
        val s = settings.state.value
        val provider = s.provider() ?: return ToolOutcome("No AI brain connected.")
        // If invoked from the floating assistant, get it out of the way so the app underneath can be operated.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { bhupendra.morph.assist.AssistBridge.hidePanel?.invoke() }
        val overlay = service.overlay
        overlay.show(task.take(48))
        OperatorTrace.sink(service)("=== task (${s.autonomy}, ${s.model}): $task" + (why?.let { "\n    why operator: $it" } ?: ""))

        val result: OperatorResult? = coroutineScope {
            val run = async {
                OperatorLoop(
                    provider, service, overlay,
                    maxSteps = s.autonomy.operatorSteps,
                    ownerName = s.userName.ifBlank { "the user" },
                    autonomy = s.autonomy,
                    deepEffort = s.mainEffort?.takeIf { it != "low" },   // escalations think at least as hard as you chose
                    trace = OperatorTrace.sink(service),
                ).run(task)
            }
            overlay.onStop = { run.cancel() }
            try {
                run.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()   // the whole turn was cancelled: propagate
                null                                        // only the task was stopped from the overlay
            }
        }
        // Where to leave the user is decided in code, with evidence — low-effort models kept saying "app" after sending.
        val asked = (args["end_on"] as? JsonPrimitive)?.contentOrNull
        val wantsToSee = STAY.containsMatchIn(task)
        // A typed decision over the whole picture (task + what actually happened); keyword heuristic as fallback.
        val decided: Boolean? = if (result != null && result.success && asked == null) {
            (service.application as bhupendra.morph.MorphApp).graph.runtime.decide(
                bhupendra.morph.core.agent.PriorityLane.Priority.Urgent, bhupendra.morph.agent.Origin.User, preferLocal = true,
            ) { d ->
                d.decide(kotlinx.serialization.json.buildJsonObject {
                    put("task", task); put("outcome", result.summary); put("tapped_send_or_post", result.sentSomething)
                }) {
                    // Goal-based wording: 10/10 on real tasks (step-based wording misread "open the chat and send hi" as "app").
                    pick("end_on", "The task is finished. Judge by its GOAL, not the steps taken: where should the user be left now?",
                        "home" to "the goal was to get something DONE — a message was sent (even if a chat had to be opened to send it), " +
                            "a setting changed, someone added or invited, something counted or looked up",
                        "app" to "the goal was for the user to SEE or USE that screen — media playing, a page/article/product to read, " +
                            "a chat they asked to open without sending anything, a form left for them to finish")
                }.pick("end_on")?.choice == "home"
            }
        } else null
        val goHome = when {
            result == null -> false                      // stopped by the user: leave them where they are
            asked == "home" -> true
            asked == "app" -> false
            !result.success -> result.returnHome         // failures usually need the user right here
            decided != null -> decided
            result.sentSomething && !wantsToSee -> true  // the job (send/post/reply) is done in the background
            else -> result.returnHome
        }
        if (goHome) {
            // Job done in the background: bring the user back to Morph (their home), where the summary lands.
            overlay.hide()
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        } else {
            // The screen is the result (or the user must take over): leave them here with a brief note.
            overlay.finish(when {
                result == null -> "Stopped."
                result.success -> "✓ ${result.summary}"
                else -> result.summary
            })
        }
        val where = if (goHome) " The user is back on the home screen." else " The user was left in the app on that screen."
        return ToolOutcome(when {
            result == null -> "The user stopped the task from the overlay."
            result.success -> "Done in ${result.steps} steps: ${result.summary}.$where"
            else -> "Not completed after ${result.steps} steps: ${result.summary}.$where"
        })
    }

    companion object {
        const val ENABLE = "morph.accessibility"
        private val STAY = Regex("\\b(show me|let me see|leave me|stay|keep it open|open (it|the)|play|watch|read)\\b", RegexOption.IGNORE_CASE)
    }
}
