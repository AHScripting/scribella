package dev.ownednotes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID

/** F168 storage lifecycle. Call on the same serialized IO queue as notebook saves and folder sync.
 * No production callers until listing, sync exclusion, repository guards and Library UI are integrated.
 * This class never writes/deletes anything in the provider or moves a notebook to Trash.
 */
class OfflineNotebooks(private val directory: File,private val attachmentDirectory: File? = null) {
    @Serializable data class Record(
        val format: String = "notes-lab-offline", val version: Int = 1,
        val id: String, val title: String, val pageCount: Int, val modified: Long,
        val source: String, val sha256: String, val bytes: Long,
    )
    private val records get() = File(directory, ".offline")
    private fun packageFile(id: String): File {
        require(id.matches(ID)) { "Invalid notebook identity." }
        return File(directory,"$id.inknote")
    }
    private fun recordFile(id: String) = File(records,packageFile(id).nameWithoutExtension+".json")
    private fun validate(record: Record): Record = record.also {
        require(it.format == "notes-lab-offline" && it.version == 1 && it.id.matches(ID) &&
            it.title.trim().isNotEmpty() && it.title.length <= 500 && it.pageCount in 1..NotebookLimits.PAGES &&
            it.source.isNotBlank() && it.source.length <= 4096 && it.sha256.matches(SHA) && it.bytes in 1..MAX_BYTES) {
            "Invalid offline notebook record. Original files preserved."
        }
    }
    /** A local package wins over a stale transition record, including after a process crash. */
    fun record(id: String): Record? {
        if (packageFile(id).isFile) return null
        val file=recordFile(id)
        if (!file.exists()) return null
        require(file.isFile && file.length() in 1..65_536) { "Unreadable offline notebook record." }
        return validate(codec.decodeFromString<Record>(file.readText())).also {
            require(it.id==id) { "Offline notebook filename and identity differ." }
        }
    }
    /** Corrupt records are reported, never silently erased or interpreted as deletion. */
    fun released(): List<Record> {
        if(!records.exists()) return emptyList()
        val files=records.listFiles() ?: throw IOException("Cannot read offline notebook records.")
        return files.filter { it.isFile && it.extension=="json" }.mapNotNull { record(it.nameWithoutExtension) }.sortedBy { it.id }
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw InterruptedIOException("Offline notebook transfer cancelled. Original files preserved.")
    }
    private fun digest(input: InputStream, limit: Long, cancelled: () -> Boolean, output: FileOutputStream?=null): Pair<String,Long> = input.use {
        val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024);var total=0L
        while(true) {
            checkCancelled(cancelled)
            val count=it.read(buffer)
            if(count<0) break
            total+=count
            require(total<=limit) { "Notebook transfer exceeds the expected size." }
            hash.update(buffer,0,count);output?.write(buffer,0,count)
        }
        hash.digest().joinToString("") { byte -> "%02x".format(byte) } to total
    }
    private fun save(record: Record) {
        validate(record)
        if (!records.isDirectory && !records.mkdirs()) throw IOException("Cannot save offline notebook record.")
        val pending=File(records,".${record.id}-${UUID.randomUUID()}.pending")
        try {
            FileOutputStream(pending).use { out -> out.write(codec.encodeToString(record).toByteArray(Charsets.UTF_8));out.fd.sync() }
            Files.move(pending.toPath(),recordFile(record.id).toPath(),ATOMIC_MOVE,REPLACE_EXISTING)
        } finally { Files.deleteIfExists(pending.toPath()) }
    }

    /** Persist the recovery record BEFORE removing bytes; a failed check leaves the package untouched.
     * [busy] must include open notebooks and pending mutations; callers must serialize this with saves.
     * [source] is the originating folder reference, not the current global sync destination.
     */
    fun release(id: String, source: String, folder: FolderSync.Folder, busy: Set<String>, cancelled: () -> Boolean = { false }): Record =
        releaseWithAccessCheck(id, source, folder, busy, checkAccess = {}, cancelled = cancelled)

    /** Provider callers validate durable recovery access before reading and again immediately before deletion.
     * [checkAccess] throws when permission is missing/revoked; local bytes then remain authoritative.
     * Filesystem callers may use [release] when their recovery source needs no separate permission grant.
     */
    fun releaseWithAccessCheck(id: String, source: String, folder: FolderSync.Folder, busy: Set<String>,
        checkAccess: () -> Unit, cancelled: () -> Boolean = { false }): Record {
        require(id !in busy) { "Close the notebook and wait for saving before freeing its local storage." }
        require(source.isNotBlank() && source.length<=4096) { "A recoverable source folder is required." }
        checkAccess()
        val local=packageFile(id)
        require(local.isFile && local.length() in 1..MAX_BYTES) { "A saved local notebook is required." }
        checkCancelled(cancelled)
        val summary=NotebookPackage.summary(local)
        require(summary.id==id) { "Notebook filename and identity differ." }
        val (sha,size)=digest(local.inputStream(),MAX_BYTES,cancelled)
        val name=local.name
        val stat=folder.list()[name] ?: throw IOException("Sync this notebook before freeing local storage.")
        require(stat.size<0 || stat.size==size) { "The folder copy differs. Sync this notebook first." }
        val remote=digest(folder.read(name),size,cancelled)
        require(remote==(sha to size)) { "The folder copy differs. Sync this notebook first." }
        val record=Record(id=id,title=summary.title,pageCount=summary.pageCount,modified=local.lastModified(),source=source,sha256=sha,bytes=size)
        // Re-read rather than trusting size/time when an external writer replaces the package.
        require(digest(local.inputStream(),size,cancelled)==remote) { "The local notebook changed. Keep it offline and try again." }
        save(record)
        checkCancelled(cancelled)
        checkAccess()
        Files.delete(local.toPath())
        return record
    }

    /** Restore a stable, validated current provider version. An unavailable/changed/bad copy leaves its record.
     * Existing local bytes are NEVER overwritten; interruption after commit is recovered by local precedence.
     */
    fun restore(id: String, source: String, folder: FolderSync.Folder, busy: Set<String>, cancelled: () -> Boolean = { false }): Record? {
        require(id !in busy) { "Wait for the notebook to finish its current operation." }
        val local=packageFile(id)
        val record=record(id) ?: return null
        require(record.source==source) { "Choose this notebook's original folder to download it." }
        checkCancelled(cancelled)
        val stat=folder.list()[local.name] ?: throw IOException("The notebook is unavailable in its original folder. Its library record is kept.")
        require(stat.size<=MAX_BYTES) { "Notebook transfer exceeds supported size." }
        val limit=if(stat.size>=0) stat.size else MAX_BYTES
        val expected=digest(folder.read(local.name),limit,cancelled)
        val pending=File(directory,".$id-${UUID.randomUUID()}.download")
        try {
            val actual=FileOutputStream(pending).use { out -> digest(folder.read(local.name),limit,cancelled,out).also { out.fd.sync() } }
            require(actual==expected) { "The folder copy changed while downloading. Try again." }
            val restored=attachmentDirectory?.let { NotebookPackage.read(pending,it) } ?: NotebookPackage.read(pending)
            require(restored.id==id) { "Notebook filename and identity differ." }
            checkCancelled(cancelled)
            // All private-library writers MUST share the application's serialized IO queue.
            // Default move refuses an existing destination, unlike Unix ATOMIC_MOVE (which
            // can replace it). Both paths have the same parent: Android commits by rename,
            // with no cross-filesystem copy. Hard links are denied on the tested S7+.
            // This is not a no-replace syscall against an out-of-process/debugger writer:
            // such writers must not modify private storage during an application transaction.
            Files.move(pending.toPath(),local.toPath())
            Files.deleteIfExists(recordFile(id).toPath())
        } finally { Files.deleteIfExists(pending.toPath()) }
        return record
    }
    companion object {
        private val codec=Json { encodeDefaults=true }
        private val ID=Regex("[a-zA-Z0-9-]{1,80}")
        private val SHA=Regex("[a-f0-9]{64}")
        private const val MAX_BYTES=1024L*1024*1024
    }
}
