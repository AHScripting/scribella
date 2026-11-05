package dev.ownednotes.app

import dev.ownednotes.core.CancellableSyncSource
import dev.ownednotes.core.FolderSync
import dev.ownednotes.core.SyncCancellation
import dev.ownednotes.core.WebDavFolder
import dev.ownednotes.core.WebDavHttpClient
import dev.ownednotes.core.WebDavRecovery
import dev.ownednotes.core.WebDavTransferJournal
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.concurrent.atomic.AtomicBoolean

/** One immutable account snapshot; IO-thread only. Account identity survives password refresh and process restart. */
internal class WebDavSource(
    private val account: WebDavAccountStore.Account,
    private val durableRoot: File,
    private val cacheRoot: File,
    private val connect: (WebDavAccountStore.Account) -> WebDavHttpClient = {
        WebDavHttpClient.open(it.endpoint, it.username, it.password)
    },
    private val openFolder: (WebDavHttpClient, WebDavTransferJournal, File, java.util.UUID) -> FolderSync.Folder =
        { client, journal, cache, id -> WebDavFolder.openResumable(client, journal, cache, id) },
) {
    val identity: String get() = "webdav:${account.id}"
    val agreementFile: File get() = File(File(durableRoot, account.id.toString()), "sync-state.txt")
    override fun toString() = "WebDAV sync source (account details hidden)"

    fun journal(): WebDavTransferJournal = WebDavTransferJournal.open(
        directory(directory(durableRoot), account.id.toString()).let { directory(it, "journal") }, account.endpoint, account.id)

    /** Binds stop before probes start, then detaches so LibrarySync can take over the same token. */
    fun open(cancellation: SyncCancellation? = null): FolderSync.Folder {
        checkRunning(cancellation)
        val journal = journal()
        if (journal.pending() != null) throw WebDavHttpClient.Failure(WebDavHttpClient.Problem.RECOVERY_REQUIRED)
        val cache = directory(directory(cacheRoot), account.id.toString())
        checkRunning(cancellation)
        val client = connect(account)
        var registration: Closeable? = null
        var delegate: FolderSync.Folder? = null
        try {
            registration = cancellation?.register { client.close() }
            checkRunning(cancellation)
            delegate = openFolder(client, journal, cache, account.id)
            checkRunning(cancellation)
            return OwnedFolder(delegate, client)
        } catch (error: Throwable) {
            closeAfterFailure(delegate as? Closeable, error)
            closeAfterFailure(client, error)
            throw error
        } finally { registration?.close() }
    }

    /** No probes, writes, automatic acknowledgement or local agreement changes. */
    fun inspectRecovery(cancellation: SyncCancellation? = null): WebDavRecovery? {
        checkRunning(cancellation)
        val journal = journal()
        if (journal.pending() == null) return null
        return connected(cancellation) { it.inspectRecovery(journal) }
    }

    /** Explicit action only: core re-observes the exact attempt and refuses conflicts/stale IDs. */
    fun reconcile(expectedAttemptId: String, cancellation: SyncCancellation? = null): WebDavRecovery {
        checkRunning(cancellation)
        val journal = journal()
        if (journal.pending()?.id != expectedAttemptId) throw WebDavHttpClient.Failure(WebDavHttpClient.Problem.RECOVERY_REQUIRED)
        return connected(cancellation) { client -> client.verifyWriteSupport().reconcile(journal, expectedAttemptId) }
    }

    private fun <T> connected(cancellation: SyncCancellation?, action: (WebDavHttpClient) -> T): T {
        checkRunning(cancellation)
        return connect(account).use { client ->
            val registration = cancellation?.register { client.close() }
            try { checkRunning(cancellation); action(client).also { checkRunning(cancellation) } }
            finally { registration?.close() }
        }
    }

    private class OwnedFolder(private val folder: FolderSync.Folder, private val client: WebDavHttpClient) :
        FolderSync.Folder, Closeable, CancellableSyncSource {
        private val closed = AtomicBoolean()
        private val cancelled = AtomicBoolean()
        private fun checkOpen() {
            if (closed.get() || cancelled.get()) throw WebDavHttpClient.Failure(WebDavHttpClient.Problem.CANCELLED)
        }
        override fun list(): Map<String, FolderSync.Stat> { checkOpen(); return folder.list() }
        override fun read(name: String): java.io.InputStream { checkOpen(); return folder.read(name) }
        override fun write(name: String, from: File): FolderSync.Stat { checkOpen(); return folder.write(name, from) }
        override fun delete(name: String) { checkOpen(); folder.delete(name) }
        override fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            try { (folder as? CancellableSyncSource)?.cancel() } finally { client.close() }
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            client.use { (folder as? Closeable)?.close() }
        }
    }

    private fun checkRunning(cancellation: SyncCancellation?) {
        if (cancellation?.isCancelled == true) throw WebDavHttpClient.Failure(WebDavHttpClient.Problem.CANCELLED)
    }

    private fun directory(root: File, child: String? = null): File {
        val value = if (child == null) root else File(root, child)
        try {
            Files.createDirectories(value.toPath())
            check(Files.isDirectory(value.toPath(), NOFOLLOW_LINKS))
        } catch (_: Exception) { throw WebDavHttpClient.Failure(WebDavHttpClient.Problem.STORAGE) }
        return value
    }

    private fun closeAfterFailure(value: Closeable?, error: Throwable) {
        try { value?.close() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
    }

    companion object {
        /** Never called on the UI thread; loading may need Android Keystore. No network during construction. */
        fun forApp(app: NotesApplication): WebDavSource {
            val account = app.webDavAccounts.load() ?: throw WebDavHttpClient.Failure(WebDavHttpClient.Problem.ACCESS)
            val scope = WebDavAccountStore.namespace(app)
            return WebDavSource(account, File(app.noBackupFilesDir, "webdav-source-$scope"), File(app.cacheDir, "webdav-source-$scope"))
        }
    }
}
