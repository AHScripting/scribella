package dev.ownednotes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** Private PC mirror. Shared source is read-only; prior generations remain recoverable.
 * A file lease serializes workspace materialization. This does not select a nightly source or enable tablet sync.
 */
object PackageTransferMirror {
    private val json = Json { encodeDefaults = true }
    private val id = Regex("[a-zA-Z0-9-]{1,80}")
    private val hash = Regex("[a-f0-9]{64}")
    private val generationName = Regex("generation-[a-f0-9-]{36}")
    @Serializable private data class Owner(val version: Int = 1, val source: String)
    @Serializable private data class Pointer(val version: Int = 1, val source: String, val generation: String,
        val plan: String, val deletions: String, val packages: Map<String, String>, val origins: Map<String, String>, val conflictCopies: Int, val metadataSha256: String? = null)
    @Serializable data class Result(val directory: String, val notebooks: Int, val conflictCopies: Int, val reused: Boolean, val reusedNotebooks: Int, val metadataSha256: String, val deferred: Boolean = false)
    @Serializable private data class Origin(val notebookId: String, val sourceSha256: String, val outputSha256: String)
    @Serializable private data class Lineage(val version: Int = 1, val source: String, val deletionSha256: String, val notebooks: Map<String, Origin>)
    fun encodeResult(result: Result): String = json.encodeToString(Result.serializer(), result)
    private fun sha(file: File) = file.inputStream().use(FolderSync::sha256)
    private fun sha(text: String) = sha(text.toByteArray())
    private fun sha(bytes: ByteArray) = bytes.inputStream().use(FolderSync::sha256)
    private fun bytes(file: File, limit: Int): ByteArray {
        require(!Files.isSymbolicLink(file.toPath()) && file.length() <= limit) { "Invalid mirror metadata" }
        return file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(n > 0 && out.size().toLong() + n <= limit) { "Mirror metadata exceeds budget" }
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    }
    private fun atomic(file: File, value: ByteArray) {
        val temp = File.createTempFile("mirror-", ".pending", file.parentFile)
        try { temp.writeBytes(value); Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
        finally { temp.delete() }
    }
    private fun oldPointer(root: File, source: String): Pointer? {
        val file = File(root, "current.json")
        if (!file.exists()) return null
        return json.decodeFromString(Pointer.serializer(), bytes(file, 16 * 1024 * 1024).toString(Charsets.UTF_8)).also { p ->
            require(p.version == 1 && p.source == source && generationName.matches(p.generation) && hash.matches(p.plan) && hash.matches(p.deletions) && (p.metadataSha256 == null || hash.matches(p.metadataSha256))) { "Invalid mirror pointer" }
            require(p.packages.size <= 20_000 && p.conflictCopies in 0..p.packages.size && p.packages.all { (key, value) -> id.matches(key) && hash.matches(value) } && p.origins.keys == p.packages.keys && p.origins.values.all(hash::matches)) { "Invalid mirror packages" }
        }
    }
    private fun generation(root: File, pointer: Pointer): File = File(root, pointer.generation).also {
        require(Files.isDirectory(it.toPath(), NOFOLLOW_LINKS)) { "Mirror generation is missing or linked" }
    }
    private fun intact(dir: File, p: Pointer): Boolean {
        val metadata = File(dir, "lineage.json")
        if (p.metadataSha256 == null || !Files.isRegularFile(metadata.toPath(), NOFOLLOW_LINKS) || metadata.length() > 16 * 1024 * 1024 || sha(metadata) != p.metadataSha256) return false
        val names = dir.listFiles()?.filter { it.name.endsWith(".inknote") }?.map { it.name.removeSuffix(".inknote") }?.toSet()
        return names == p.packages.keys && p.packages.all { (key, value) ->
            val file = File(dir, "$key.inknote")
            Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && sha(file) == value
        }
    }
    fun run(sourceRoot: File, mirrorRoot: File, attachments: File): Result {
        require(Files.isDirectory(sourceRoot.toPath(), NOFOLLOW_LINKS)) { "Transfer source directory required" }
        require(!Files.isSymbolicLink(mirrorRoot.toPath())) { "Linked mirror refused" }
        val source = sourceRoot.canonicalFile; val mirror = mirrorRoot.canonicalFile
        require(!source.toPath().startsWith(mirror.toPath()) && !mirror.toPath().startsWith(source.toPath())) { "Mirror and source must be separate" }
        require(mirror.isDirectory || mirror.mkdirs()) { "Cannot create mirror workspace" }
        if (!File(mirror, "owner.json").exists()) require(mirror.list()!!.all { it == ".mirror.lock" }) { "Mirror workspace must be empty or already owned" }
        val path = File(mirror, ".mirror.lock").toPath()
        require(!Files.isSymbolicLink(path)) { "Linked mirror lock refused" }
        return java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { channel ->
            val lease = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
                ?: throw IOException("Mirror workspace is already in use")
            lease.use { materialize(sourceRoot, mirrorRoot, attachments) }
        }
    }
    private fun materialize(sourceRoot: File, mirrorRoot: File, attachments: File): Result {
        require(Files.isDirectory(sourceRoot.toPath(), NOFOLLOW_LINKS)) { "Transfer source directory required" }
        val source = sourceRoot.canonicalFile; val mirror = mirrorRoot.canonicalFile
        require(!source.toPath().startsWith(mirror.toPath()) && !mirror.toPath().startsWith(source.toPath())) { "Mirror and source must be separate" }
        require(!Files.isSymbolicLink(mirrorRoot.toPath())) { "Linked mirror refused" }
        require(mirror.isDirectory || mirror.mkdirs()) { "Cannot create mirror workspace" }
        val sourceKey = sha(source.path)
        val owner = File(mirror, "owner.json")
        if (!owner.exists()) {
            require(mirror.list()!!.all { it == ".mirror.lock" }) { "Mirror workspace must be empty or already owned" }
            atomic(owner, json.encodeToString(Owner.serializer(), Owner(source = sourceKey)).toByteArray())
        } else {
            val previousOwner = json.decodeFromString(Owner.serializer(), bytes(owner, 1024).toString(Charsets.UTF_8))
            require(previousOwner.version == 1 && previousOwner.source == sourceKey) { "Mirror belongs to another source" }
        }
        require(source.listFiles()?.none { it.name.endsWith(".inknote") } == true) { "Mixed complete-package and transfer sources require explicit migration" }
        val namespace = FolderSync.DirectoryFolder(source).child("transfer-v1")
        val commits = namespace.child("commits"); val blobs = namespace.child("blobs")
        val storeWorkspace = File(mirror, "store").also { require(it.isDirectory || it.mkdir()) }
        val snapshot = PackageTransferStore(commits, blobs, storeWorkspace, sourceKey).scan()
        if (snapshot.incomplete != 0) throw IOException("Transfer history is incomplete; previous mirror retained")
        val previous = oldPointer(mirror, sourceKey)
        val oldGeneration = previous?.let { generation(mirror, it) }
        val oldDeletionBytes = oldGeneration?.let { bytes(File(it, "deletions.json"), SyncDeletionIndex.MAX_BYTES) }
        require(previous == null || sha(oldDeletionBytes!!) == previous.deletions) { "Mirror deletion shadow checksum mismatch" }
        val priorDeletions = oldDeletionBytes?.let(SyncDeletionIndex::decode).orEmpty()
        val remoteDeletionFile = File(source, SyncDeletionIndex.FILE_NAME)
        if (!remoteDeletionFile.exists()) {
            if (previous == null || !intact(oldGeneration!!, previous)) throw IOException("Deletion metadata is unavailable; no new mirror can be published")
            return Result(oldGeneration.path, previous.packages.size, previous.conflictCopies, true, previous.packages.size, previous.metadataSha256!!, deferred = true)
        }
        val remoteDeletions = SyncDeletionIndex.decode(bytes(remoteDeletionFile, SyncDeletionIndex.MAX_BYTES))
        val deletions = SyncDeletionIndex.merge(priorDeletions, remoteDeletions)
        val deletionBytes = SyncDeletionIndex.encode(deletions)
        val deletionSha = sha(deletionBytes)
        val plan = sha(snapshot.heads.toSortedMap().entries.joinToString("\n") { (key, versions) -> "$key:${versions.map { it.hash }.sorted().joinToString(",")}" } + ":" + deletionSha)
        if (previous != null && previous.plan == plan && intact(oldGeneration!!, previous)) {
            return Result(oldGeneration.path, previous.packages.size, previous.conflictCopies, true, previous.packages.size, previous.metadataSha256!!)
        }
        val sizes = snapshot.heads.filterKeys { deletions[it]?.deleted != true }.values.flatMap { versions -> versions.distinctBy { it.commit.notebook.sha256 }.map { it.commit.notebook.size } }
        val expectedBytes = sizes.sum()
        require(expectedBytes <= 8L * 1024 * 1024 * 1024) { "Mirror generation exceeds eight GiB budget" }
        require(Files.getFileStore(mirror.toPath()).usableSpace > expectedBytes + (sizes.maxOrNull() ?: 0L) + 64L * 1024 * 1024) { "Insufficient space for a complete mirror generation" }
        val pending = Files.createTempDirectory(mirror.toPath(), "pending-").toFile()
        var published = false
        try {
            val packages = LinkedHashMap<String, String>(); val origins = LinkedHashMap<String, String>(); val lineage = LinkedHashMap<String, Origin>(); var conflicts = 0; var reused = 0
            snapshot.heads.toSortedMap().forEach { (notebookId, versions) ->
                if (deletions[notebookId]?.deleted == true) return@forEach
                val distinct = versions.distinctBy { it.commit.notebook.sha256 }.sortedBy { it.commit.notebook.sha256 }
                distinct.forEach versionLoop@ { version ->
                    val sourceHash = version.commit.notebook.sha256
                    val outputId = if (distinct.size == 1) notebookId else UUID.nameUUIDFromBytes("$notebookId:$sourceHash".toByteArray()).toString()
                    require(outputId !in packages) { "Mirror identity collision" }
                    val output = File(pending, "$outputId.inknote")
                    origins[outputId] = sourceHash
                    val local = oldGeneration?.let { File(it, "$outputId.inknote") }
                    if (previous?.origins?.get(outputId) == sourceHash && local != null && Files.isRegularFile(local.toPath(), NOFOLLOW_LINKS) && previous.packages[outputId] == sha(local)) {
                        Files.copy(local.toPath(), output.toPath())
                        packages[outputId] = previous.packages.getValue(outputId)
                        lineage[outputId] = Origin(notebookId, sourceHash, packages.getValue(outputId))
                        if (distinct.size > 1) conflicts++
                        reused++
                        return@versionLoop
                    }
                    val raw = File(pending, "raw.package")
                    PackageTransfer.hydrate(version.commit.notebook, raw, { part ->
                        val input = File(source, "transfer-v1/blobs/${part.sha256}")
                        require(Files.isRegularFile(input.toPath(), NOFOLLOW_LINKS)) { "Linked or missing transfer blob" }
                        input.inputStream()
                    })
                    val note = NotebookPackage.read(raw, attachments)
                    require(note.id == notebookId) { "Transfer notebook identity mismatch" }
                    if (distinct.size == 1) Files.move(raw.toPath(), output.toPath(), ATOMIC_MOVE)
                    else {
                        val copy = note.copy(id = outputId, title = "${note.title.take(460)} (conflict ${version.commit.notebook.sha256.take(8)})")
                        output.outputStream().use { NotebookPackage.write(copy, it) }
                        NotebookPackage.read(output, attachments)
                        require(raw.delete()); conflicts++
                    }
                    packages[outputId] = sha(output)
                    lineage[outputId] = Origin(notebookId, sourceHash, packages.getValue(outputId))
                }
            }
            File(pending, "deletions.json").writeBytes(deletionBytes)
            val metadata = json.encodeToString(Lineage.serializer(), Lineage(source = sourceKey, deletionSha256 = deletionSha, notebooks = lineage)).toByteArray()
            require(metadata.size <= 16 * 1024 * 1024) { "Mirror lineage exceeds budget" }
            File(pending, "lineage.json").writeBytes(metadata)
            val metadataSha = sha(metadata)
            val name = "generation-${UUID.randomUUID()}"
            val committed = File(mirror, name)
            Files.move(pending.toPath(), committed.toPath(), ATOMIC_MOVE)
            published = true // An unreferenced generation is recoverable if pointer publication fails.
            val pointer = Pointer(source = sourceKey, generation = name, plan = plan, deletions = deletionSha, packages = packages, origins = origins, conflictCopies = conflicts, metadataSha256 = metadataSha)
            atomic(File(mirror, "current.json"), json.encodeToString(Pointer.serializer(), pointer).toByteArray())
            return Result(committed.path, packages.size, conflicts, false, reused, metadataSha)
        } finally {
            if (!published) {
                // Only our direct files in the exact temporary directory; no recursive deletion.
                pending.listFiles().orEmpty().forEach { if (Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS)) it.delete() }
                pending.delete()
            }
        }
    }
}
