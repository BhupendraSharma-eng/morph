package bhupendra.morph.core

import bhupendra.morph.core.surface.Action
import bhupendra.morph.core.surface.Block
import bhupendra.morph.core.surface.SurfaceParser
import bhupendra.morph.core.surface.Tone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SurfaceParserTest {
    private fun parse(s: String) = SurfaceParser.parse(Json.parseToJsonElement(s) as JsonObject)

    @Test fun `parses a mixed surface and drops unknown blocks`() {
        val s = parse("""{"title":"Plan","tone":"tertiary","blocks":[
            {"type":"heading","text":"Tonight"},
            {"type":"hologram","text":"nope"},
            {"type":"chips","options":["Book a table",{"label":"Call Sam","action":{"type":"say","text":"call sam"}}]},
            {"type":"progress","value":40}
        ]}""")!!
        assertEquals(Tone.Tertiary, s.tone)
        assertEquals(3, s.blocks.size)
        val chips = s.blocks[1] as Block.Chips
        assertEquals(Action.Say("Book a table"), chips.options[0].action)
        assertEquals(Action.Say("call sam"), chips.options[1].action)
        assertEquals(0.4f, (s.blocks[2] as Block.Progress).value, 0.001f)
    }

    @Test fun `accepts common key variations`() {
        val s = parse("""{"blocks":[{"type":"list","items":[{"text":"Milk","description":"2L"}]},{"type":"apps","apps":[{"name":"Spotify"}]}]}""")!!
        assertEquals("Milk", (s.blocks[0] as Block.ListBlock).items[0].title)
        assertEquals(listOf("Spotify"), (s.blocks[1] as Block.Apps).apps)
    }

    @Test fun `empty surface is rejected`() {
        assertNull(parse("""{"blocks":[{"type":"unknown"}]}"""))
    }
}
