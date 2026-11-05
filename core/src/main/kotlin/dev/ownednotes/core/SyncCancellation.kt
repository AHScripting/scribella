package dev.ownednotes.core

import java.io.Closeable

/** Optional terminal cancellation; implementations must return promptly without waiting for IO/cleanup locks. */
interface CancellableSyncSource {
    fun cancel()
}

/** One sync cycle, one active provider. Cleanup remains with its normal owner on the sync executor. */
class SyncCancellation {
    private val lock = Any()
    @Volatile var isCancelled: Boolean = false
        private set
    private var listener: (() -> Unit)? = null

    fun cancel() {
        val action = synchronized(lock) {
            if (isCancelled) return
            isCancelled = true
            listener.also { listener = null }
        }
        invokeSafely(action)
    }

    /** Handles stop-before-registration as well as stop-during-work; returned handle detaches only this hook. */
    fun register(action: () -> Unit): Closeable {
        val alreadyStopped = synchronized(lock) {
            check(listener == null) { "A sync source is already registered" }
            if (isCancelled) true else { listener = action; false }
        }
        if (alreadyStopped) invokeSafely(action)
        return Closeable { synchronized(lock) { if (listener === action) listener = null } }
    }

    private fun invokeSafely(action: (() -> Unit)?) {
        // Cancellation must not break JobService teardown; normal provider close still runs in finally.
        try { action?.invoke() } catch (_: Exception) { }
    }
}
