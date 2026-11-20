package bhupendra.morph.core

import bhupendra.morph.core.llm.LlmEvent
import bhupendra.morph.core.llm.LlmProvider
import bhupendra.morph.core.llm.LlmRequest
import bhupendra.morph.core.llm.Msg
import bhupendra.morph.core.llm.ToolCall
import bhupendra.morph.core.operator.OperatorLoop
import bhupendra.morph.core.operator.OperatorUi
import bhupendra.morph.core.operator.Screen
import bhupendra.morph.core.operator.ScreenSnapshot
import bhupendra.morph.core.operator.Target
import bhupendra.morph.core.operator.UiAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorTest {

    /** A tiny fake chat app. */
    private class FakePhone : Screen {
        var app = "Launcher"
        val done = mutableListOf<String>()
        private val targets = mapOf(1 to Target(1, "Maa"), 2 to Target(2, "Message", false), 3 to Target(3, "Send"),
            4 to Target(4, "PIN", isPassword = true), 5 to Target(5, "Pay ₹639"))
        override suspend fun observe() = ScreenSnapshot(app, "[1] Row \"Maa\"\n[2] Field \"Message\"\n[3] Button \"Send\"\n[4] Field \"PIN\" {password}")
        override fun target(id: Int) = targets[id]
        override suspend fun act(action: UiAction): String {
            done += action.toString()
            if (action is UiAction.OpenApp) app = action.name
            return "ok"
        }
    }

    private class Ui(val allow: Boolean) : OperatorUi {
        val asked = mutableListOf<String>()
        override suspend fun status(text: String) {}
        override suspend fun confirm(question: String): Boolean { asked += question; return allow }
        override suspend fun choose(question: String, options: List<String>) = options.firstOrNull()
    }

    private class Script(vararg calls: Pair<String, String>) : LlmProvider {
        override val id = "s"
        private val q = ArrayDeque(calls.toList())
        val requests = mutableListOf<LlmRequest>()
        override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
            requests += request
            val (name, args) = q.removeFirst()
            emit(LlmEvent.Completed("", listOf(ToolCall("c${requests.size}", name, args))))
        }
    }

    @Test fun `sending requires approval even if the model forgets to ask`() = runTest {
        val phone = FakePhone(); val ui = Ui(allow = true)
        val p = Script(
            "open_app" to """{"app":"WhatsApp"}""", "tap" to """{"id":1}""",
            "type" to """{"id":2,"text":"On my way"}""", "tap" to """{"id":3}""",
            "done" to """{"summary":"Sent"}""",
        )
        val r = OperatorLoop(p, phone, ui, settleMs = 0).run("text maa on my way")
        assertTrue(r.success)
        assertEquals(1, ui.asked.size)                    // the code itself stopped at "Send"
        assertTrue(phone.done.contains("Tap(id=3)"))
    }

    @Test fun `denied send is never performed`() = runTest {
        val phone = FakePhone(); val ui = Ui(allow = false)
        val p = Script("tap" to """{"id":3}""", "give_up" to """{"reason":"user said no"}""")
        val r = OperatorLoop(p, phone, ui, settleMs = 0).run("send it")
        assertFalse(r.success)
        assertFalse(phone.done.contains("Tap(id=3)"))
    }

    @Test fun `never types into password fields`() = runTest {
        val phone = FakePhone()
        val p = Script("type" to """{"id":4,"text":"1234"}""", "give_up" to """{"reason":"login"}""")
        OperatorLoop(p, phone, Ui(true), settleMs = 0).run("log in")
        assertTrue(phone.done.none { it.startsWith("Type") })
    }

    @Test fun `only the latest screen is kept in history`() = runTest {
        val phone = FakePhone()
        val p = Script("back" to "{}", "back" to "{}", "back" to "{}", "done" to """{"summary":"ok"}""")
        OperatorLoop(p, phone, Ui(true), settleMs = 0).run("go back thrice")
        val results = p.requests.last().messages.filterIsInstance<Msg.ToolResult>()
        assertEquals(1, results.count { it.content.contains("Screen now:") })
    }

    @Test fun `autonomous mode sends without asking but still stops for money`() = runTest {
        val phone = FakePhone(); val ui = Ui(allow = true)
        val p = Script("tap" to """{"id":3}""", "tap" to """{"id":5}""", "done" to """{"summary":"ok"}""")
        OperatorLoop(p, phone, ui, settleMs = 0, autonomy = bhupendra.morph.core.agent.Autonomy.Autonomous).run("send then pay")
        assertEquals(listOf("Tap “Pay ₹639”?"), ui.asked)      // Send went straight through; Pay did not
        assertTrue(phone.done.containsAll(listOf("Tap(id=3)", "Tap(id=5)")))
    }

    @Test fun `notes survive in the system prompt and unchanged screens are flagged`() = runTest {
        val phone = FakePhone()
        val p = Script("note" to """{"text":"Order 4821"}""", "tap" to """{"id":1}""", "done" to """{"summary":"ok"}""")
        OperatorLoop(p, phone, Ui(true), settleMs = 0).run("remember the order")
        assertTrue(p.requests.last().system.contains("Order 4821"))
        val tapResult = p.requests.last().messages.filterIsInstance<Msg.ToolResult>().last()
        assertTrue(tapResult.content.contains("didn't change"))
    }
}
