package bhupendra.morph.auto

import bhupendra.morph.core.surface.Action
import bhupendra.morph.core.surface.Block
import bhupendra.morph.core.surface.ButtonSpec
import bhupendra.morph.core.surface.ButtonStyle
import bhupendra.morph.core.surface.Item
import bhupendra.morph.core.surface.Option
import bhupendra.morph.core.surface.Surface
import bhupendra.morph.core.surface.Tone
import bhupendra.morph.data.CanvasItem

/** Nothing happens behind your back: every automatic action becomes a card on the canvas. */
fun autoCard(e: AutoEvent): CanvasItem = when (e) {
    is AutoEvent.Replied -> CanvasItem.Card(Surface(
        title = "Replied to ${e.to}", icon = "💬", tone = Tone.Secondary,
        blocks = listOf(
            Block.ListBlock(listOf(
                Item(e.incoming, "${e.to} · ${e.app}", icon = "📨"),
                Item(e.reply, "Morph replied", icon = "🤖"),
            )),
            Block.Chips(listOf(
                Option("Stop this rule", Action.RunTool("remove_auto_reply", """{"id":"${e.rule.id}"}""")),
                Option("Open ${e.app}", Action.Launch(e.app)),
                Option("Activity", Action.OpenPage("activity")),
            )),
        ),
    ))
    is AutoEvent.Urgent -> CanvasItem.Card(Surface(
        title = "${e.emergency.who} needed you", icon = "🚨", tone = Tone.Error,
        blocks = buildList {
            add(Block.Heading(e.emergency.reason, eyebrow = "Emergency detected · ${e.emergency.app}"))
            add(Block.Text("“${e.emergency.message}”"))
            e.reply?.let { add(Block.Text("I replied: **$it**")) }
            add(Block.Buttons(listOf(
                ButtonSpec("Open ${e.emergency.app}", Action.Launch(e.emergency.app), ButtonStyle.Filled),
                ButtonSpec("Silence", Action.RunTool("stop_alarm", "{}"), ButtonStyle.Tonal),
            )))
        },
    ))
    is AutoEvent.Failed -> CanvasItem.Notice("Couldn't auto-reply to ${e.to} on ${e.app}: ${e.why}", error = true)
    is AutoEvent.Screened -> CanvasItem.Card(Surface(
        title = "${e.who} wanted to reach you", icon = "🫶", tone = Tone.Tertiary,
        blocks = buildList {
            add(Block.Heading("Not urgent — I handled it", eyebrow = "Screened · ${e.app}"))
            add(Block.ListBlock(listOfNotNull(
                Item(e.explanation, "${e.who} said", icon = "📨"),
                e.reply?.let { Item(it, "Morph replied", icon = "🤖") },
            )))
            add(Block.Chips(listOf(Option("Open ${e.app}", Action.Launch(e.app)), Option("Activity", Action.OpenPage("activity")))))
        },
    ))
}
