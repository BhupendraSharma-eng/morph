package bhupendra.morphy.core

import bhupendra.morphy.core.decide.JevDecisions
import bhupendra.morphy.core.decide.Pick
import bhupendra.morphy.core.decide.Rate
import bhupendra.morphy.core.decide.YesNo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DecisionsTest {
    private val qs = mapOf(
        "urgent" to YesNo("Emergency?", "danger", "anything else"),
        "kind" to Pick("Kind?", mapOf("emergency" to "danger", "plan" to "plans")),
        "importance" to Rate("How soon?", listOf("later", "today", "hour", "now")),
    )

    @Test fun `requests use the decisions wire format`() {
        val urgent = JevDecisions.encode(qs.getValue("urgent"))
        assertEquals("noul", (urgent["type"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(setOf("true", "false"), (urgent["criteria"] as JsonObject).keys)
        assertEquals("score", (JevDecisions.encode(qs.getValue("importance"))["type"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    /** A real response captured from typesafe/jev-1.14 on 2026-11-21 (Dad's highway accident message). */
    @Test fun `parses a real Jev response`() {
        val real = Json.parseToJsonElement("""{"model":"typesafe/jev-1.14-20261014","answers":{
            "urgent":{"type":"noul","noul":0.73},
            "kind":{"type":"choice","choice":"emergency","probabilities":{"emergency":0.92,"plan":0.08},"confidence":0.89},
            "importance":{"type":"score","score":2.85,"legend":{"0":"later","1":"today","2":"hour","3":"now"},"probabilities":{"0":0,"1":0.01,"2":0.14,"3":0.85},"confidence":0.8}},
            "usage":{"input_tokens":481,"output_tokens":0,"cost":2.0202e-05}}""") as JsonObject
        val d = JevDecisions.parse(real, qs)
        assertEquals(0.73, d.yes("urgent"), 1e-9)
        assertEquals("emergency", d.pick("kind")?.choice)
        assertEquals(0.95, d.rate("importance")!!.fraction, 0.001)
        assertEquals("typesafe/jev-1.14-20261014", d.model)
    }
}
