package bhupendra.morph.core

import bhupendra.morph.core.agent.PriorityLane
import bhupendra.morph.core.agent.PriorityLane.Priority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class PriorityLaneTest {

    @Test fun `waiting work runs highest priority first, FIFO within a priority`() = runTest {
        val lane = PriorityLane()
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = launch { lane.run(Priority.Background) { gate.await(); order += "running" } }
        yield()
        val jobs = listOf(
            "bg1" to Priority.Background, "pro" to Priority.Proactive, "urgent" to Priority.Urgent, "bg2" to Priority.Background,
        ).map { (name, p) -> launch { lane.run(p) { order += name } }.also { yield() } }
        gate.complete(Unit)
        (jobs + first).forEach { it.join() }
        assertEquals(listOf("running", "urgent", "pro", "bg1", "bg2"), order)
    }

    @Test fun `cancelled waiter is skipped and the lane keeps flowing`() = runTest {
        val lane = PriorityLane()
        val gate = CompletableDeferred<Unit>()
        val holder = launch { lane.run(Priority.Background) { gate.await() } }
        yield()
        val doomed = launch { lane.run(Priority.Urgent) { error("should never run") } }
        yield()
        val ok = async { lane.run(Priority.Background) { "done" } }
        yield()
        doomed.cancel()
        gate.complete(Unit)
        holder.join()
        assertEquals("done", ok.await())
        assertEquals("again", lane.run(Priority.Background) { "again" })   // not stuck busy
    }
}
