package dev.ownednotes.app

import java.io.IOException

/**
 * P711: RSAF (rclone) uploads a file to OneDrive when it is closed. A large file written into a document
 * that was created a moment before can fail there with "409 resourceModified": the empty file RSAF is
 * still uploading changes the item under the big upload. So a large write into a new document first
 * waits for that empty upload to settle, and a write that fails is tried again into a fresh document.
 */
internal object ProviderUpload {
    /** OneDrive takes files above this size in an upload session, where the 409 happens. */
    const val LARGE_BYTES = 4L * 1024 * 1024
    const val TRIES = 3
    @Volatile var settleMillis = 7_000L
    @Volatile var retryMillis = 8_000L

    fun settle(fresh: Boolean, size: Long) {
        if (fresh && size > LARGE_BYTES && settleMillis > 0) Thread.sleep(settleMillis)
    }

    /** Runs [attempt] up to [TRIES] times while it throws [IOException]; the attempt cleans up after itself. */
    fun <T> retrying(attempt: (Int) -> T): T {
        var failure: IOException? = null
        for (i in 0 until TRIES) {
            try { return attempt(i) } catch (e: IOException) {
                failure?.let { e.addSuppressed(it) }
                failure = e
                if (i < TRIES - 1 && retryMillis > 0) Thread.sleep(retryMillis * (i + 1))
            }
        }
        throw failure!!
    }
}
