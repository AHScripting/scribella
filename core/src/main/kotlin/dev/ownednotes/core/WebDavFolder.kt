package dev.ownednotes.core

import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Blocking FolderSync bridge, inactive until an account/source explicitly selects it.
 * Owns its client after successful open; use on the library's save/sync executor and close after
 * each sync cycle. The caller persists the journal/account ID, sync agreement and notebook versions.
 * privateCache must be app-private storage, never the library, materials or shared sync directory.
 */
class WebDavFolder private constructor(
    private val client: WebDavHttpClient,
    private val writer: WebDavHttpClient.Writer,
    private val journal: WebDavTransferJournal,
    private val scratch: File,
    private val downloads: WebDavDownloadCache? = null,
) : FolderSync.Folder, Closeable, CancellableSyncSource {
    private val lock = Any()
    private val closed = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private var entries: MutableMap<String, WebDavListing.Entry>? = null
    private val snapshots = mutableSetOf<SnapshotInput>()

    override fun list(): Map<String, FolderSync.Stat> = synchronized(lock) {
        checkOpen()
        entries = null // A failed refresh cannot leave an older listing usable.
        client.requireReadyJournal(journal)
        val listing = client.list()
        checkOpen()
        entries = listing.toMutableMap()
        listing.mapValues { it.value.syncStat() }
    }

    override fun read(name: String): InputStream = synchronized(lock) {
        checkOpen()
        val entry = listing()[validName(name)] ?: throw WebDavHttpClient.Failure(WebDavHttpClient.Problem.CHANGED)
        val file = storage { File.createTempFile("download-", ".part", scratch) }
        try {
            if (downloads == null) client.download(entry, file) else downloads.withSlot(entry) { slot ->
                client.downloadResumable(entry, file, slot, downloads.accountId)
            }
            checkOpen()
            val stream = storage { SnapshotInput(file.inputStream(), file) }
            snapshots += stream
            stream
        } catch (error: Throwable) {
            discard(file)
            throw error
        }
    }

    override fun write(name: String, from: File): FolderSync.Stat = synchronized(lock) {
        checkOpen()
        val values = listing()
        validName(name)
        val previous = values[name]
        val tag = previous?.let { it.strongEtag ?: throw WebDavHttpClient.Failure(WebDavHttpClient.Problem.UNSUPPORTED) }
        val sha = storage {
            if (!Files.isRegularFile(from.toPath(), NOFOLLOW_LINKS)) throw WebDavHttpClient.Failure(WebDavHttpClient.Problem.STORAGE)
            FolderSync.sha256(from.inputStream())
        }
        val result = writer.upload(journal, name, from, sha, tag)
        checkOpen()
        val updated = WebDavListing.Entry(name, result.bytes, result.etag)
        values[name] = updated
        updated.syncStat()
    }

    override fun delete(name: String): Unit = synchronized(lock) {
        checkOpen()
        val values = listing()
        validName(name)
        val entry = values[name] ?: return@synchronized
        writer.delete(journal, entry)
        checkOpen()
        values.remove(name)
    }

    private fun listing() = entries ?: throw WebDavHttpClient.Failure(WebDavHttpClient.Problem.CHANGED)
    private fun validName(name: String): String {
        if (!WebDavEndpoint.SYNC_NAME.matches(name)) throw WebDavHttpClient.Failure(WebDavHttpClient.Problem.CONTENT)
        return name
    }
    private fun checkOpen() { if (closed.get() || cancelled.get()) throw WebDavHttpClient.Failure(WebDavHttpClient.Problem.CANCELLED) }

    /** Abort the client without taking the folder monitor or deleting local files on the caller's thread. */
    override fun cancel() {
        if (cancelled.compareAndSet(false, true)) client.close()
    }

    private inner class SnapshotInput(input: InputStream, private val file: File) : FilterInputStream(input) {
        private var ended = false
        override fun close(): Unit = synchronized(lock) {
            if (ended) return@synchronized
            ended = true
            try { storage { super.close() } }
            finally { snapshots.remove(this); discard(file) }
        }
    }

    /** Cancel network IO first, before waiting for the local operation/stream cleanup monitor. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancel()
        synchronized(lock) {
            entries = null
            for (stream in snapshots.toList()) {
                try { stream.close() } catch (_: IOException) { }
            }
            discard(scratch) // Only the owned directory, and only if empty; never recursive/sweep.
        }
    }

    companion object {
        /** No native writes or guessed recovery here. Failure before return leaves client ownership with caller. */
        fun open(client: WebDavHttpClient, journal: WebDavTransferJournal, privateCache: File): WebDavFolder {
            return openInternal(client, journal, privateCache, null)
        }

        /** Explicit account-bound durable downloads. Existing open remains unchanged. */
        fun openResumable(client: WebDavHttpClient, journal: WebDavTransferJournal, privateCache: File,
                          accountId: java.util.UUID): WebDavFolder =
            openInternal(client, journal, privateCache, client.downloadCache(journal, privateCache, accountId))

        private fun openInternal(client: WebDavHttpClient, journal: WebDavTransferJournal, privateCache: File,
                                 downloads: WebDavDownloadCache?): WebDavFolder {
            client.requireReadyJournal(journal) // Validate endpoint/scope/pending before any synthetic probes.
            val scratch = storage {
                if (!Files.isDirectory(privateCache.toPath(), NOFOLLOW_LINKS)) throw WebDavHttpClient.Failure(WebDavHttpClient.Problem.STORAGE)
                Files.createTempDirectory(privateCache.toPath(), "webdav-folder-").toFile()
            }
            try { return WebDavFolder(client, client.verifyWriteSupport(), journal, scratch, downloads) }
            catch (error: Throwable) { discard(scratch); throw error }
        }

        private fun discard(file: File) {
            try { Files.deleteIfExists(file.toPath()) } catch (_: IOException) { } catch (_: SecurityException) { }
        }
        private fun <T> storage(block: () -> T): T = try { block() }
            catch (error: WebDavHttpClient.Failure) { throw error }
            catch (_: IOException) { throw WebDavHttpClient.Failure(WebDavHttpClient.Problem.STORAGE) }
            catch (_: SecurityException) { throw WebDavHttpClient.Failure(WebDavHttpClient.Problem.STORAGE) }
    }
}
