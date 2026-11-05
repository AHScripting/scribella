package dev.ownednotes.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Internal durable prefix for one representation, not authorization to publish a notebook.
 * A dedicated app-private cache slot is required, never a library/materials/shared directory.
 * The HTTP caller must still validate conditional range responses and remote identity before publication.
 * Checksums detect accidental corruption, not an attacker able to replace both private metadata and payload.
 */
internal class WebDavDownloadCheckpoint private constructor(
    private val directory: File,
    private val binding: List<String>,
    private val bytes: Long,
    private val expectedSha256: String?,
) {
    private val record get() = File(directory, "download.state").toPath()
    private val payload get() = File(directory, "payload.part").toPath()

    fun <T> session(block: (Session) -> T): T = safe {
        require(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS))
        val key = directory.toPath().toRealPath()
        val owner = Any()
        if (owners.putIfAbsent(key, owner) != null) fail(WebDavHttpClient.Problem.BUSY)
        try {
            val lock = File(directory, "download.lock").toPath()
            val channel = try { FileChannel.open(lock, CREATE_NEW, WRITE, NOFOLLOW_LINKS) }
                catch (_: java.nio.file.FileAlreadyExistsException) { FileChannel.open(lock, WRITE, NOFOLLOW_LINKS) }
            channel.use { handle ->
                val acquired = try { handle.tryLock() } catch (_: OverlappingFileLockException) { null }
                    ?: fail(WebDavHttpClient.Problem.BUSY)
                acquired.use {
                    val hasRecord = Files.exists(record, NOFOLLOW_LINKS)
                    val hasPayload = Files.exists(payload, NOFOLLOW_LINKS)
                    if (!hasRecord && !hasPayload) {
                        FileChannel.open(payload, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
                        save(0, EMPTY_CHAIN)
                    }
                    // One missing file is not permission to adopt an unknown prefix or erase a record.
                    require(Files.isRegularFile(record, NOFOLLOW_LINKS) && Files.isRegularFile(payload, NOFOLLOW_LINKS))
                    val state = load()
                    val session = Session(state.first, state.second)
                    try { block(session) } finally { session.active = false }
                }
            }
        } finally { owners.remove(key, owner) }
    }

    inner class Session internal constructor(offset: Long, chain: ByteArray) {
        private val thread = Thread.currentThread()
        internal var active = true
        private var poisoned = false
        private var offset = offset
        private var chain = chain
        private var digest = MessageDigest.getInstance("SHA-256")
        private var completeSha: String? = null

        init {
            require(Files.size(payload) >= offset)
            var remaining = offset
            var actualChain = EMPTY_CHAIN
            val buffer = ByteArray(BLOCK_BYTES)
            Files.newInputStream(payload, NOFOLLOW_LINKS).use { input ->
                while (remaining > 0) {
                    val count = minOf(BLOCK_BYTES.toLong(), remaining).toInt()
                    var at = 0
                    while (at < count) {
                        val read = input.read(buffer, at, count - at)
                        require(read > 0)
                        at += read
                    }
                    digest.update(buffer, 0, count)
                    actualChain = nextChain(actualChain, buffer, count)
                    remaining -= count
                }
            }
            require(MessageDigest.isEqual(chain, actualChain))
            if (offset == bytes) completeSha = checkedCompleteSha()
            // Only after checking the recorded prefix may a crash's uncommitted tail be discarded.
            if (Files.size(payload) > offset) FileChannel.open(payload, WRITE, NOFOLLOW_LINKS).use {
                it.truncate(offset); it.force(true)
            }
        }

        val committedBytes: Long get() { owned(); return offset }
        val completedSha256: String? get() { owned(); return completeSha }

        /** Commit exactly one full block, or the representation's final short block. */
        fun append(data: ByteArray, count: Int = data.size) {
            owned()
            if (count <= 0 || count > data.size || count != minOf(BLOCK_BYTES.toLong(), bytes - offset).toInt())
                fail(WebDavHttpClient.Problem.CONTENT)
            try {
                safe {
                    FileChannel.open(payload, WRITE, NOFOLLOW_LINKS).use { output ->
                        output.position(offset)
                        val buffer = ByteBuffer.wrap(data, 0, count)
                        while (buffer.hasRemaining()) output.write(buffer)
                        output.force(true)
                    }
                    val next = nextChain(chain, data, count)
                    digest.update(data, 0, count)
                    val total = offset + count
                    val sha = if (total == bytes) checkedCompleteSha() else null
                    save(total, next)
                    offset = total; chain = next; completeSha = sha
                }
            } catch (error: Throwable) { poisoned = true; throw error }
        }

        /** An explicit full-response fallback can reset only this slot, not any saved notebook. */
        fun reset() {
            owned()
            try {
                safe {
                    // If interrupted before truncation, reopen treats all old bytes as an uncommitted tail.
                    save(0, EMPTY_CHAIN)
                    FileChannel.open(payload, WRITE, NOFOLLOW_LINKS).use { it.truncate(0); it.force(true) }
                    offset = 0; chain = EMPTY_CHAIN; digest = MessageDigest.getInstance("SHA-256")
                    completeSha = if (bytes == 0L) checkedCompleteSha() else null
                }
            } catch (error: Throwable) { poisoned = true; throw error }
        }

        /** Still private staging: a verified HTTP operation must own final destination publication. */
        fun completedFile(): File {
            owned()
            if (completeSha == null) fail(WebDavHttpClient.Problem.CONTENT)
            return payload.toFile()
        }

        private fun checkedCompleteSha(): String {
            val result = hex(digest.digest())
            if (expectedSha256 != null && expectedSha256 != result) fail(WebDavHttpClient.Problem.CHANGED)
            return result
        }

        private fun owned() {
            if (!active || poisoned || Thread.currentThread() !== thread) fail(WebDavHttpClient.Problem.STORAGE)
        }
    }

    private fun load(): Pair<Long, ByteArray> {
        require(Files.size(record) in 1..MAX_RECORD_BYTES)
        val raw = Files.newInputStream(record, NOFOLLOW_LINKS).use { it.readNBytes(MAX_RECORD_BYTES.toInt() + 1) }
        require(raw.size.toLong() in 1..MAX_RECORD_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
        val lines = text.split('\n')
        require(lines.size == 9 && lines[0] == "NLDOWNLOAD1")
        val body = lines.drop(2).joinToString("\n")
        require(lines[1] == hex(hash(body.toByteArray(Charsets.UTF_8))))
        require(lines.subList(2, 7) == binding)
        val offset = lines[7].toLong()
        require(lines[7] == offset.toString() && offset in 0..bytes && (offset == bytes || offset % BLOCK_BYTES == 0L))
        require(SHA.matches(lines[8]))
        return offset to lines[8].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun save(offset: Long, chain: ByteArray) {
        require(!Files.exists(record, NOFOLLOW_LINKS) || Files.isRegularFile(record, NOFOLLOW_LINKS))
        val body = (binding + offset.toString() + hex(chain)).joinToString("\n")
        val data = "NLDOWNLOAD1\n${hex(hash(body.toByteArray(Charsets.UTF_8)))}\n$body".toByteArray(Charsets.UTF_8)
        require(data.size.toLong() in 1..MAX_RECORD_BYTES)
        val temporary = File.createTempFile("download-state-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { it.write(data); it.fd.sync() }
            Files.move(temporary.toPath(), record, ATOMIC_MOVE, REPLACE_EXISTING)
            // Best effort on platforms which can open/fsync directories; not a universal power-loss guarantee.
            try { FileChannel.open(directory.toPath(), READ).use { it.force(true) } }
            catch (_: IOException) { } catch (_: SecurityException) { } catch (_: UnsupportedOperationException) { }
        } finally {
            try { Files.deleteIfExists(temporary.toPath()) } catch (_: IOException) { } catch (_: SecurityException) { }
        }
    }

    private fun <T> safe(block: () -> T): T = try { block() }
        catch (error: WebDavHttpClient.Failure) { throw error }
        catch (_: IOException) { fail() }
        catch (_: IllegalArgumentException) { fail() }
        catch (_: SecurityException) { fail() }
        catch (_: UnsupportedOperationException) { fail() }

    companion object {
        const val BLOCK_BYTES = 1024 * 1024
        const val MAX_DOWNLOAD_BYTES = 1024L * 1024 * 1024
        private const val MAX_RECORD_BYTES = 16L * 1024
        private val SHA = Regex("[0-9a-f]{64}")
        private val TAG = Regex("\"[\\x21\\x23-\\x7E]*\"")
        private val owners = ConcurrentHashMap<Path, Any>()
        private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
        private fun hex(data: ByteArray) = data.joinToString("") { "%02x".format(it) }
        private val EMPTY_CHAIN = hash(byteArrayOf())
        private fun nextChain(previous: ByteArray, data: ByteArray, count: Int): ByteArray =
            MessageDigest.getInstance("SHA-256").apply {
                update(previous); update(ByteBuffer.allocate(4).putInt(count).array()); update(data, 0, count)
            }.digest()
        private fun fail(problem: WebDavHttpClient.Problem = WebDavHttpClient.Problem.STORAGE): Nothing =
            throw WebDavHttpClient.Failure(problem)

        /** Directory must already be a dedicated app-private slot; no network or global sweep here. */
        fun open(directory: File, endpoint: WebDavEndpoint, accountId: UUID, entry: WebDavListing.Entry,
                 expectedSha256: String? = null): WebDavDownloadCheckpoint {
            val tag = entry.strongEtag ?: fail(WebDavHttpClient.Problem.UNSUPPORTED)
            if (tag.length !in 2..4096 || !TAG.matches(tag) || !WebDavEndpoint.SYNC_NAME.matches(entry.name) ||
                entry.size !in 0..MAX_DOWNLOAD_BYTES || expectedSha256 != null && !SHA.matches(expectedSha256))
                fail(WebDavHttpClient.Problem.CONTENT)
            val encode = Base64.getEncoder()
            val scope = hex(hash("$accountId\n${endpoint.collection.toASCIIString()}".toByteArray(Charsets.UTF_8)))
            val binding = listOf(scope, encode.encodeToString(entry.name.toByteArray(Charsets.UTF_8)),
                encode.encodeToString(tag.toByteArray(Charsets.US_ASCII)), entry.size.toString(), expectedSha256 ?: "-")
            return WebDavDownloadCheckpoint(directory.absoluteFile, binding, entry.size, expectedSha256)
        }
    }
}
