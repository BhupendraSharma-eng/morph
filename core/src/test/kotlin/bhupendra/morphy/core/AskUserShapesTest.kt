package bhupendra.morphy.core

import bhupendra.morphy.core.agent.AskUserTool
import bhupendra.morphy.core.surface.Block
import bhupendra.morphy.core.surface.InputKind
import bhupendra.morphy.core.surface.Surface
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The four shapes GLM actually produced for "ask me to pick one" (captured 2026-11-21). All must work. */
class AskUserShapesTest {
    private suspend fun ask(json: String): Pair<Surface?, String> {
        var shown: Surface? = null
        val out = AskUserTool { s -> shown = s; JsonObject(mapOf("choice" to JsonPrimitive("Retry"))) }
            .run(Json.parseToJsonElement(json) as JsonObject)
        return shown to out.result
    }

    private fun choiceOf(s: Surface?) = s!!.blocks.filterIsInstance<Block.Input>().single()

    @Test fun `chips and buttons become a single choice`() = runTest {
        val (s, r) = ask("""{"blocks":[{"type":"text","text":"Stopped. What now?"},
            {"type":"chips","items":[{"title":"Retry send to AI group"},{"title":"Pick a different group"},{"title":"Cancel task"}]},
            {"type":"buttons","buttons":[{"label":"Retry now"},{"label":"Cancel task"}]}]}""")
        assertTrue(r.startsWith("User answered"))
        assertEquals(InputKind.Choice, choiceOf(s).kind)
        assertTrue(choiceOf(s).options.containsAll(listOf("Retry send to AI group", "Retry now", "Cancel task")))
    }

    @Test fun `kind wins over a wrong type`() = runTest {
        val (s, _) = ask("""{"blocks":[{"default":"Retry","id":"action","kind":"choice","type":"text",
            "options":[{"label":"Retry"},{"label":"Pick a different group"},{"label":"Cancel"}]}]}""")
        assertEquals("action", choiceOf(s).id)
        assertEquals(listOf("Retry", "Pick a different group", "Cancel"), choiceOf(s).options)
    }

    @Test fun `a list of options becomes a choice`() = runTest {
        val (s, _) = ask("""{"blocks":[{"type":"list","items":[{"title":"Retry"},{"title":"Pick a different group"},{"title":"Cancel"}]}]}""")
        assertEquals(3, choiceOf(s).options.size)
    }

    @Test fun `input-ish types and missing ids are accepted`() = runTest {
        val (s, _) = ask("""{"blocks":[{"type":"radio","label":"Which group?","options":["AI","AI Club"]}]}""")
        assertEquals("which_group", choiceOf(s).id)
        assertEquals(InputKind.Choice, choiceOf(s).kind)
    }
}
