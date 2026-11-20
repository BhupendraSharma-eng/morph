package bhupendra.morph.core

import bhupendra.morph.core.llm.Msg
import bhupendra.morph.core.llm.MsgCodec
import bhupendra.morph.core.llm.ToolCall
import bhupendra.morph.core.surface.Action
import bhupendra.morph.core.surface.Block
import bhupendra.morph.core.surface.ButtonSpec
import bhupendra.morph.core.surface.ButtonStyle
import bhupendra.morph.core.surface.Item
import bhupendra.morph.core.surface.Option
import bhupendra.morph.core.surface.Surface
import bhupendra.morph.core.surface.SurfaceCodec
import bhupendra.morph.core.surface.Tone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class CodecTest {

    @Test fun `every block and action survives a round trip`() {
        val s = Surface(
            id = "s1", title = "Everything", icon = "🧪", tone = Tone.Tertiary,
            blocks = listOf(
                Block.Heading("Hi", eyebrow = "Top"),
                Block.Text("Some **bold** text"),
                Block.Metric("82%", "Battery", "🔋"),
                Block.ListBlock(listOf(Item("A", "sub", "📦", "1m", Action.Launch("spotify")), Item("B"))),
                Block.Chips(listOf(Option("Say", Action.Say("hello")), Option("Web", Action.OpenUrl("https://x.y")))),
                Block.Buttons(listOf(
                    ButtonSpec("Tool", Action.RunTool("stop_alarm", "{}"), ButtonStyle.Filled),
                    ButtonSpec("Perm", Action.RequestPermission("android.permission.READ_CONTACTS"), ButtonStyle.Outlined),
                    ButtonSpec("Settings", Action.OpenSettings, ButtonStyle.Tonal),
                )),
                Block.Apps(listOf("com.spotify.music")),
                Block.Progress(0.4f, "Loading"),
            ),
        )
        val back = SurfaceCodec.decode(Json.parseToJsonElement(SurfaceCodec.encode(s).toString()) as JsonObject)
        assertEquals(s, back)
    }

    @Test fun `conversation history round trips including provider extras`() {
        val extra = JsonObject(mapOf("google" to JsonObject(mapOf("thought_signature" to JsonPrimitive("abc")))))
        val msgs = listOf(
            Msg.User("torch on"),
            Msg.Assistant("", listOf(ToolCall("c1", "flashlight", """{"on":true}""", extra))),
            Msg.ToolResult("c1", "flashlight", "Flashlight on."),
            Msg.Assistant("Done!"),
        )
        assertEquals(msgs, MsgCodec.decode(MsgCodec.encode(msgs)))
    }
}
