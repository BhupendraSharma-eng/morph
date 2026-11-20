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
    fun sink(context: android.content.Context): (String) -> Unit = { line ->
        android.util.Log.i("MorphOperator", line)
        runCatching {
            val f = java.io.File(context.getExternalFilesDir(null), "operator-trace.log")
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
        description = "Carry out a task inside other apps by operating the phone like a person: open apps, tap, type, " +
            "scroll. Use when no direct tool can do it — sending a WhatsApp/Telegram message with content, changing an " +
            "in-app setting, finding something inside an app, filling a form. Give ONE complete instruction (who, what, " +
            "which app, exact text). It can handle long multi-app tasks. The user sees every step and can stop it.",
        parameters = schema(listOf("task")) { string("task", "Complete instruction, e.g. 'In WhatsApp, send Maa: Reached home'") },
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
        val s = settings.state.value
        val provider = s.provider() ?: return ToolOutcome("No AI brain connected.")
        // If invoked from the floating assistant, get it out of the way so the app underneath can be operated.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { bhupendra.morph.assist.AssistBridge.hidePanel?.invoke() }
        val overlay = service.overlay
        overlay.show(task.take(48))
        OperatorTrace.sink(service)("=== task (${s.autonomy}, ${s.model}): $task")

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
        val goHome = result?.returnHome == true
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

    companion object { const val ENABLE = "morph.accessibility" }
}
