package bhupendra.morph.core.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.PriorityQueue

/**
 * Background model work runs one call at a time, highest priority first
 * (emergency triage before proactive briefs before housekeeping). The user's own turns never queue here.
 */
class PriorityLane {
    enum class Priority { Urgent, Proactive, Background }

    private class Ticket(val priority: Priority, val seq: Long) {
        val go = CompletableDeferred<Unit>()
    }

    private val waiting = PriorityQueue<Ticket>(compareBy<Ticket>({ it.priority.ordinal }, { it.seq }))
    private var busy = false
    private var seq = 0L

    suspend fun <T> run(priority: Priority, block: suspend () -> T): T {
        val ticket = synchronized(this) {
            Ticket(priority, seq++).also { t -> if (!busy) { busy = true; t.go.complete(Unit) } else waiting.add(t) }
        }
        try {
            ticket.go.await()
        } catch (e: CancellationException) {
            synchronized(this) {
                if (!waiting.remove(ticket)) release()   // we were granted right as we got cancelled
            }
            throw e
        }
        try {
            return block()
        } finally {
            synchronized(this) { release() }
        }
    }

    private fun release() {
        val next = waiting.poll()
        if (next != null) next.go.complete(Unit) else busy = false
    }
}
