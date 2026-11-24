package bhupendra.morphy.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import bhupendra.morphy.agent.LedgerEntry
import bhupendra.morphy.agent.Origin
import bhupendra.morphy.auto.Emergency
import bhupendra.morphy.auto.ForYouItem
import bhupendra.morphy.core.surface.Action
import bhupendra.morphy.core.surface.Block
import bhupendra.morphy.core.surface.FormSpec
import bhupendra.morphy.core.surface.InputKind
import bhupendra.morphy.core.surface.Item
import bhupendra.morphy.core.surface.Option
import bhupendra.morphy.core.surface.Surface
import bhupendra.morphy.core.surface.Tone
import bhupendra.morphy.data.CanvasItem
import bhupendra.morphy.device.Notif
import java.time.LocalDate
import kotlinx.coroutines.launch
import java.time.temporal.TemporalAdjusters

/**
 * Showcase mode for screenshots and demos: every screen shows realistic but entirely fictional people,
 * messages and notifications. In-memory only (never persisted) and switched from a computer via the
 * adb-only ConfigReceiver:  --es demo on|off  --es scene home|research|plan|alert|activity|settings
 */
object Demo {
    var active by mutableStateOf(false)
    var scene by mutableStateOf("home")
    /** Sheet the activity should open for the current scene (consumed by MainActivity). */
    var sheet by mutableStateOf<String?>(null)

    val canvas = mutableStateListOf<CanvasItem>()
    val waiting = mutableStateOf(emptySet<String>())

    fun apply(on: Boolean, newScene: String?) {
        active = on
        scene = newScene ?: scene
        canvas.clear()
        waiting.value = emptySet()
        sheet = null
        if (!on) return
        when (scene) {
            "research" -> canvas.addAll(research())
            "plan" -> { canvas.addAll(plan()); waiting.value = setOf("demo-plan") }
            // Benchmarks (no model calls): a heavy canvas, and a long reply streamed token by token.
            "stress" -> repeat(10) { i -> canvas.addAll(research().map { it.rekey("s$i") }); canvas.addAll(plan().map { it.rekey("p$i") }) }
            "stream" -> stream()
            "activity" -> sheet = "activity"
            "settings" -> sheet = "settings"
        }
    }

    val emergency: Emergency?
        get() = if (active && scene == "alert") Emergency(
            who = "Dad", app = "WhatsApp", reason = "Accident",
            message = "Accident ho gaya highway pe, main theek hoon par gaadi phas gayi, call kar",
        ) else null

    // ---- Home: what needs you ----------------------------------------------------------------------

    val forYou: List<ForYouItem>
        get() = if (!active || scene == "alert") emptyList() else listOf(
            ForYouItem("demo/mom", "Mom", "WhatsApp", "com.whatsapp", "Mom wants to know when you'll be home tonight",
                listOf("Running late, 9 tak", "Abhi nikla hoon"), now(4),
                ask = Block.Input("value", InputKind.Time, "Home by", initial = "20:30"), template = "Mom, {value} tak ghar aa jaunga"),
            ForYouItem("demo/rahul", "Rahul", "Slack", "com.Slack", "Rahul asks if you can move today's sync to 4pm",
                listOf("4pm works", "Let's keep 5pm"), now(11)),
            ForYouItem("demo/ananya", "Ananya", "Instagram", "com.instagram.android", "Ananya asks if you're up for cycling tomorrow morning",
                listOf("Haan, chalte hain! 🚴", "Kal nahi, Sunday?"), now(26)),
        )

    val notifications: List<Notif>
        get() = listOf(
            Notif("demo-n1", "com.google.android.gm", "Gmail", "Interview — Round 2", "Please confirm your slot for Monday 10am by tonight", now(8)),
            Notif("demo-n2", "com.bank", "Bank", "Autopay failed", "₹649 to Spotify failed — insufficient balance", now(35)),
            Notif("demo-n3", "com.food", "Food", "Order delivered", "Enjoy your meal!", now(52)),
        )

    /** Decision-model importance for the demo notifications (same scale the real ranker produces). */
    val scores = mapOf("demo-n1" to 0.82, "demo-n2" to 0.64, "demo-n3" to 0.12)

    val ledger: List<LedgerEntry>
        get() = listOf(
            LedgerEntry(now(3), Origin.User, "Replying", "Ananya", "Sent instantly via the notification: \"Haan, chalte hain! 🚴\""),
            LedgerEntry(now(9), Origin.Automation, "Auto-replied to Mom", "WhatsApp: khana kha liya?", "Haan Mom, abhi khaya. Aap bhi kha lo! 🙂"),
            LedgerEntry(now(14), Origin.Automation, "Handled Rohan's \"urgent\" request", "WhatsApp: Need your Netflix password",
                "Not urgent enough to interrupt · replied: I'll let him know — he'll get back when free!"),
            LedgerEntry(now(40), Origin.User, "Using your apps", "In WhatsApp, send the trip photos to Weekend Gang",
                "Done in 7 steps: sent 4 photos to Weekend Gang. The user is back on the home screen."),
            LedgerEntry(now(65), Origin.User, "Setting a timer", "{\"minutes\":25}", "Timer set for 25 min."),
            LedgerEntry(now(120), Origin.Automation, "Emergency alarm", "Dad on WhatsApp: accident on the highway", "Accident · replied: I've alerted him — he'll call right away."),
        )

    // ---- Conversations -----------------------------------------------------------------------------

    private fun research() = listOf(
        CanvasItem.UserEcho("What happened in AI this week?", "demo-u1"),
        CanvasItem.Reply("Three things worth knowing — I read the sources so you don't have to.", false, "demo-r1"),
        CanvasItem.Card(Surface(
            id = "demo-news", title = "AI this week", icon = "🧠", tone = Tone.Primary,
            blocks = listOf(
                Block.Text("Model releases dominated: faster, cheaper **decision models** arrived from three labs, and on-device agents got serious."),
                Block.ListBlock(listOf(
                    Item("Decision models go mainstream", "Three labs ship typed yes/no & choice APIs · 2h ago", "⚡", action = Action.Say("Tell me more about decision models")),
                    Item("Phones get agentic assistants", "On-device agents that operate apps · 5h ago", "📱", action = Action.Say("How do phone agents work?")),
                    Item("Open-weights model tops coding benchmark", "New SOTA on agentic coding · 1d ago", "🏆", action = Action.Say("Which open model and how good?")),
                )),
                Block.Chips(listOf(
                    Option("Go deeper on #1", Action.Say("Go deeper on decision models")),
                    Option("Summarize as audio", Action.Say("Read this to me")),
                    Option("Save to notes", Action.Say("Save this to my notes")),
                )),
            ),
        )),
    )

    private fun plan(): List<CanvasItem> {
        val sat = LocalDate.now().with(TemporalAdjusters.next(java.time.DayOfWeek.SATURDAY))
        return listOf(
            CanvasItem.UserEcho("Plan a weekend trip to Rishikesh for me", "demo-u2"),
            CanvasItem.Reply("Love it! A few quick choices so I get it right:", false, "demo-r2"),
            CanvasItem.Card(Surface(
                id = "demo-plan", title = "Weekend in Rishikesh", icon = "🏞️", tone = Tone.Secondary,
                blocks = listOf(
                    Block.Input("date", InputKind.Date, "Leaving on", initial = sat.toString()),
                    Block.Input("budget", InputKind.Slider, "Budget per person", min = 5000f, max = 30000f, step = 1000f, unit = "₹", initial = "12000"),
                    Block.Input("vibe", InputKind.Choice, "Vibe", options = listOf("Adventure", "Chill", "Spiritual"), initial = "Adventure"),
                    Block.Input("rafting", InputKind.Toggle, "Include river rafting", initial = "true"),
                    Block.Input("people", InputKind.Stepper, "Travellers", min = 1f, max = 10f, initial = "3"),
                ),
                form = FormSpec("Plan it", "Skip"),
            )),
        )
    }

    private var streamJob: kotlinx.coroutines.Job? = null
    private fun stream() {
        streamJob?.cancel()
        val words = ("Here's a plan for the weekend. Leave early Saturday to beat traffic, check in by noon, and do the " +
            "**afternoon rafting** slot when the water is calmer. Evening aarti at Triveni Ghat, then dinner at a cafe " +
            "by the river. Sunday: sunrise trek to Kunjapuri, breakfast, and head back after lunch. ").repeat(6).split(" ")
        val key = "demo-stream"
        canvas.add(CanvasItem.UserEcho("Plan my weekend", "demo-u3"))
        canvas.add(CanvasItem.Reply("", true, key))
        streamJob = kotlinx.coroutines.MainScope().launch {
            val sb = StringBuilder()
            for (w in words) {
                sb.append(w).append(' ')
                val i = canvas.indexOfFirst { it.key == key }; if (i < 0) return@launch
                canvas[i] = CanvasItem.Reply(sb.toString(), true, key)
                kotlinx.coroutines.delay(16)
            }
            val i = canvas.indexOfFirst { it.key == key }; if (i >= 0) canvas[i] = CanvasItem.Reply(sb.toString(), false, key)
        }
    }

    private fun CanvasItem.rekey(p: String): CanvasItem = when (this) {
        is CanvasItem.UserEcho -> copy(key = "$p-$key")
        is CanvasItem.Reply -> copy(key = "$p-$key")
        is CanvasItem.Card -> copy(surface = surface.copy(id = "$p-${surface.id}"), key = "$p-$key")
        is CanvasItem.Notice -> copy(key = "$p-$key")
    }

    private fun now(minutesAgo: Int) = System.currentTimeMillis() - minutesAgo * 60_000L
}
