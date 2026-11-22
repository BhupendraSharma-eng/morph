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

class SourceLinksTest {
    @Test fun `source rows without a link get one from recent results`() = runBlocking {
        bhupendra.morph.core.web.SourceLinks.record("Financial Services Data Intelligence Oracle News", "https://www.oracle.com/news/fsdi")
        bhupendra.morph.core.web.SourceLinks.record("Fusion Agentic Applications for Finance Oracle News", "https://www.oracle.com/news/fusion")
        val args = Json.parseToJsonElement("""{"blocks":[{"type":"list","items":[
            {"title":"Fusion Agentic Applications for Finance","subtitle":"Oracle News · Apr 9"},
            {"title":"Something unrelated entirely","subtitle":"Nowhere"}]}]}""") as JsonObject
        val items = (RenderUiTool().run(args).surface!!.blocks.first() as Block.ListBlock).items
        org.junit.Assert.assertEquals(bhupendra.morph.core.surface.Action.OpenUrl("https://www.oracle.com/news/fusion"), items[0].action)
        org.junit.Assert.assertNull(items[1].action)
    }
}
