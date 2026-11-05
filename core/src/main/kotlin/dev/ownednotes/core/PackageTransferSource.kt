package dev.ownednotes.core

import java.io.*
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.*
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Provider-neutral native transfer session, also usable with Android SAF NestedFolder.
 * No FolderSync selection, deletion visibility or automatic conflict resolution is implied.
 * All heads are returned; callers must preserve concurrent content before acknowledging parents.
 * Private workspace is source-bound and exclusively leased until close. No cache GC.
 */
class PackageTransferSource private constructor(
    private val blobs: FolderSync.Folder,
    private val workspace: File,
    private val attachments: File,
    private val store: PackageTransferStore,
    private val channel: FileChannel,
    private val lease: FileLock,
    private val cancelled: () -> Boolean,
) : Closeable {
    private var closed = false
    private fun active() {
        check(!closed) { "Transfer source session is closed" }
        if (cancelled()) throw InterruptedIOException("Transfer cancelled")
    }
    fun scan(): PackageTransferStore.Snapshot { active(); return store.scan() }

    /** Read all content heads individually; never choose a timestamp winner. The caller owns
     * returned private cache paths and must not mutate them. Cached bytes are hashed each use.
     */
    fun download(version: PackageTransferStore.Version): File {
        active()
        require(store.scan().ready[version.hash] == version.commit) { "Unknown transfer version" }
        val manifest = version.commit.notebook
        val target = File(directory("native"), "${manifest.sha256}.inknote")
        require(!Files.isSymbolicLink(target.toPath())) { "Linked native cache refused" }
        if (target.isFile && target.length() == manifest.size && sha(target) == manifest.sha256) {
            require(NotebookPackage.read(target, attachments).id == version.commit.notebookId) { "Transfer notebook identity mismatch" }
            return target
        }
        val candidate = File.createTempFile("source-", ".pending", target.parentFile)
        try {
            PackageTransfer.hydrate(manifest, candidate, { blobs.read(it.sha256) }, cancelled)
            require(NotebookPackage.read(candidate, attachments).id == version.commit.notebookId) { "Transfer notebook identity mismatch" }
            active()
            Files.move(candidate.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            return target
        } finally { candidate.delete() }
    }

    /** Snapshot first, validate that exact native snapshot, then publish. A changing editor file
     * cannot cause validation of different bytes from those uploaded. Parent selection is explicit.
     */
    fun upload(notebookId: String, source: File, parents: List<String>): PackageTransferStore.Published {
        active()
        require(source.isFile && source.length() in 1..PackageTransfer.MAX_PACKAGE_BYTES && !Files.isSymbolicLink(source.toPath())) { "Native source required" }
        val candidate = File.createTempFile("upload-", ".pending", workspace)
        try {
            source.inputStream().use { input -> candidate.outputStream().use { output ->
                val buffer = ByteArray(65536); var total = 0L
                while (true) {
                    active(); val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) throw IOException("Native input made no progress")
                    total += n; require(total <= PackageTransfer.MAX_PACKAGE_BYTES) { "Native source exceeds budget" }
                    output.write(buffer, 0, n)
                }
            } }
            require(NotebookPackage.read(candidate, attachments).id == notebookId) { "Transfer notebook identity mismatch" }
            val cache = directory("chunks")
            val staged = PackageTransfer.stage(candidate, cache, cancelled)
            active()
            return store.publish(notebookId, staged, cache, parents)
        } finally { candidate.delete() }
    }
    /** Durable independent copies of every distinct content head. Original heads are never
     * acknowledged here. Repeating the operation reuses byte-identical published copies;
     * a deterministic ID already holding different content fails rather than overwriting it.
     * A failure may leave some safe copies published; retry completes the remaining copies.
     */
    fun preserveConflicts(notebookId: String): Map<String, PackageTransferStore.Version> {
        active()
        val before = store.scan()
        require(before.incomplete == 0) { "Transfer history is incomplete" }
        val heads = before.heads[notebookId].orEmpty().distinctBy { it.commit.notebook.sha256 }
        if (heads.size < 2) return emptyMap()
        // Validate every source first; no commit is published for malformed source content.
        val natives = heads.associateWith { download(it) }
        val result = linkedMapOf<String, PackageTransferStore.Version>()
        natives.forEach { (version, raw) ->
            active()
            val content = version.commit.notebook.sha256
            val copyId = java.util.UUID.nameUUIDFromBytes("$notebookId:$content".toByteArray(Charsets.UTF_8)).toString()
            val note = NotebookPackage.read(raw, attachments)
            val copy = note.copy(id = copyId, title = "${note.title.take(460)} (conflict ${content.take(8)})")
            val pending = File.createTempFile("conflict-", ".pending", workspace)
            try {
                pending.outputStream().use { NotebookPackage.write(copy, it) }
                val expected = sha(pending)
                val current = store.scan().heads[copyId].orEmpty()
                require(current.all { it.commit.notebook.sha256 == expected }) { "Conflict copy ID already has different content" }
                val published = current.firstOrNull() ?: upload(copyId, pending, emptyList()).version
                result[version.hash] = published
            } finally { pending.delete() }
        }
        return result
    }

    private fun directory(name: String): File = File(workspace, name).also {
        require(!Files.isSymbolicLink(it.toPath()) && (it.isDirectory || it.mkdir())) { "Owned transfer cache required" }
    }
    override fun close() {
        if (!closed) { closed = true; try { lease.release() } finally { channel.close() } }
    }
    companion object {
        private fun sha(file: File) = file.inputStream().use(FolderSync::sha256)
        /** initialize=true is only for explicitly approved fresh sources. Existing flat native
         * packages are never migrated. Missing deletion metadata on an existing source fails closed.
         * One source session at a time per workspace; remote devices may still publish concurrently.
         */
        fun open(root: FolderSync.NestedFolder, workspace: File, attachments: File, sourceKey: String,
            initialize: Boolean = false, cancelled: () -> Boolean = { false }): PackageTransferSource {
            require(sourceKey.isNotBlank() && !Files.isSymbolicLink(workspace.toPath()) && (workspace.isDirectory || workspace.mkdirs())) { "Owned source workspace required" }
            val owner = File(workspace, "source-owner")
            val fingerprint = sourceKey.byteInputStream().use(FolderSync::sha256)
            require(!Files.isSymbolicLink(owner.toPath())) { "Linked source owner refused" }
            require(owner.exists() || workspace.list()!!.all { it == ".source.lock" }) { "Source workspace must be empty or owned" }
            val path = File(workspace, ".source.lock").toPath()
            require(!Files.isSymbolicLink(path)) { "Linked source lock refused" }
            val channel = FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS)
            var lease: FileLock? = null
            try {
                lease = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
                    ?: throw IOException("Transfer source workspace is in use")
                if (owner.exists()) require(owner.length() == 64L && owner.readText() == fingerprint) { "Workspace belongs to another source" }
                else {
                    val pending = File.createTempFile("owner-", ".pending", workspace)
                    try { pending.writeText(fingerprint); Files.move(pending.toPath(), owner.toPath(), ATOMIC_MOVE) }
                    finally { pending.delete() }
                }
                if (cancelled()) throw InterruptedIOException("Transfer cancelled")
                val files = root.list()
                require(files.keys.none { it.endsWith(".inknote") || it.endsWith(".deleted") }) { "Mixed sync protocols require explicit migration" }
                val namespace = root.child("transfer-v1", initialize)
                val commits = namespace.child("commits", initialize)
                val blobs = namespace.child("blobs", initialize)
                val deletion = files[SyncDeletionIndex.FILE_NAME]
                if (deletion == null) {
                    require(initialize && files.isEmpty() && commits.list().isEmpty() && blobs.list().isEmpty()) { "Deletion metadata unavailable; source initialization refused" }
                    val pending = File.createTempFile("deletions-", ".pending", workspace)
                    try {
                        val bytes = SyncDeletionIndex.encode(emptyMap()); pending.writeBytes(bytes)
                        root.write(SyncDeletionIndex.FILE_NAME, pending)
                        require(root.read(SyncDeletionIndex.FILE_NAME).use { it.readBytesLimited(SyncDeletionIndex.MAX_BYTES) }.contentEquals(bytes)) { "Deletion metadata publication failed" }
                    } finally { pending.delete() }
                } else {
                    require(deletion.size in 1..SyncDeletionIndex.MAX_BYTES.toLong()) { "Invalid deletion metadata size" }
                    SyncDeletionIndex.decode(root.read(SyncDeletionIndex.FILE_NAME).use { it.readBytesLimited(SyncDeletionIndex.MAX_BYTES) })
                }
                val storeDirectory = File(workspace, "store")
                require(!Files.isSymbolicLink(storeDirectory.toPath()) && (storeDirectory.isDirectory || storeDirectory.mkdir()))
                val store = PackageTransferStore(commits, blobs, storeDirectory, sourceKey, cancelled)
                return PackageTransferSource(blobs, workspace, attachments, store, channel, lease, cancelled)
            } catch (error: Throwable) {
                try { lease?.release() } finally { channel.close() }
                throw error
            }
        }
        private fun InputStream.readBytesLimited(limit: Int): ByteArray {
            val out = ByteArrayOutputStream(); val buffer = ByteArray(65536)
            while (true) {
                val n = read(buffer)
                if (n < 0) break
                if (n == 0) throw IOException("Transfer metadata made no progress")
                require(out.size().toLong() + n <= limit) { "Transfer metadata exceeds budget" }
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }
}
