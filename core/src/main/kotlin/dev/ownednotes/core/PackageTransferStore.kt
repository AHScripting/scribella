package dev.ownednotes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

/** Immutable transfer history over two dedicated provider subfolders. Does not enable FolderSync.
 * One owner per workspace/instance. No deletion or garbage collection is performed.
 */
class PackageTransferStore(
    private val commits: FolderSync.Folder,
    private val blobs: FolderSync.Folder,
    private val workspace: File,
    sourceKey: String,
    private val cancelled: () -> Boolean = { false },
) {
    @Serializable data class Commit(val version: Int = 1, val notebookId: String,
        val parents: List<String>, val notebook: PackageTransfer.Manifest)
    data class Version(val hash: String, val commit: Commit)
    data class Snapshot(val heads: Map<String, List<Version>>, val ready: Map<String, Commit>, val incomplete: Int)
    data class Published(val version: Version, val uploadedBytes: Long, val reusedBytes: Long)
    @Serializable private data class Receipt(val size: Long, val modified: Long)
    @Serializable private data class Receipts(val version: Int = 1, val blobs: Map<String, Receipt>)
    private val json = Json { encodeDefaults = true }
    private val shaPattern = Regex("[a-f0-9]{64}")
    private val idPattern = Regex("[a-zA-Z0-9-]{1,80}")
    private val maxCommit = PackageTransfer.MAX_MANIFEST_BYTES + 16 * 1024
    private val maxRecords = 20_000
    private val maxScanBytes = 64L * 1024 * 1024
    private val receiptFile: File
    private val receipts: MutableMap<String, Receipt>
    init {
        require(sourceKey.isNotBlank() && workspace.isDirectory && !Files.isSymbolicLink(workspace.toPath())) { "Owned transfer workspace and source identity required" }
        receiptFile = File(workspace, "receipts-${sha(sourceKey.toByteArray())}.json")
        receipts = loadReceipts()
    }
    private fun checkCancelled() { if (cancelled()) throw InterruptedIOException("Transfer cancelled") }
    private fun sha(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun bounded(input: InputStream, limit: Int, observed: (Int) -> Unit = {}): ByteArray = input.use {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(65536)
        while (true) {
            checkCancelled(); val n = it.read(buffer)
            if (n < 0) break
            if (n == 0) throw IOException("Transfer input made no progress")
            observed(n)
            require(out.size().toLong() + n <= limit) { "Transfer metadata exceeds budget" }
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    }
    private fun loadReceipts(): MutableMap<String, Receipt> {
        if (!receiptFile.exists()) return mutableMapOf()
        return try {
            require(!Files.isSymbolicLink(receiptFile.toPath()))
            val state = json.decodeFromString(Receipts.serializer(), bounded(receiptFile.inputStream(), maxCommit).toString(Charsets.UTF_8))
            require(state.version == 1 && state.blobs.size <= maxRecords)
            require(state.blobs.all { (hash, r) -> shaPattern.matches(hash) && r.size in 1..PackageTransfer.MAX_PACKAGE_BYTES && r.modified >= 0 })
            state.blobs.toMutableMap()
        } catch (e: InterruptedIOException) { throw e }
        catch (_: IllegalArgumentException) { mutableMapOf() }
        catch (_: IOException) { mutableMapOf() }
    }
    private fun saveReceipts() {
        // Dropping receipts only causes reverification; it never changes provider content.
        while (receipts.size > maxRecords) receipts.remove(receipts.keys.first())
        val temp = File.createTempFile("receipts-", ".pending", workspace)
        try {
            temp.writeText(json.encodeToString(Receipts.serializer(), Receipts(blobs = receipts.toSortedMap())))
            Files.move(temp.toPath(), receiptFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { temp.delete() }
    }
    private fun encode(commit: Commit): ByteArray {
        require(commit.version == 1 && idPattern.matches(commit.notebookId)) { "Invalid transfer commit" }
        require(commit.parents.size <= 16 && commit.parents.distinct().size == commit.parents.size && commit.parents.all(shaPattern::matches)) { "Invalid transfer parents" }
        PackageTransfer.encode(commit.notebook)
        return json.encodeToString(Commit.serializer(), commit).toByteArray(Charsets.UTF_8).also { require(it.size <= maxCommit) }
    }
    private fun decode(bytes: ByteArray, expected: String): Commit {
        require(bytes.size <= maxCommit && sha(bytes) == expected) { "Incomplete transfer commit" }
        return json.decodeFromString(Commit.serializer(), bytes.toString(Charsets.UTF_8)).also {
            encode(it); require(expected !in it.parents) { "Cyclic transfer commit" }
        }
    }

    /** Incomplete uploads and parent chains remain observable, but cannot hide valid older heads.
     * Concurrent complete branches are all returned; no timestamp winner is selected.
     */
    fun scan(): Snapshot {
        val listing = commits.list().filterKeys { it.matches(Regex("[a-f0-9]{64}\\.commit")) }
        require(listing.size <= maxRecords) { "Transfer history exceeds scan budget" }
        require(listing.values.sumOf { it.size.coerceAtLeast(0).coerceAtMost(maxCommit.toLong()) } <= maxScanBytes) { "Transfer history exceeds byte scan budget" }
        var scannedBytes = 0L
        val decoded = LinkedHashMap<String, Commit>()
        var incomplete = 0
        for ((name, stat) in listing) {
            checkCancelled()
            if (stat.size > maxCommit || stat.size == 0L) { incomplete++; continue }
            try {
                val hash = name.removeSuffix(".commit")
                decoded[hash] = decode(bounded(commits.read(name), maxCommit) { n ->
                    scannedBytes += n
                    if (scannedBytes > maxScanBytes) throw IOException("Transfer history exceeds byte scan budget")
                }, hash)
            } catch (e: InterruptedIOException) { throw e }
            catch (_: IllegalArgumentException) { incomplete++ }
            // Provider I/O failure is not evidence of an incomplete commit: preserve and retry.
        }
        val ready = LinkedHashMap<String, Commit>()
        val pending = HashMap<String, Int>()
        val children = HashMap<String, MutableList<String>>()
        val queue = java.util.ArrayDeque<String>()
        decoded.forEach { (hash, commit) ->
            if (commit.parents.any { decoded[it]?.notebookId != commit.notebookId }) return@forEach
            pending[hash] = commit.parents.size
            if (commit.parents.isEmpty()) queue.add(hash)
            commit.parents.forEach { children.getOrPut(it) { ArrayList() }.add(hash) }
        }
        while (queue.isNotEmpty()) {
            checkCancelled()
            val hash = queue.removeFirst(); ready[hash] = decoded.getValue(hash)
            children[hash].orEmpty().forEach { child ->
                val left = pending.getValue(child) - 1; pending[child] = left
                if (left == 0) queue.add(child)
            }
        }
        incomplete += decoded.size - ready.size
        val superseded = ready.values.flatMap { it.parents }.toHashSet()
        val heads = ready.filterKeys { it !in superseded }.map { Version(it.key, it.value) }.groupBy { it.commit.notebookId }
        return Snapshot(heads, ready, incomplete)
    }
    private fun verify(input: InputStream, part: PackageTransfer.Part): Boolean = input.use {
        val digest = MessageDigest.getInstance("SHA-256"); var count = 0L; val buffer = ByteArray(65536)
        while (true) {
            checkCancelled(); val n = it.read(buffer)
            if (n < 0) break
            if (n == 0) throw IOException("Transfer input made no progress")
            count += n
            if (count > part.size) return false
            digest.update(buffer, 0, n)
        }
        count == part.size && hex(digest.digest()) == part.sha256
    }
    private fun verifiedRemote(part: PackageTransfer.Part, before: FolderSync.Stat): Boolean {
        if (before.size != part.size || !verify(blobs.read(part.sha256), part)) return false
        val after = blobs.list()[part.sha256]
        if (before != after) throw IOException("Transfer blob changed during verification")
        receipts[part.sha256] = Receipt(part.size, before.modified.coerceAtLeast(0))
        saveReceipts()
        return true
    }

    /** Publish only after every blob was verified on this source or has an unchanged positive-time
     * receipt from a previous verification. Unknown timestamps are always rehashed remotely.
     * Hash validation during hydration remains mandatory; receipts are metadata optimizations.
     */
    fun publish(notebookId: String, staged: PackageTransfer.Staged, localBlobs: File, parents: List<String>): Published {
        val commit = Commit(notebookId = notebookId, parents = parents.sorted(), notebook = staged.manifest)
        val bytes = encode(commit); val hash = sha(bytes)
        val snapshot = scan()
        require(parents.all { snapshot.ready[it]?.notebookId == notebookId }) { "Transfer parent is missing or belongs to another notebook" }
        var uploaded = 0L; var reused = 0L
        var listing = blobs.list()
        staged.manifest.parts.distinctBy { it.sha256 }.forEach { part ->
            checkCancelled()
            val local = File(localBlobs, part.sha256)
            require(!Files.isSymbolicLink(local.toPath()) && verify(local.inputStream(), part)) { "Invalid local transfer blob" }
            val stat = listing[part.sha256]
            val receipt = receipts[part.sha256]
            val trusted = stat != null && stat.modified > 0 && receipt == Receipt(stat.size, stat.modified) && stat.size == part.size
            if (trusted || (stat != null && verifiedRemote(part, stat))) { reused += part.size; return@forEach }
            blobs.write(part.sha256, local)
            val written = blobs.list()[part.sha256] ?: throw IOException("Uploaded transfer blob is missing")
            if (!verifiedRemote(part, written)) throw IOException("Uploaded transfer blob failed verification")
            uploaded += part.size; listing = blobs.list()
        }
        checkCancelled()
        val name = "$hash.commit"
        val present = commits.list()[name]
        val alreadyValid = if (present != null) {
            try { decode(bounded(commits.read(name), maxCommit), hash); true }
            catch (e: InterruptedIOException) { throw e }
            catch (_: IllegalArgumentException) { false }
        } else false
        if (!alreadyValid) {
            val temp = File.createTempFile("commit-", ".pending", workspace)
            try { temp.writeBytes(bytes); commits.write(name, temp) } finally { temp.delete() }
            decode(bounded(commits.read(name), maxCommit), hash)
        }
        return Published(Version(hash, commit), uploaded, reused)
    }
}
