package dev.ownednotes.core

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/** Waits for the requested durable revision of every open notebook, without owning its writer.
 * Participants receive separate acknowledgments so one failure or cancelled waiter cannot skip
 * the other notebooks' pending saves. Caller invokes this on its document-owning thread.
 */
object OpenNotebookSaves {
    fun await(participants: Iterable<(CompletableFuture<Unit>) -> Unit>): CompletableFuture<Unit> {
        val requests = participants.toList()
        val completed = CompletableFuture<Unit>()
        if (requests.isEmpty()) return completed.apply { complete(Unit) }
        val remaining = AtomicInteger(requests.size)
        requests.forEach { request ->
            val saved = CompletableFuture<Unit>()
            saved.whenComplete { _, error ->
                if (error != null) completed.completeExceptionally(error)
                else if (remaining.decrementAndGet() == 0) completed.complete(Unit)
            }
            try { request(saved) }
            catch (error: Exception) { saved.completeExceptionally(error) }
        }
        return completed
    }
}
