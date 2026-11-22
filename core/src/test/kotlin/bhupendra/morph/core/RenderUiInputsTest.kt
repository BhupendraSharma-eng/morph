package bhupendra.morph.core

import bhupendra.morph.core.agent.RenderUiTool
import bhupendra.morph.core.surface.Block
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderUiInputsTest {
    @Test fun `inputs on a display card become plain text`() = runBlocking {
        val args = Json.parseToJsonElement("""{"title":"Oracle AI Tools for Finance","blocks":[
            {"type":"input","id":"s","kind":"text","label":"Summary","placeholder":"Oracle launched Treasury Analytics."},
            {"type":"text","text":"More below"}]}""") as JsonObject
        val out = RenderUiTool().run(args)
        val blocks = out.surface!!.blocks
        assertTrue(blocks.none { it is Block.Input })
        assertTrue(blocks.any { it is Block.Text && it.text.contains("Treasury Analytics") })
    }
}
