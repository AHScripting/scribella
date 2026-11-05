package dev.ownednotes.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import dev.ownednotes.core.FolderSync
import dev.ownednotes.core.CancellableSyncSource
import dev.ownednotes.core.SyncCancellation
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.Closeable

/**
 * P139 (F170, F167, F180): two-way sync of the library with a "Scribella sync" folder inside a folder the user picked
 * (for example a OneDrive or Google Drive folder that the cloud app keeps on each device). The rules live in
 * [FolderSync]; this keeps the chosen folder, the pause switch, the last result and the per-folder state.
 */
object LibrarySync {
    /** P566: from resources, because the owner's debug app keeps its existing folder's name; "Scribella sync" in public builds. */
    fun folderName(context: Context): String = context.getString(R.string.sync_folder)
    private const val WEBDAV_KEY = "sync_webdav_id"
    private const val RECOVERY_KEY = "sync_webdav_recovery"
    private const val TREE_KEY = "sync_tree"
    private const val LABEL_KEY = "sync_label"
    private const val PAUSED_KEY = "sync_paused"
    private const val LAST_KEY = "sync_last"
    private const val RESULT_KEY = "sync_result"
    private const val PROBLEM_KEY = "sync_problem"
    private const val WAITING_KEY = "sync_waiting"
    private const val WIFI_KEY = "sync_wifi_only"
    const val WIFI_WAITING = "Waiting for Wi-Fi. Your notebooks are saved on this tablet. Connect to Wi-Fi, then choose Sync now."
    private const val STATE_FILE = "sync-state.txt"
    private const val PART_FOLDERS_KEY = "sync_changed_page_folders"
    /** Explicit source membership, retained for released notebooks after the current folder changes. */
    fun changedPages(context: Context, identity: String? = sourceIdentity(context)): Boolean =
        identity != null && identity in preferences(context).getStringSet(PART_FOLDERS_KEY, emptySet()).orEmpty()
    private fun changedWorkspace(app: NotesApplication, identity: String): File =
        File(app.storageRoot, "changed-page-sync/" + FolderSync.sha256(identity.byteInputStream()))
    private fun changedSource(app: NotesApplication, remote: FolderSync.NestedFolder, identity: String,
        initialize: Boolean = false, cancelled: () -> Boolean = { false }) =
        dev.ownednotes.core.ChangedPageFolder.open(remote, changedWorkspace(app, identity), app.attachmentDirectory, identity, initialize, cancelled)

    /** Prepare only an explicitly chosen new source. Production configuration changes only after validation. */
    internal fun prepareChangedFolder(app: NotesApplication, tree: Uri, done: (Result<Unit>) -> Unit) {
        if (tree == folderUri(app)) { done(Result.failure(IOException("Choose a different empty test folder. The current sync folder was kept."))); return }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try { app.contentResolver.takePersistableUriPermission(tree, flags) }
        catch (error: Exception) { done(Result.failure(error)); return }
        app.io.execute {
            val result = runCatching {
                changedSource(app, TreeFolder(app, tree), tree.toString(), initialize = true).use { }
                Unit
            }
            app.main.post {
                val selected = result.mapCatching {
                    preferences(app).edit().putStringSet(PART_FOLDERS_KEY,
                        preferences(app).getStringSet(PART_FOLDERS_KEY, emptySet()).orEmpty() + tree.toString()).apply()
                    setFolder(app, tree)
                }
                if (selected.isFailure) FolderAccess.releaseIfUnused(app, tree)
                done(selected)
            }
        }
    }

    /** [folderName] inside a picked document tree, created on first use. */
    class TreeFolder private constructor(private val context: Context, private val tree: Uri, createIfMissing: Boolean,
        private val path: List<String>, private val rootName:String) : FolderSync.NestedFolder {
        constructor(context: Context, tree: Uri, createIfMissing: Boolean = true, rootName:String = folderName(context)): this(context, tree, createIfMissing, emptyList(),rootName)
        override fun child(name: String, create: Boolean): FolderSync.NestedFolder {
            FolderSync.requireChildName(name)
            return TreeFolder(context, tree, create, path + name,rootName)
        }
        private val resolver = context.contentResolver
        private companion object { const val PART = ".uploading" }
        private class InvalidListing : IOException("The sync folder returned incomplete or ambiguous file information."), FolderSync.ClassifiedFailure {
            override val syncProblemKind = FolderSync.ProblemKind.CONTENT
        }
        private val folderId: String = run {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val named = children(rootId).filter { it.name == rootName }
            if (named.size > 1 || named.any { it.mime != Document.MIME_TYPE_DIR }) throw InvalidListing()
            var selected = named.singleOrNull()?.id
                ?: if (!createIfMissing) throw FileNotFoundException("The original sync folder is missing")
                else DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, rootId), Document.MIME_TYPE_DIR, rootName)
                    ?.let { DocumentsContract.getDocumentId(it) } ?: throw IOException("Cannot create the sync folder")
            for (name in path) {
                FolderSync.requireChildName(name)
                val matches = children(selected).filter { it.name == name }
                if (matches.size > 1 || matches.any { it.mime != Document.MIME_TYPE_DIR }) throw InvalidListing()
                selected = matches.singleOrNull()?.id ?: run {
                    if (!createIfMissing) throw FileNotFoundException("The sync namespace is missing")
                    val created = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, selected), Document.MIME_TYPE_DIR, name)
                        ?: throw IOException("Cannot create the sync namespace")
                    val createdId = DocumentsContract.getDocumentId(created)
                    val confirmed = children(selected).filter { it.name == name }
                    if (confirmed.size != 1 || confirmed.single().id != createdId || confirmed.single().mime != Document.MIME_TYPE_DIR) throw InvalidListing()
                    createdId
                }
            }
            selected
        }
        private class Child(val id: String, val name: String, val mime: String, val size: Long, val modified: Long)
        private fun children(id: String): List<Child> {
            val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
            return resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), columns, null, null, null)?.use { c ->
                // Cloud providers may return only cached rows while loading. Never let that
                // snapshot create a second folder or drive deletion/conflict decisions.
                if (c.extras.getBoolean(DocumentsContract.EXTRA_LOADING) ||
                    !c.extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrBlank()) {
                    throw IOException("The sync folder is still loading or unavailable. Try syncing again later.")
                }
                val ids = HashSet<String>()
                buildList {
                    while (c.moveToNext()) {
                        val childId = c.getString(0)?.takeIf { it.isNotEmpty() } ?: throw InvalidListing()
                        val name = c.getString(1)?.takeIf { it.isNotEmpty() } ?: throw InvalidListing()
                        val mime = c.getString(2)?.takeIf { it.isNotEmpty() } ?: throw InvalidListing()
                        if (!ids.add(childId)) throw InvalidListing()
                        add(Child(childId, name, mime, if (c.isNull(3)) -1L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4)))
                    }
                }
            } ?: throw IOException("Cannot read the sync folder")
        }
        private fun files(): List<Child> {
            val children = children(folderId)
            if (children.map { it.name }.toSet().size != children.size) throw InvalidListing()
            return children.filter { it.mime != Document.MIME_TYPE_DIR }
        }
        private fun uri(name: String) = files().firstOrNull { it.name == name }?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.id) }
        override fun list() = files().associate { it.name to FolderSync.Stat(it.size, it.modified) }
        override fun read(name: String): InputStream = uri(name)?.let { resolver.openInputStream(it) } ?: throw FileNotFoundException(name)
        /**
         * P558: a notebook goes up under a working name and takes its real name only when complete, so an app killed part way
         * leaves the earlier copy whole (the reader on the PC saw 16 MB of a 47 MB notebook when a sync was cut off in place).
         * Null when this provider cannot do it; the caller then writes in place, and FolderSync repairs a copy cut short.
         */
        private fun writeWhole(name: String, from: File): FolderSync.Stat? {
            val partName = "$name$PART"
            val part = uri(partName) ?: try {
                DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, folderId), "application/octet-stream", partName)
            } catch (_: Exception) { null } ?: return null
            fun abandon(): FolderSync.Stat? { runCatching { DocumentsContract.deleteDocument(resolver, part) }; return null }
            val partId = DocumentsContract.getDocumentId(part)
            if (files().firstOrNull { it.id == partId }?.name != partName || !renames(part)) return abandon()
            (resolver.openOutputStream(part, "wt") ?: throw IOException("Cannot write $name")).use { out -> from.inputStream().use { it.copyTo(out) } }
            val placed = try {
                val old = uri(name)
                (old == null || DocumentsContract.deleteDocument(resolver, old)) && DocumentsContract.renameDocument(resolver, part, name) != null
            } catch (_: Exception) { false }
            if (!placed) return abandon()
            val written = files().firstOrNull { it.name == name } ?: throw IOException("$name was not saved under its name")
            return FolderSync.Stat(written.size, written.modified)
        }
        private fun renames(document: Uri) = resolver.query(document, arrayOf(Document.COLUMN_FLAGS), null, null, null)?.use {
            it.moveToFirst() && it.getInt(0) and Document.FLAG_SUPPORTS_RENAME != 0
        } ?: false
        override fun write(name: String, from: File): FolderSync.Stat {
            if (name.endsWith(".inknote")) writeWhole(name, from)?.let { return it }
            val target = uri(name) ?: DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, folderId), "application/octet-stream", name)
                ?: throw IOException("Cannot create $name")
            (resolver.openOutputStream(target, "wt") ?: throw IOException("Cannot write $name")).use { out -> from.inputStream().use { it.copyTo(out) } }
            val targetId = DocumentsContract.getDocumentId(target)
            val written = files().firstOrNull { it.id == targetId }
                ?: throw IOException("$name was not saved under its name")
            if (written.name != name) { runCatching { DocumentsContract.deleteDocument(resolver, target) }; throw IOException("The folder renamed $name") }
            return FolderSync.Stat(written.size, written.modified)
        }
        override fun delete(name: String) {
            uri("$name$PART")?.let { part -> runCatching { DocumentsContract.deleteDocument(resolver, part) } }
            val target = uri(name) ?: return
            if (!DocumentsContract.deleteDocument(resolver, target))
                throw IOException("The sync folder refused to remove the file")
            if (uri(name) != null)
                throw IOException("The sync folder has not confirmed the file was removed")
        }
    }

    private fun preferences(context: Context) = context.getSharedPreferences((context.applicationContext as? NotesApplication)?.preferencesName ?: "library", 0)
    private fun treeUri(context: Context): Uri? = preferences(context).getString(TREE_KEY, null)?.let(Uri::parse)
    fun webDavSelected(context: Context): Boolean = preferences(context).contains(WEBDAV_KEY)
    fun sourceIdentity(context: Context): String? = preferences(context).getString(WEBDAV_KEY, null)?.let { "webdav:$it" } ?: treeUri(context)?.toString()
    fun hasSource(context: Context) = sourceIdentity(context) != null
    fun folderUri(context: Context): Uri? = if (webDavSelected(context)) null else treeUri(context)
    fun needsRecovery(context: Context) = preferences(context).getBoolean(RECOVERY_KEY, false)
    internal fun setWebDav(app: NotesApplication, accountId: java.util.UUID) {
        preferences(app).edit().putString(WEBDAV_KEY, accountId.toString()).putString(LABEL_KEY, "WebDAV server")
            .putBoolean(PAUSED_KEY, false).remove(LAST_KEY).remove(RESULT_KEY).remove(PROBLEM_KEY).remove(WAITING_KEY).remove(RECOVERY_KEY).apply()
        BackgroundSync.update(app)
    }
    /** IO only: a released notebook uses its saved identity, even when current sync is stopped/changed. */
    internal fun originalSource(app: NotesApplication, identity: String): FolderSync.Folder =
        if (identity.startsWith("webdav:")) WebDavSource.forApp(app).let {
            if (it.identity != identity) throw dev.ownednotes.core.WebDavHttpClient.Failure(dev.ownednotes.core.WebDavHttpClient.Problem.ACCESS)
            it.open()
        } else TreeFolder(app, Uri.parse(identity), createIfMissing = false).let { folder ->
            if (changedPages(app, identity)) changedSource(app, folder, identity) else folder
        }
    internal fun sourceReadable(app: NotesApplication, identity: String): Boolean =
        if (identity.startsWith("webdav:")) WebDavSource.forApp(app).identity == identity else FolderAccess.hasPersistedRead(app, identity)
    fun folderLabel(context: Context): String = preferences(context).getString(LABEL_KEY, null) ?: "the chosen folder"
    fun paused(context: Context): Boolean = preferences(context).getBoolean(PAUSED_KEY, false)
    fun setPaused(context: Context, paused: Boolean) {
        preferences(context).edit().putBoolean(PAUSED_KEY, paused).apply()
        (context.applicationContext as? NotesApplication)?.let { BackgroundSync.update(it) }
    }
    fun wifiOnly(context: Context): Boolean = preferences(context).getBoolean(WIFI_KEY, false)
    fun setWifiOnly(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(WIFI_KEY, enabled).apply()
        (context.applicationContext as? NotesApplication)?.let { BackgroundSync.update(it) }
    }

    /** Check the default network, not merely a Wi-Fi radio that is switched on. Unknown fails closed. */
    internal fun onWifi(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val active = manager.activeNetwork ?: return@runCatching false
        manager.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)

    fun lastSync(context: Context): Long = preferences(context).getLong(LAST_KEY, 0L)
    fun lastResult(context: Context): String? = preferences(context).getString(RESULT_KEY, null)
    internal fun lastProblem(context: Context) = preferences(context).getBoolean(PROBLEM_KEY, false)
    internal fun waitingForNotebooks(context: Context) = preferences(context).getBoolean(WAITING_KEY, false)
    private fun stateFile(app: NotesApplication) = File(app.storageRoot, STATE_FILE)

    /** F180: the sync state in a few words, separate from "Saved locally" (which only says the tablet has it). */
    fun status(context: Context, now: Long = System.currentTimeMillis()): String = when {
        !hasSource(context) -> "Not syncing"
        paused(context) -> "Sync paused"
        wifiOnly(context) && !onWifi(context) -> "Waiting for Wi-Fi"
        preferences(context).getBoolean(PROBLEM_KEY, false) -> "Sync needs attention"
        preferences(context).getBoolean(WAITING_KEY, false) -> "Waiting for notebooks to close"
        lastSync(context) == 0L -> "Not synced yet"
        else -> "Synced " + android.text.format.DateUtils.getRelativeTimeSpanString(lastSync(context), now, android.text.format.DateUtils.MINUTE_IN_MILLIS)
    }

    /** Keeps access to [tree] across restarts and makes it the sync folder; a different folder starts afresh. */
    fun setFolder(app: NotesApplication, tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val resolver = app.contentResolver
        val old = treeUri(app)
        // Obtain the new grant first: a failed picker result must not revoke the existing folder.
        resolver.takePersistableUriPermission(tree, flags)
        val label = runCatching {
            resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        if (old != tree) app.io.execute { stateFile(app).delete() }
        preferences(app).edit().remove(WEBDAV_KEY).remove(RECOVERY_KEY).putString(TREE_KEY, tree.toString()).putString(LABEL_KEY, label).putBoolean(PAUSED_KEY, false).remove(LAST_KEY).remove(RESULT_KEY).remove(PROBLEM_KEY).remove(WAITING_KEY).apply()
        FolderAccess.releaseIfUnused(app, old?.takeIf { it != tree })
        BackgroundSync.update(app)
    }

    /** Stops syncing; the folder and both libraries keep everything they have. */
    fun stop(app: NotesApplication) {
        val old = treeUri(app)
        app.io.execute { stateFile(app).delete() }
        preferences(app).edit().remove(WEBDAV_KEY).remove(RECOVERY_KEY).remove(TREE_KEY).remove(LABEL_KEY).remove(PAUSED_KEY).remove(LAST_KEY).remove(RESULT_KEY).remove(PROBLEM_KEY).remove(WAITING_KEY).apply()
        FolderAccess.releaseIfUnused(app, old)
        BackgroundSync.update(app)
    }

    internal fun recovery(kind: FolderSync.ProblemKind): String = when (kind) {
        FolderSync.ProblemKind.ACCESS -> "Folder access was lost. Choose the folder again. If your cloud app asks you to sign in, do that in the cloud app first."
        FolderSync.ProblemKind.SPACE -> "Storage is full on this tablet or in the sync folder. Free some space, then choose Sync now."
        FolderSync.ProblemKind.CONTENT -> "A notebook could not be verified. Its existing local copy was kept. Check the file in your sync folder before retrying."
        FolderSync.ProblemKind.UNAVAILABLE -> "The sync folder could not be reached. Check your cloud app and connection, then choose Sync now or choose the folder again."
    }

    /**
     * Syncs now through [repository] (on the save thread, after any queued save) and reports a short sentence on the main
     * thread, or null when no folder is set. [folder] builds the folder there; tests pass a plain directory.
     */
    internal fun sync(app: NotesApplication, repository: NotebookRepository, folder: (() -> FolderSync.Folder)? = null, wifi: () -> Boolean = { onWifi(app) }, continueSync: () -> Boolean = { true }, cancellation: SyncCancellation? = null, source: WebDavSource? = null, smallUploads: Boolean = changedPages(app), done: (String?) -> Unit) {
        val originalIdentity = sourceIdentity(app)
        val originalTree = folderUri(app)
        var activeSource: WebDavSource? = null
        var recoveryNeeded = false
        fun recoveryText(kind: FolderSync.ProblemKind): String {
            if (activeSource == null) return recovery(kind)
            if (recoveryNeeded) return "Open Library sync and choose Inspect interrupted transfer before retrying. Existing notebook copies and recovery data were kept."
            return when (kind) {
                FolderSync.ProblemKind.ACCESS -> "Open WebDAV server settings and check the saved sign-in and server-folder permissions."
                FolderSync.ProblemKind.CONTENT -> "A server notebook could not be verified. Its existing local copy was kept; check the server copy before retrying."
                FolderSync.ProblemKind.SPACE -> recovery(kind)
                FolderSync.ProblemKind.UNAVAILABLE -> "The WebDAV server could not be reached. Check the connection, then choose Sync now."
            }
        }
        fun permitted() = cancellation?.isCancelled != true && continueSync() && (folder != null || sourceIdentity(app) == originalIdentity)
        if (!permitted()) return done(null)
        val open: () -> FolderSync.Folder = folder ?: if (webDavSelected(app)) ({
            val owner = source ?: WebDavSource.forApp(app)
            if (owner.identity != originalIdentity) throw IOException("Sync source changed")
            activeSource = owner
            owner.open(cancellation)
        }) else originalTree?.let { tree -> { TreeFolder(app, tree) } } ?: return done(null)
        fun checkNetwork() {
            if (!permitted()) throw IOException("Sync stopped")
            if (wifiOnly(app) && !wifi()) throw IOException(WIFI_WAITING)
        }
        if (wifiOnly(app) && !wifi()) {
            preferences(app).edit().putString(RESULT_KEY, WIFI_WAITING).putBoolean(PROBLEM_KEY, false).putBoolean(WAITING_KEY, false).apply()
            return done(WIFI_WAITING)
        }
        repository.syncLibrary({ busy, titles ->
            // Recheck after queued saves and before opening a provider. Also guard each new folder operation.
            checkNetwork()
            val raw = open()
            val remote = if (smallUploads) {
                require(raw is FolderSync.NestedFolder && activeSource == null) { "Smaller uploads require a dedicated folder source" }
                changedSource(app, raw, originalIdentity ?: "isolated-test-folder", cancelled = { checkNetwork(); false })
            } else raw
            val work = {
                val guarded = object : FolderSync.Folder {
                    override fun list(): Map<String, FolderSync.Stat> { checkNetwork(); return remote.list() }
                    override fun read(name: String): InputStream { checkNetwork(); return remote.read(name) }
                    override fun write(name: String, from: File): FolderSync.Stat { checkNetwork(); return remote.write(name, from) }
                    override fun delete(name: String) { checkNetwork(); remote.delete(name) }
                }
                val file = activeSource?.agreementFile ?: stateFile(app)
                val state = if (file.isFile) FolderSync.decode(file.readText()) else emptyMap()
                FolderSync.run(File(app.storageRoot, "notebooks"), guarded, state, busy, titles, cancelled = { !permitted() }, sourceKey = originalIdentity ?: "isolated-test-folder", attachmentDirectory = app.attachmentDirectory).also { outcome ->
                    checkNetwork()
                    recoveryNeeded = activeSource?.journal()?.pending() != null
                    val pending = File(file.parentFile, ".$STATE_FILE.pending")
                    pending.writeText(FolderSync.encode(outcome.state))
                    if (!pending.renameTo(file)) { file.delete(); if (!pending.renameTo(file)) throw IOException("Cannot save the sync state") }
                }
            }
            // Future network sources own sockets/snapshots. Close on success, failure and cancellation;
            // use also preserves the original exception if cleanup itself fails.
            val ownedWork = {
                // Bind only after open succeeds; an earlier stop immediately cancels the new source.
                val registration = (remote as? CancellableSyncSource)?.let { source -> cancellation?.register { source.cancel() } }
                try { checkNetwork(); work() } finally { registration?.close() }
            }
            if (remote is Closeable) remote.use { ownedWork() } else ownedWork()
        }) { result ->
            if (!permitted()) { done(null); return@syncLibrary }
            val failure = result.exceptionOrNull() as? dev.ownednotes.core.WebDavHttpClient.Failure
            if (failure?.problem in setOf(dev.ownednotes.core.WebDavHttpClient.Problem.RECOVERY_REQUIRED, dev.ownednotes.core.WebDavHttpClient.Problem.UNCERTAIN)) recoveryNeeded = true
            if (activeSource != null) preferences(app).edit().putBoolean(RECOVERY_KEY, recoveryNeeded).apply()
            val waiting = wifiOnly(app) && !wifi()
            val text = if (waiting) {
                // Keep the last successful timestamp. Verified per-file progress is still recorded for a retry.
                preferences(app).edit().putString(RESULT_KEY, WIFI_WAITING).putBoolean(PROBLEM_KEY, false).putBoolean(WAITING_KEY, false).apply()
                WIFI_WAITING
            } else result.fold({ outcome ->
                val complete = outcome.failed.isEmpty() && outcome.waiting.isEmpty() && !outcome.cancelled
                val details = outcome.problems.map { recoveryText(it.kind) }.distinct().joinToString("\n")
                val report = outcome.message + if (details.isEmpty()) "" else "\n$details"
                preferences(app).edit().putString(RESULT_KEY, report)
                    .putBoolean(PROBLEM_KEY, outcome.failed.isNotEmpty())
                    .putBoolean(WAITING_KEY, outcome.waiting.isNotEmpty())
                    .apply { if (complete) putLong(LAST_KEY, System.currentTimeMillis()) }.apply()
                report
            }, {
                val report = "Sync failed. ${recoveryText(FolderSync.problemKind(it))} Your notebooks are still saved on this tablet."
                preferences(app).edit().putString(RESULT_KEY, report).putBoolean(PROBLEM_KEY, true).putBoolean(WAITING_KEY, false).apply()
                report
            })
            done(text)
        }
    }
}
