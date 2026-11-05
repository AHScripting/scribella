package dev.ownednotes.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import dev.ownednotes.core.NotebookReadGate
import dev.ownednotes.core.NotebookLibrary
import dev.ownednotes.core.NotebookPackage
import dev.ownednotes.core.OfflineNotebooks
import java.io.File
import java.io.FileOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * P088 (F166): copies every notebook package into a "Scribella backup" folder inside a folder the user picked with the
 * Android folder picker (so it can live in a OneDrive, Drive or SD-card folder). Only notebooks whose bytes changed
 * since the last backup to that folder are copied; each copy is read back and its SHA-256 checked. A renamed notebook
 * replaces its old file; a trashed notebook keeps its backup. Nothing here ever changes the library itself.
 */
object LibraryBackup {
    /** P566: as LibrarySync.folderName. */
    fun folderName(context: Context): String = context.getString(R.string.backup_folder)
    private const val TREE_KEY = "backup_tree"
    private const val LABEL_KEY = "backup_label"
    private const val LAST_KEY = "backup_last"
    private const val COUNT_KEY = "backup_count"
    private const val STATE_KEY = "backup_state"

    /** What was last copied for one notebook: checksum and file name in the backup folder, and the local file's size and time. */
    data class Entry(val sha: String, val name: String, val size: Long, val modified: Long)
    data class Result(val copied: Int, val unchanged: Int, val failed: List<String>, val state: Map<String, Entry>, val notDownloaded: Int = 0) {
        val total get() = copied + unchanged + failed.size
        val scopeNotice get() = when (notDownloaded) {
            0 -> ""
            1 -> " 1 notebook is not downloaded and was not included."
            else -> " $notDownloaded notebooks are not downloaded and were not included."
        }
    }

    /** The backup folder: names of its files, and replacing, reading and deleting one. */
    interface Folder {
        fun names(): Set<String>
        /** Creates or replaces [name] with [from]'s bytes; returns the name actually used. */
        fun write(name: String, from: File): String
        fun read(name: String): InputStream
        fun rename(from:String,to:String):String = throw IOException("This provider cannot publish a staged recovery copy")
        fun delete(name: String)
    }

    class DirectoryFolder(private val directory: File) : Folder {
        override fun names() = directory.apply { mkdirs() }.listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet()
        override fun write(name: String, from: File): String { directory.mkdirs(); from.copyTo(File(directory, name), overwrite = true); return name }
        override fun read(name: String): InputStream = File(directory, name).inputStream()
        override fun rename(from:String,to:String):String {
            java.nio.file.Files.move(File(directory,from).toPath(),File(directory,to).toPath());return to
        }
        override fun delete(name: String) { File(directory, name).delete() }
    }

    /** "Scribella backup" inside a picked document tree, created on first use. */
    class TreeFolder(context: Context, tree: Uri, folderName:String = LibraryBackup.folderName(context)) : Folder {
        private val resolver = context.contentResolver
        private val authority = tree.authority
        private val folderId: String = run {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
            children(tree, rootId).firstOrNull { it.second == folderName && it.third == Document.MIME_TYPE_DIR }?.first
                ?: DocumentsContract.createDocument(resolver, parent, Document.MIME_TYPE_DIR, folderName)
                    ?.let { DocumentsContract.getDocumentId(it) } ?: throw IOException("Cannot create the backup folder")
        }
        private val treeUri = tree
        private fun children(tree: Uri, id: String): List<Triple<String, String, String>> {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            return resolver.query(uri, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2) ?: "")) }
            } ?: throw IOException("Cannot read the backup folder")
        }
        private fun files() = children(treeUri, folderId).filter { it.third != Document.MIME_TYPE_DIR }
        private fun uri(name: String) = files().firstOrNull { it.second == name }?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it.first) }
        override fun names() = files().map { it.second }.toSet()
        override fun write(name: String, from: File): String {
            val target = uri(name) ?: DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, folderId), "application/octet-stream", name)
                ?: throw IOException("Cannot create $name")
            (resolver.openOutputStream(target, "wt") ?: throw IOException("Cannot write $name")).use { out -> from.inputStream().use { it.copyTo(out) } }
            return resolver.query(target, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: name
        }
        override fun read(name: String): InputStream = uri(name)?.let { resolver.openInputStream(it) } ?: throw FileNotFoundException(name)
        override fun rename(from:String,to:String):String {
            check(uri(to)==null){"Recovery target already exists"}
            val source=uri(from) ?: throw FileNotFoundException(from)
            val renamed=DocumentsContract.renameDocument(resolver,source,to) ?: throw IOException("Cannot publish staged recovery copy")
            return resolver.query(renamed,arrayOf(Document.COLUMN_DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst())it.getString(0)else null}
                ?: throw IOException("Cannot confirm published recovery name")
        }
        override fun delete(name: String) { uri(name)?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } } }
        override fun toString() = "$authority/$folderId"
    }

    /** A file name for the notebook that is readable in a file manager and unique through the id's start. */
    fun fileName(id: String, title: String?): String {
        val clean = title.orEmpty().map { if (it < ' ' || it in "\\/:*?\"<>|") '_' else it }.joinToString("").trim().trim('.').take(60).trim()
        return "${clean.ifEmpty { "Notebook" }} (${id.take(8)}).inknote"
    }

    fun sha256(stream: InputStream): String = stream.use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Backs up each `<id>.inknote` in [notebooks] (not the trash, not pending files) to [folder]. [previous] is what the
     * last backup to this folder copied; a notebook whose file size and time, name and presence are unchanged is skipped
     * without reading it. Call on the thread that saves notebooks, so a save never lands half-way through a copy.
     */
    fun run(notebooks: File, folder: Folder, titles: Map<String, String>, previous: Map<String, Entry>, nameFor:(String,String?)->String = ::fileName): Result {
        // Validate released metadata before touching the provider; local files win over stale records.
        val notDownloaded = OfflineNotebooks(notebooks).released().size
        val files = notebooks.listFiles().orEmpty().filter { it.isFile && it.extension == "inknote" && !it.name.startsWith(".") }.sortedBy { it.name }
        var present = folder.names()
        val state = previous.toMutableMap()
        var copied = 0; var unchanged = 0; val failed = mutableListOf<String>()
        files.forEach { file ->
            val id = file.nameWithoutExtension
            val name = nameFor(id, titles[id])
            val before = previous[id]
            if (before != null && before.size == file.length() && before.modified == file.lastModified() && before.name == name && before.name in present) {
                unchanged++; return@forEach
            }
            runCatching {
                // Pin under the local move gate; no disk copy or provider call holds it.
                val (input, size, modified) = NotebookReadGate.access(notebooks, id) {
                    Triple(file.inputStream(), file.length(), file.lastModified())
                }
                input.use { source ->
                    val snapshot = File.createTempFile(".backup-$id-", ".pending", notebooks)
                    try {
                        FileOutputStream(snapshot).use { out -> source.copyTo(out); out.fd.sync() }
                        check(snapshot.length() == size) { "Notebook changed while staging backup" }
                        val sha = sha256(snapshot.inputStream())
                        if (before != null && before.sha == sha && before.name == name && before.name in present) {
                            state[id] = before.copy(size = size, modified = modified); unchanged++
                        } else {
                            val used = folder.write(name, snapshot)
                            check(sha256(folder.read(used)) == sha) { "Checksum differs after copying" }
                            if (before != null && before.name != used && before.name in present) folder.delete(before.name)
                            state[id] = Entry(sha, used, size, modified)
                            present = present + used
                            copied++
                        }
                    } finally { snapshot.delete() }
                }
            }.onFailure { failed += titles[id] ?: id }
        }
        return Result(copied, unchanged, failed, state, notDownloaded)
    }

    /** P124 (F220): what a restore did; [restored] and [failed] name notebooks and files, [present] counts those already here. */
    data class Restore(val restored: List<String>, val present: Int, val failed: List<String>) {
        val message: String get() = buildList {
            add(when (restored.size) { 0 -> "No notebooks were missing"; 1 -> "Restored 1 notebook: ${restored[0]}"; else -> "Restored ${restored.size} notebooks: ${restored.joinToString()}" })
            if (present > 0) add("$present already in the library")
            if (failed.isNotEmpty()) add("${failed.size} could not be read: ${failed.joinToString()}")
        }.joinToString("; ") + "."
    }

    /**
     * P124 (F220): adds every readable notebook in [folder] whose identity is neither in [library] nor its trash, under
     * that same identity, so a later backup recognises it. Nothing already in the library is replaced or deleted, and the
     * backup folder is only read. Call on the thread that saves notebooks.
     */
    fun restore(folder: Folder, library: NotebookLibrary): Restore {
        val restored = mutableListOf<String>(); var present = 0; val failed = mutableListOf<String>()
        val released = library.entries().filter { !it.availableOffline }.mapTo(hashSetOf()) { it.id }
        folder.names().filter { it.endsWith(".inknote") && !it.startsWith(".") }.sorted().forEach { name ->
            runCatching {
                val notebook = folder.read(name).use { library.readPackage(it) }
                if (notebook.id in released || library.contains(notebook.id) || library.inTrash(notebook.id)) present++
                else { library.write(notebook); restored += notebook.title }
            }.onFailure { failed += name }
        }
        return Restore(restored, present, failed)
    }

    // ---- App side: the chosen folder and the last result live in the library preferences. ----

    private fun preferences(context: Context) = context.getSharedPreferences((context.applicationContext as? NotesApplication)?.preferencesName ?: "library", 0)
    fun folderUri(context: Context): Uri? = preferences(context).getString(TREE_KEY, null)?.let(Uri::parse)
    fun folderLabel(context: Context): String = preferences(context).getString(LABEL_KEY, null) ?: "the chosen folder"
    fun lastBackup(context: Context): Long = preferences(context).getLong(LAST_KEY, 0L)
    fun lastCount(context: Context): Int = preferences(context).getInt(COUNT_KEY, 0)

    /** Obtain new access first; release previous access only when no owner still needs it. */
    fun setFolder(context: Context, tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val resolver = context.contentResolver
        val old = folderUri(context)
        resolver.takePersistableUriPermission(tree, flags)
        val label = runCatching {
            resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        preferences(context).edit().putString(TREE_KEY, tree.toString()).putString(LABEL_KEY, label).remove(STATE_KEY).remove(LAST_KEY).remove(COUNT_KEY).apply()
        FolderAccess.releaseIfUnused(context, old?.takeIf { it != tree })
    }

    fun stop(context: Context) {
        val old = folderUri(context)
        preferences(context).edit().remove(TREE_KEY).remove(LABEL_KEY).remove(STATE_KEY).remove(LAST_KEY).remove(COUNT_KEY).apply()
        FolderAccess.releaseIfUnused(context, old)
    }

    private fun readState(context: Context): Map<String, Entry> = preferences(context).getStringSet(STATE_KEY, null).orEmpty().mapNotNull { line ->
        line.split('\n').takeIf { it.size == 5 }?.let { p -> runCatching { p[0] to Entry(p[1], p[2], p[3].toLong(), p[4].toLong()) }.getOrNull() }
    }.toMap()

    /**
     * Backs up the library on [NotesApplication.io] (after any queued save) and reports on the main thread with a short
     * sentence, or null when no folder is set. [titles] come from the library list the caller already holds.
     */
    fun backUp(app: NotesApplication, titles: Map<String, String>, done: (String?) -> Unit) {
        val tree = folderUri(app) ?: return done(null)
        val previous = readState(app)
        app.io.execute {
            val outcome = runCatching { app.afterQueuedSaves(); run(File(app.storageRoot, "notebooks"), TreeFolder(app, tree), titles, previous) }
            val text = outcome.fold({ result ->
                // Trashed notebooks stay in the state, so their backups are never re-copied or removed.
                preferences(app).edit()
                    .putStringSet(STATE_KEY, result.state.map { (id, e) -> listOf(id, e.sha, e.name, e.size, e.modified).joinToString("\n") }.toSet())
                    .apply { if (result.failed.isEmpty()) putLong(LAST_KEY, System.currentTimeMillis()).putInt(COUNT_KEY, result.total) }.apply()
                when {
                    result.failed.isNotEmpty() -> "Backup could not copy ${result.failed.joinToString(", ")}. The others are backed up."
                    result.copied == 0 -> "Backup is up to date (${result.total} notebooks)."
                    else -> "Backed up ${result.copied} of ${result.total} notebooks to ${folderLabel(app)}."
                } + result.scopeNotice
            }, { "Backup failed: ${it.message ?: "the folder cannot be reached"}. Choose the folder again from Library → Backup." })
            app.main.post { done(text) }
        }
    }
}
