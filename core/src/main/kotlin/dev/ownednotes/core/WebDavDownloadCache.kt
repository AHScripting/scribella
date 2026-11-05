package dev.ownednotes.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Dedicated private throwaway cache, never a library/materials/sync directory.
 * Every consumer must hold withSlot for the complete checkpoint/download/copy operation.
 * The root lock excludes eviction across threads, instances and processes; slots are not exported.
 */
internal class WebDavDownloadCache private constructor(
    private val root: File,
    private val scope: String,
    private val endpoint: WebDavEndpoint,
    val accountId: UUID,
    private val maxReservedBytes: Long,
    private val maxSlots: Int,
) {
    private data class Slot(val file: File, val size: Long)
    private data class Contents(val size: Long, val files: List<File>)

    fun <T> withSlot(entry: WebDavListing.Entry, block: (File) -> T): T = safe {
        if (!WebDavEndpoint.SYNC_NAME.matches(entry.name) || entry.size !in 0..WebDavDownloadCheckpoint.MAX_DOWNLOAD_BYTES)
            fail(WebDavHttpClient.Problem.CONTENT)
        val tag = entry.strongEtag ?: fail(WebDavHttpClient.Problem.UNSUPPORTED)
        if (tag.length !in 2..4096 || !Regex("\"[\\x21\\x23-\\x7E]*\"").matches(tag)) fail(WebDavHttpClient.Problem.CONTENT)
        if (entry.size > maxReservedBytes) fail()
        locked {
            val id = hash("${entry.name}\n$tag\n${entry.size}")
            val target = File(root, "slot-$id")
            val slots = (root.listFiles() ?: fail()).filter { SLOT.matches(it.name) }.mapNotNull { dir ->
                if (!Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS)) fail()
                if (!File(dir, "slot.owner").exists() && (dir.list() ?: fail()).isEmpty()) {
                    // A crash between creating an owned empty slot and its marker has no payload to adopt.
                    Files.delete(dir.toPath()); null
                } else Slot(dir, validateSlot(dir))
            }.toMutableList()
            val found = slots.firstOrNull { it.file == target }
            if (found != null && found.size != entry.size) fail()
            var reserved = slots.sumOf { it.size }
            if (found == null) {
                val oldest = slots.sortedBy { it.file.lastModified() }.iterator()
                while (slots.size >= maxSlots || reserved + entry.size > maxReservedBytes) {
                    if (!oldest.hasNext()) fail()
                    val victim = oldest.next()
                    eraseSlot(victim.file); reserved -= victim.size; slots.remove(victim)
                }
                Files.createDirectory(target.toPath())
                write(File(target, "slot.owner"), "NLRESUME1\n$scope\n$id\n${entry.size}")
            }
            // A checkpoint is disposable only inside this proved owned namespace. No native file/journal is reset.
            try { WebDavDownloadCheckpoint.open(target, endpoint, accountId, entry).session { } }
            catch (failure: WebDavHttpClient.Failure) {
                if (failure.problem != WebDavHttpClient.Problem.STORAGE) throw failure
                val contents = inspectSlot(target)
                for (file in contents.files.filter { it.name != "slot.owner" && it.name != "download.lock" })
                    Files.deleteIfExists(file.toPath())
                WebDavDownloadCheckpoint.open(target, endpoint, accountId, entry).session { }
            }
            // Approximate LRU is sufficient; an OS timestamp failure cannot authorize extra deletion.
            target.setLastModified(System.currentTimeMillis())
            block(target)
        }
    }

    private fun validateSlot(dir: File): Long = inspectSlot(dir).size

    private fun inspectSlot(dir: File): Contents {
        val marker = File(dir, "slot.owner")
        if (!Files.isRegularFile(marker.toPath(), NOFOLLOW_LINKS) || marker.length() !in 1..256) fail()
        val lines = marker.readText(Charsets.US_ASCII).split('\n')
        if (lines.size != 4 || lines[0] != "NLRESUME1" || lines[1] != scope || "slot-${lines[2]}" != dir.name) fail()
        val size = lines[3].toLongOrNull() ?: fail()
        if (size !in 0..WebDavDownloadCheckpoint.MAX_DOWNLOAD_BYTES || lines[3] != size.toString()) fail()
        val files = ArrayList<File>()
        for (file in (dir.listFiles() ?: fail())) {
            // A platform deletion can leave an already-gone name in a directory enumeration.
            // Ignore only proved absence; existing unknown files and symlinks still prevent cleanup.
            val attributes = try {
                Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            } catch (_: java.nio.file.NoSuchFileException) { continue }
            if (file.name !in setOf("slot.owner", "download.lock", "download.state", "payload.part") && !TEMP.matches(file.name)) {
                // A deletion's transient rename may still exist at the first attribute read.
                // Wait at most 25ms without modifying it; cleanup is allowed only after proved absence.
                if (awaitAbsent(file)) continue
                fail()
            }
            if (!attributes.isRegularFile) fail()
            files.add(file)
        }
        return Contents(size, files)
    }

    private fun awaitAbsent(file: File): Boolean {
        repeat(5) {
            try { Thread.sleep(5) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); fail(WebDavHttpClient.Problem.BUSY) }
            try { Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
            catch (_: java.nio.file.NoSuchFileException) { return true }
        }
        return false
    }

    private fun eraseSlot(dir: File) {
        val contents = inspectSlot(dir)
        for (file in contents.files.filter { it.name != "slot.owner" }) Files.deleteIfExists(file.toPath())
        Files.delete(File(dir, "slot.owner").toPath()); Files.delete(dir.toPath())
    }

    private fun <T> locked(block: () -> T): T {
        if (!Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) fail()
        val key = root.toPath().toRealPath(); val owner = Any()
        if (owners.putIfAbsent(key, owner) != null) fail(WebDavHttpClient.Problem.BUSY)
        try {
            val marker = File(root, "cache.owner")
            if (!Files.isRegularFile(marker.toPath(), NOFOLLOW_LINKS) || marker.length() !in 1..128 ||
                marker.readText(Charsets.US_ASCII) != "NLRESUMECACHE1\n$scope") fail()
            val path = File(root, "cache.lock").toPath()
            val channel = try { FileChannel.open(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS) }
                catch (_: java.nio.file.FileAlreadyExistsException) { FileChannel.open(path, WRITE, NOFOLLOW_LINKS) }
            return channel.use { handle ->
                val lock = try { handle.tryLock() } catch (_: OverlappingFileLockException) { null }
                    ?: fail(WebDavHttpClient.Problem.BUSY)
                lock.use { block() }
            }
        } finally { owners.remove(key, owner) }
    }

    companion object {
        private val SLOT = Regex("slot-[0-9a-f]{64}")
        private val TEMP = Regex("download-state-[0-9]+\\.tmp")
        private val owners = ConcurrentHashMap<Path, Any>()
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
        private fun fail(problem: WebDavHttpClient.Problem = WebDavHttpClient.Problem.STORAGE): Nothing = throw WebDavHttpClient.Failure(problem)
        private fun write(file: File, value: String) {
            FileChannel.open(file.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { }
            FileOutputStream(file).use { it.write(value.toByteArray(Charsets.US_ASCII)); it.fd.sync() }
        }
        private fun <T> safe(block: () -> T): T = try { block() }
            catch (error: WebDavHttpClient.Failure) { throw error }
            catch (_: IOException) { fail() } catch (_: SecurityException) { fail() }

        fun open(privateCache: File, endpoint: WebDavEndpoint, accountId: UUID,
                 maxReservedBytes: Long = 2L * 1024 * 1024 * 1024, maxSlots: Int = 8): WebDavDownloadCache = safe {
            require(maxReservedBytes in 0..2L * 1024 * 1024 * 1024 && maxSlots in 1..32)
            if (!Files.isDirectory(privateCache.toPath(), NOFOLLOW_LINKS)) fail()
            val scope = hash("$accountId\n${endpoint.collection.toASCIIString()}")
            val root = File(privateCache, "webdav-resume-$scope")
            try {
                Files.createDirectory(root.toPath()); write(File(root, "cache.owner"), "NLRESUMECACHE1\n$scope")
            } catch (_: java.nio.file.FileAlreadyExistsException) { }
            val marker = File(root, "cache.owner")
            if (!Files.isDirectory(root.toPath(), NOFOLLOW_LINKS) || !Files.isRegularFile(marker.toPath(), NOFOLLOW_LINKS) ||
                marker.length() !in 1..128 || marker.readText(Charsets.US_ASCII) != "NLRESUMECACHE1\n$scope") fail()
            WebDavDownloadCache(root, scope, endpoint, accountId, maxReservedBytes, maxSlots)
        }
    }
}
