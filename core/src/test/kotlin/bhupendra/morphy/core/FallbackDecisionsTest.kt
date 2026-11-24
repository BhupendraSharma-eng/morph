package bhupendra.morphy.core

import bhupendra.morphy.core.decide.Decision
import bhupendra.morphy.core.decide.DecisionModel
import bhupendra.morphy.core.decide.FallbackDecisions
import bhupendra.morphy.core.decide.Question
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

class FallbackDecisionsTest {
    private class Fake(override val id: String, var fail: Boolean = false) : DecisionModel {
        var calls = 0
        override suspend fun decide(state: JsonElement, questions: Map<String, Question>): Decision {
            calls++
            if (fail) throw IllegalStateException("down")
            return Decision(emptyMap(), id)
        }
    }

    @Test fun `uses local, falls back when it fails, then skips it during cooldown`() = runBlocking {
        var t = 1_000_000L
        val local = Fake("kev-test-${System.nanoTime()}")
        val cloud = Fake("jev")
        val d = FallbackDecisions(local, cloud, cooldownMs = 60_000, now = { t })
        assertEquals(local.id, d.decide(JsonNull, emptyMap()).model)
        local.fail = true
        assertEquals("jev", d.decide(JsonNull, emptyMap()).model)
        assertEquals(2, local.calls)
        t += 30_000
        assertEquals("jev", d.decide(JsonNull, emptyMap()).model)
        assertEquals(2, local.calls)          // skipped while cooling down
        local.fail = false; t += 31_000
        assertEquals(local.id, d.decide(JsonNull, emptyMap()).model)
    }
}
