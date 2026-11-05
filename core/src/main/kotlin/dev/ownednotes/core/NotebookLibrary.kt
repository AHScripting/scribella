package dev.ownednotes.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Locale
import java.util.UUID

/** [modified] is the package file's last-modified time in epoch milliseconds (0 when unknown). */
data class LibraryEntry(val id: String, val title: String, val pageCount: Int, val error: String? = null, val modified: Long = 0L, val availableOffline: Boolean = true, val offlineSource: String? = null) {
    /** The entry without its time, for telling whether a library view needs new tiles. */
    val identity: LibraryEntry get() = if (modified == 0L) this else copy(modified = 0L)
}

/** Local packages are authoritative; explicit released-notebook records retain remote identities. */
class NotebookLibrary(private val directory: File,private val attachmentDirectory: () -> File? = { null }, private val onWritten: (File) -> Unit = {}) {
    constructor(directory: File,ownedDirectory: File?): this(directory,{ ownedDirectory })
    /** Native import/restore validation shares this library's explicitly owned attachment storage. */
    fun readPackage(file: File): Notebook = attachmentDirectory()?.let { NotebookPackage.read(file,it) } ?: NotebookPackage.read(file)
    fun readPackage(input: java.io.InputStream): Notebook = attachmentDirectory()?.let { NotebookPackage.read(input,it) } ?: NotebookPackage.read(input)
    private fun readPackageCover(file: File): Notebook = attachmentDirectory()?.let { NotebookPackage.firstPage(file,it) } ?: NotebookPackage.firstPage(file)
    private fun path(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Invalid notebook identity" }
        return File(directory, "$id.inknote")
    }
    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create notebook directory")
    }
    fun contains(id: String): Boolean = path(id).isFile
    /** Physical native-package sizes, without opening/parsing notebook content or changing any file. */
    data class DiskUsage(val currentBytes: Long, val earlierVersionFiles: Int, val earlierVersionBytes: Long)

    fun diskUsage(id: String): DiskUsage {
        val current = path(id) // Validate identity before any filesystem access.
        fun attributes(file: File): java.nio.file.attribute.BasicFileAttributes? = try {
            Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        } catch (_: java.nio.file.NoSuchFileException) { null }
        fun directoryPresent(file: File): Boolean {
            val info = attributes(file) ?: return false
            if (!info.isDirectory) throw IOException("Notebook storage directory is not a plain directory")
            return true
        }
        fun bytes(file: File): Long {
            val info = attributes(file) ?: return 0L
            if (!info.isRegularFile) throw IOException("Notebook storage entry is not a plain file")
            return info.size()
        }
        if (!directoryPresent(directory)) return DiskUsage(0, 0, 0)
        val currentBytes = bytes(current)
        val versionsRoot = File(directory, VERSIONS)
        val versions = File(versionsRoot, id)
        var count = 0; var keptBytes = 0L
        if (directoryPresent(versionsRoot) && directoryPresent(versions)) {
            Files.newDirectoryStream(versions.toPath()).use { files ->
                files.filter { it.fileName.toString().endsWith(".inknote") }.forEach { file ->
                    // Include unreadable/corrupt/unnamed retained packages: their physical bytes still remain.
                    keptBytes = Math.addExact(keptBytes, bytes(file.toFile())); count = Math.addExact(count, 1)
                }
            }
        }
        return DiskUsage(currentBytes, count, keptBytes)
    }
    fun read(id: String): Notebook = readPackage(path(id)).also { require(it.id == id) { "Notebook filename and identity differ" } }
    /** Fully parsed/validated, ready to publish as a session without another full point walk. */
    fun readSession(id: String): DocumentSession = DocumentSession.read(path(id),attachmentDirectory()).also {
        require(it.notebook.id == id) { "Notebook filename and identity differ" }
    }
    /** The notebook with only its first page, for its cover; the rest is not read. */
    fun readCover(id: String): Notebook = readPackageCover(path(id)).also { require(it.id == id) { "Notebook filename and identity differ" } }
    /** A list entry from the package's manifest alone, so a long library lists quickly; pages are checked when opened. */
    private fun entry(file: File, expectedId: String = file.nameWithoutExtension): LibraryEntry = runCatching { NotebookPackage.summary(file).also { require(it.id == expectedId) { "Notebook filename and identity differ" } } }.fold(
        { LibraryEntry(file.nameWithoutExtension, it.title, it.pageCount, modified = file.lastModified()) },
        { LibraryEntry(file.nameWithoutExtension, file.nameWithoutExtension, 0, it.message ?: "Unreadable notebook", file.lastModified()) },
    )
    fun write(notebook: Notebook) {
        // NotebookPackage.write validates before anything is written; a refused notebook leaves only the pending file, removed below.
        ensureDirectory()
        val destination = path(notebook.id)
        val pending = File(directory, ".${notebook.id}-${UUID.randomUUID()}.pending")
        try {
            FileOutputStream(pending).use { stream -> NotebookPackage.write(notebook, stream); stream.fd.sync() }
            // Fail rather than silently falling back to a non-atomic replacement.
            Files.move(pending.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            // A derived-copy failure must never turn a successful native save into a failed save.
            runCatching { onWritten(destination) }
        } finally { Files.deleteIfExists(pending.toPath()) }
    }
    fun entries(): List<LibraryEntry> {
        ensureDirectory()
        val files = directory.listFiles() ?: throw IOException("Cannot read notebook directory")
        val local = files.filter { it.isFile && it.extension == "inknote" }.map { file ->
            if (file.nameWithoutExtension.matches(Regex("[a-zA-Z0-9-]{1,80}"))) entry(file)
            else LibraryEntry(file.nameWithoutExtension, file.nameWithoutExtension, 0, "Invalid notebook identity", file.lastModified())
        }
        val released = OfflineNotebooks(directory).released().map {
            LibraryEntry(it.id,it.title,it.pageCount,modified=it.modified,availableOffline=false,offlineSource=it.source)
        }
        return (local+released).sortedWith(compareBy({ it.title.lowercase(Locale.ROOT) }, { it.id }))
    }
    // ---- Trash (P024): a recoverable holding area. Packages move unchanged; nothing here deletes a notebook. ----

    private val trashDirectory get() = File(directory, "trash")
    private fun trashPath(id: String) = File(trashDirectory, path(id).name)
    private fun originPath(id: String) = File(File(trashDirectory,".identities"),path(id).nameWithoutExtension)
    private fun originalId(id: String): String = originPath(id).takeIf { it.isFile }?.let {
        require(it.length() in 1..80) { "Invalid trash identity record" };it.readText().also { original -> path(original) }
    } ?: id
    private fun atomicText(target: File, text: String) {
        if(!target.parentFile.isDirectory && !target.parentFile.mkdirs()) throw IOException("Cannot save deletion tracking")
        val pending=File(target.parentFile,".${target.name}-${UUID.randomUUID()}.pending")
        try {
            FileOutputStream(pending).use { out -> out.write(text.toByteArray(Charsets.UTF_8));out.fd.sync() }
            Files.move(pending.toPath(),target.toPath(),ATOMIC_MOVE,REPLACE_EXISTING)
        } finally { Files.deleteIfExists(pending.toPath()) }
    }
    /** Deletion intent must outlive the recoverable Trash package, until sync handles it. */
    private fun rememberDeletion(id:String) = atomicText(File(File(directory,DELETIONS),path(id).nameWithoutExtension+".deleted"),"Deleted locally\n")
    fun inTrash(id: String): Boolean = trashPath(id).isFile
    /** Reads a trashed notebook (for its cover) without restoring it. */
    fun readTrashed(id: String): Notebook = readPackage(trashPath(id)).also { require(it.id == originalId(id)) { "Notebook filename and identity differ" } }
    fun readTrashedCover(id: String): Notebook = readPackageCover(trashPath(id)).also { require(it.id == originalId(id)) { "Notebook filename and identity differ" } }

    /** Moves packages unchanged. An older same-identity Trash copy gets an independent storage key. */
    fun trash(id: String) {
        val source = path(id)
        if (!source.isFile) throw IOException("Notebook not found")
        if (!trashDirectory.isDirectory && !trashDirectory.mkdirs()) throw IOException("Cannot create trash directory")
        val target = trashPath(id)
        rememberDeletion(id)
        if (target.exists()) {
            val key=UUID.randomUUID().toString()
            atomicText(originPath(key),originalId(id))
            Files.move(target.toPath(),trashPath(key).toPath(),ATOMIC_MOVE)
            // The canonical key now represents the new package, not an alias.
            Files.deleteIfExists(originPath(id).toPath())
        }
        Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE)
    }

    /** Moves a trashed package back. Refused if an active notebook already has the same identity. */
    fun restore(id: String) {
        val source = trashPath(id)
        if (!source.isFile) throw IOException("Notebook is not in the trash")
        ensureDirectory()
        val original=originalId(id)
        val target = path(original)
        if (target.exists()) throw IOException("A notebook with this identity already exists")
        Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE)
        Files.deleteIfExists(originPath(id).toPath())
        Files.deleteIfExists(File(File(directory,DELETIONS),"$original.deleted").toPath())
    }

    /** Trashed notebooks, in the same order and with the same error reporting as [entries]. */
    fun trashEntries(): List<LibraryEntry> {
        val files = trashDirectory.takeIf { it.isDirectory }?.listFiles() ?: return emptyList()
        return files.filter { it.isFile && it.extension == "inknote" && it.nameWithoutExtension.matches(Regex("[a-zA-Z0-9-]{1,80}")) }.map { entry(it,originalId(it.nameWithoutExtension)) }.sortedWith(compareBy({ it.title.lowercase(Locale.ROOT) }, { it.id }))
    }

    /** Exact bytes the user is about to confirm. Call off the UI thread on the save queue. */
    fun trashSnapshot(): Map<String,String> = trashEntries().associate { it.id to FolderSync.sha256(trashPath(it.id).inputStream()) }
    data class Purge(val deleted:Int,val kept:Int)
    /** Permanently removes ONLY confirmed, unchanged Trash files. New/changed entries are retained. */
    fun emptyTrash(confirmed:Map<String,String>):Purge {
        confirmed.forEach { (id,sha) -> trashPath(id);require(sha.matches(Regex("[a-f0-9]{64}"))) { "Invalid trash confirmation" } }
        var deleted=0;var kept=0
        confirmed.forEach { (id,sha) ->
            val source=trashPath(id)
            if(!source.isFile || FolderSync.sha256(source.inputStream())!=sha) kept++
            else {
                rememberDeletion(originalId(id))
                Files.delete(source.toPath());deleted++
                Files.deleteIfExists(originPath(id).toPath())
            }
        }
        return Purge(deleted,kept)
    }

    // ---- Earlier versions (F178): packages kept in versions/<id>/<epoch ms>.inknote before sync replaced them (P139). ----

    /** [error] says the kept package cannot be read; it is still listed, never deleted here. */
    data class Version(val time: Long, val title: String, val pageCount: Int, val error: String? = null)

    private fun versionFile(id: String, time: Long) = File(File(File(directory, VERSIONS), path(id).nameWithoutExtension), "$time.inknote")

    /** The kept versions of [id], newest first. */
    fun versions(id: String): List<Version> {
        val folder = versionFile(id, 0L).parentFile
        return folder.listFiles().orEmpty().filter { it.isFile && it.extension == "inknote" }.mapNotNull { file ->
            val time = file.nameWithoutExtension.toLongOrNull() ?: return@mapNotNull null
            runCatching { NotebookPackage.summary(file).also { require(it.id == id) { "Notebook filename and identity differ" } } }
                .fold({ Version(time, it.title, it.pageCount) }, { Version(time, id, 0, it.message ?: "Unreadable notebook") })
        }.sortedByDescending { it.time }
    }

    /** Writes the version kept at [time] as a new notebook titled [title]; the notebook and its versions stay as they are. */
    fun restoreVersion(id: String, time: Long, title: String): Notebook {
        require(title.trim().length in 1..500) { "Title must have 1–500 characters" }
        val file = versionFile(id, time)
        if (!file.isFile) throw IOException("That version is no longer kept")
        val kept = readPackage(file).also { require(it.id == id) { "Notebook filename and identity differ" } }
        return importCopy(kept.copy(title = title.trim()))
    }

    /** Retain the legacy source, but import its identity only once; missing local bytes can be an intentional deletion/release. */
    fun migrateLegacy(notebook: Notebook) {
        val id = path(notebook.id).nameWithoutExtension
        val receipts = File(directory, ".legacy-migrations")
        val receipt = File(receipts, id)
        val noFollow = java.nio.file.LinkOption.NOFOLLOW_LINKS
        if (Files.exists(receipts.toPath(), noFollow) && !Files.isDirectory(receipts.toPath(), noFollow))
            throw IOException("Legacy migration records are unavailable")
        if (Files.exists(receipt.toPath(), noFollow)) {
            if (!Files.isRegularFile(receipt.toPath(), noFollow) || receipt.length() > 64 || receipt.readText() != "Legacy migration complete\n")
                throw IOException("Legacy migration record is unavailable")
            return
        }
        val owned = contains(id) || inTrash(id) || File(File(directory, DELETIONS), "$id.deleted").exists() ||
            OfflineNotebooks(directory).record(id) != null
        if (!owned) write(notebook)
        // Commit after importing (or finding prior ownership), so a failed first import remains retryable.
        atomicText(receipt, "Legacy migration complete\n")
    }
    /** Retitles a stored notebook atomically; returns the renamed notebook. */
    fun rename(id: String, title: String): Notebook {
        require(title.trim().length in 1..500) { "Title must have 1–500 characters" }
        return read(id).copy(title = title.trim()).also { write(it) }
    }
    /** Writes a copy of a stored notebook under a fresh identity, titled "<title> copy" (within the title limit). */
    fun duplicate(id: String): Notebook {
        val source = read(id)
        return importCopy(source.copy(title = "${source.title.take(495)} copy"))
    }
    fun importCopy(notebook: Notebook): Notebook = notebook.copy(id = UUID.randomUUID().toString()).also { it.validate(); write(it) }

    companion object {
        /** The folder beside the notebooks that holds kept versions, one subfolder per notebook. */
        const val VERSIONS = "versions"
        const val DELETIONS = ".deleted"
    }
}
