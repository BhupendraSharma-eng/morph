package bhupendra.morph.core

import bhupendra.morph.core.agent.Agent
import bhupendra.morph.core.agent.AgentEvent
import bhupendra.morph.core.agent.RenderUiTool
import bhupendra.morph.core.agent.Risk
import bhupendra.morph.core.agent.Tool
import bhupendra.morph.core.agent.ToolOutcome
import bhupendra.morph.core.agent.ToolRegistry
import bhupendra.morph.core.agent.ToolSpec
import bhupendra.morph.core.agent.schema
import bhupendra.morph.core.llm.LlmEvent
import bhupendra.morph.core.llm.LlmException
import bhupendra.morph.core.llm.LlmProvider
import bhupendra.morph.core.llm.LlmRequest
import bhupendra.morph.core.llm.Msg
import bhupendra.morph.core.llm.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTest {

    /** Replays scripted responses and records every request it receives. */
    private class ScriptedProvider(vararg script: () -> LlmEvent.Completed) : LlmProvider {
        override val id = "fake"
        private val queue = ArrayDeque(script.toList())
        val requests = mutableListOf<LlmRequest>()
        override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
            requests += request
            val next = queue.removeFirst()()
            if (next.text.isNotEmpty()) emit(LlmEvent.TextDelta(next.text))
            emit(next)
        }
    }

    private val torch = object : Tool {
        var calls = 0
        override val spec = ToolSpec("torch", "toggle", schema { boolean("on", "on") }, Risk.Act)
        override suspend fun run(args: JsonObject): ToolOutcome { calls++; return ToolOutcome("torch is on") }
    }

    private fun agent() = Agent(ToolRegistry(listOf(torch, RenderUiTool())), { "sys" })

    @Test fun `plain text answer finishes in one round`() = runTest {
        val p = ScriptedProvider({ LlmEvent.Completed("Hi!", emptyList()) })
        val events = agent().run(p, "hello").toList()
        assertTrue(events.contains(AgentEvent.Reply("Hi!")))
        assertEquals(AgentEvent.Done, events.last())
        assertEquals(1, p.requests.size)
    }

    @Test fun `tool result is fed back for a follow-up round`() = runTest {
        val p = ScriptedProvider(
            { LlmEvent.Completed("", listOf(ToolCall("c1", "torch", """{"on":true}"""))) },
            { LlmEvent.Completed("Done, it's on.", emptyList()) },
        )
        agent().run(p, "torch on").toList()
        assertEquals(1, torch.calls)
        val second = p.requests[1].messages
        assertTrue(second.last() is Msg.ToolResult)
        assertEquals("torch is on", (second.last() as Msg.ToolResult).content)
    }

    @Test fun `render_ui is terminal and skips the follow-up round`() = runTest {
        val p = ScriptedProvider({
            LlmEvent.Completed("", listOf(ToolCall("c1", "render_ui", """{"title":"Battery","blocks":[{"type":"metric","value":"80%","label":"Battery"}]}""")))
        })
        val events = agent().run(p, "battery?").toList()
        assertEquals(1, p.requests.size)
        assertTrue(events.any { it is AgentEvent.ShowSurface })
    }

    @Test fun `failure rolls history back so the next turn is valid`() = runTest {
        var fail = true
        val p = object : LlmProvider {
            override val id = "flaky"
            val requests = mutableListOf<LlmRequest>()
            override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
                requests += request
                if (requests.size == 1) emit(LlmEvent.Completed("", listOf(ToolCall("c1", "torch", "{}"))))
                else if (fail) { fail = false; throw LlmException("boom") }
                else emit(LlmEvent.Completed("ok", emptyList()))
            }
        }
        val a = agent()
        val events = a.run(p, "one").toList()
        assertTrue(events.any { it is AgentEvent.Failed })
        a.run(p, "two").toList()
        // History must be: user(one), user(two) — no orphaned assistant tool call.
        val msgs = p.requests.last().messages
        assertEquals(listOf(Msg.User("one"), Msg.User("two")), msgs)
    }

    @Test fun `bulky tool results from earlier turns are compacted`() = runTest {
        val big = "x".repeat(5000)
        val reader = object : Tool {
            override val spec = ToolSpec("read", "read", schema { })
            override suspend fun run(args: JsonObject) = ToolOutcome(big)
        }
        val a = Agent(ToolRegistry(listOf(reader)), { "sys" })
        val p = ScriptedProvider(
            { LlmEvent.Completed("", listOf(ToolCall("c1", "read", "{}"))) },
            { LlmEvent.Completed("summary", emptyList()) },
            { LlmEvent.Completed("ok", emptyList()) },
        )
        a.run(p, "read it").toList()
        // Within the turn, the model saw the full page.
        assertEquals(5000, (p.requests[1].messages.last() as Msg.ToolResult).content.length)
        a.run(p, "thanks").toList()
        // On the next turn, the old result is compacted.
        val old = p.requests[2].messages.filterIsInstance<Msg.ToolResult>().single()
        assertTrue(old.content.length < 700)
    }

    @Test fun `the chosen effort is sent with conversational turns`() = runTest {
        val p = ScriptedProvider({ LlmEvent.Completed("hi", emptyList()) })
        Agent(ToolRegistry(), { "sys" }, reasoningEffort = { "high" }).run(p, "hello").toList()
        assertEquals("high", p.requests.single().reasoningEffort)
    }
}
