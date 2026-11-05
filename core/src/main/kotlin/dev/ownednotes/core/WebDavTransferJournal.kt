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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Private, single-account write-ahead metadata. Never place this directory in the sync folder.
 * File contents are fsynced before atomic replacement; this is not a universal power-loss guarantee.
 * A pending attempt is evidence to reconcile, never permission to retry or delete its stage blindly.
 */
class WebDavTransferJournal private constructor(
    private val directory: File,
    private val endpointHash: String,
    private val scopeHash: String,
) {
    @Serializable enum class Kind { UPLOAD, DELETE }
    @Serializable enum class Phase { PREPARED, STAGED, COMMITTING }
    @Serializable data class Pending(
        val id: String,
        val kind: Kind,
        val name: String,
        val bytes: Long,
        val sha256: String? = null,
        val previousEtag: String? = null,
        val stageId: String? = null,
        val stageEtag: String? = null,
        val phase: Phase,
    )
    @Serializable private data class Document(val version: Int, val scope: String, val pending: Pending?)
    private val file get() = File(directory, "intent.json")

    /** Safe metadata only; concurrent active writes report BUSY instead of blocking. */
    fun pending(): Pending? = locked { it.current }

    internal fun requireAccount(endpoint: WebDavEndpoint, accountId: UUID) {
        if (digest("$accountId\n${digest(endpoint.collection.toASCIIString())}") != scopeHash) fail()
        session(endpoint) { }
    }

    internal fun <T> session(endpoint: WebDavEndpoint, block: (Session) -> T): T {
        return recovery(endpoint) { session ->
            if (session.current != null) throw WebDavHttpClient.Failure(WebDavHttpClient.Problem.RECOVERY_REQUIRED)
            block(session)
        }
    }

    internal fun <T> recovery(endpoint: WebDavEndpoint, block: (Session) -> T): T {
        if (digest(endpoint.collection.toASCIIString()) != endpointHash) fail()
        return locked(block)
    }

    /** Only the owning locked operation can change the record. There is no public clear/reset bypass. */
    internal inner class Session internal constructor(pending: Pending?) {
        private val thread = Thread.currentThread()
        private var active = true
        internal var current: Pending? = pending
            private set

        internal fun release() { active = false }
        private fun owned() { require(active && Thread.currentThread() === thread) }

        fun begin(value: Pending) {
            owned()
            if (current != null) throw WebDavHttpClient.Failure(WebDavHttpClient.Problem.RECOVERY_REQUIRED)
            validate(value)
            save(Document(1, scopeHash, value))
            current = value
        }

        fun staged(tag: String) {
            owned()
            val old = current ?: fail()
            require(old.kind == Kind.UPLOAD && old.phase == Phase.PREPARED)
            val next = old.copy(stageEtag = tag, phase = Phase.STAGED)
            validate(next)
            save(Document(1, scopeHash, next))
            current = next
        }

        fun committing() {
            owned()
            val old = current ?: fail()
            require(old.kind == Kind.UPLOAD && old.phase == Phase.STAGED)
            val next = old.copy(phase = Phase.COMMITTING)
            save(Document(1, scopeHash, next))
            current = next
        }

        fun completed() {
            owned()
            require(current?.phase == Phase.COMMITTING)
            save(Document(1, scopeHash, null))
            current = null
        }

        /** Only after read-back of this recorded stage; never downgrade a committing record. */
        fun confirmStage(tag: String) {
            owned()
            val old = current ?: fail()
            require(old.kind == Kind.UPLOAD)
            if (old.stageEtag != null && old.stageEtag != tag) throw WebDavHttpClient.Failure(WebDavHttpClient.Problem.CHANGED)
            val next = old.copy(stageEtag = tag, phase = if (old.phase == Phase.PREPARED) Phase.STAGED else old.phase)
            validate(next)
            save(Document(1, scopeHash, next))
            current = next
        }

        /** Internal acknowledgement after fresh remote reconciliation; no public reset. */
        fun resolved(expectedAttemptId: String) {
            owned()
            if (current?.id != expectedAttemptId) throw WebDavHttpClient.Failure(WebDavHttpClient.Problem.CHANGED)
            save(Document(1, scopeHash, null))
            current = null
        }
    }

    private fun <T> locked(block: (Session) -> T): T = safe {
        require(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS))
        val key = directory.toPath().toRealPath()
        val owner = Any()
        if (owners.putIfAbsent(key, owner) != null) throw WebDavHttpClient.Failure(WebDavHttpClient.Problem.BUSY)
        try {
            val lock = File(directory, "intent.lock").toPath()
            var fresh = false
            val channel = try {
                FileChannel.open(lock, CREATE_NEW, WRITE, NOFOLLOW_LINKS).also { fresh = true }
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                FileChannel.open(lock, WRITE, NOFOLLOW_LINKS)
            }
            channel.use { handle ->
                val acquired = try { handle.tryLock() } catch (_: OverlappingFileLockException) { null }
                    ?: throw WebDavHttpClient.Failure(WebDavHttpClient.Problem.BUSY)
                acquired.use {
                    // Once a lock/store existed, losing its document is corruption, not an empty journal.
                    if (fresh && !Files.exists(file.toPath(), NOFOLLOW_LINKS)) save(Document(1, scopeHash, null))
                    val session = Session(load().pending)
                    try { block(session) } finally { session.release() }
                }
            }
        } finally { owners.remove(key, owner) }
    }

    private fun load(): Document {
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
        require(Files.size(file.toPath()) in 1..MAX_BYTES)
        val bytes = Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { it.readNBytes(MAX_BYTES.toInt() + 1) }
        require(bytes.size.toLong() in 1..MAX_BYTES)
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val parts = decoder.decode(ByteBuffer.wrap(bytes)).toString().split('\n', limit = 3)
        require(parts.size == 3 && parts[0] == "NLDAV1" && parts[1] == digest(parts[2]))
        validateJsonShape(parts[2])
        val value = Json.decodeFromString<Document>(parts[2])
        require(value.version == 1 && value.scope == scopeHash)
        value.pending?.let(::validate)
        return value
    }

    private fun save(document: Document) {
        val payload = Json.encodeToString(document)
        val bytes = "NLDAV1\n${digest(payload)}\n$payload".toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() in 1..MAX_BYTES)
        require(!Files.exists(file.toPath(), NOFOLLOW_LINKS) || Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
        val temporary = File.createTempFile("dav-intent-", ".part", directory)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            // Supported on Android/Linux; Windows may reject opening a directory channel.
            try { FileChannel.open(directory.toPath(), READ).use { it.force(true) } }
            catch (_: IOException) { } catch (_: SecurityException) { } catch (_: UnsupportedOperationException) { }
        } finally {
            try { Files.deleteIfExists(temporary.toPath()) } catch (_: IOException) { } catch (_: SecurityException) { }
        }
    }

    private fun validate(value: Pending) {
        require(UUID.fromString(value.id).toString() == value.id)
        require(WebDavEndpoint.SYNC_NAME.matches(value.name) && value.bytes in 0..1024L * 1024 * 1024)
        require(value.previousEtag == null || validTag(value.previousEtag))
        when (value.kind) {
            Kind.UPLOAD -> {
                require(value.sha256?.matches(Regex("[0-9a-f]{64}")) == true)
                require(value.stageId != null && UUID.fromString(value.stageId).toString() == value.stageId)
                require(value.stageEtag == null || validTag(value.stageEtag))
                require((value.phase == Phase.PREPARED) == (value.stageEtag == null))
            }
            Kind.DELETE -> require(value.sha256 == null && value.stageId == null && value.stageEtag == null &&
                value.previousEtag != null && value.phase == Phase.COMMITTING)
        }
    }

    private fun validTag(value: String) = value.length in 2..4096 && Regex("\"[\\x21\\x23-\\x7E]*\"").matches(value)

    /** This schema has two object levels and no arrays. Reject duplicates/deep input before decoding. */
    private fun validateJsonShape(text: String) {
        val objects = ArrayDeque<MutableSet<String>>()
        var quoted = false; var escaped = false; var start = 0
        for ((i, c) in text.withIndex()) {
            if (quoted) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> {
                        quoted = false
                        var after = i + 1
                        while (after < text.length && text[after].isWhitespace()) after++
                        if (after < text.length && text[after] == ':') {
                            require(objects.isNotEmpty())
                            val key = Json.decodeFromString<String>(text.substring(start, i + 1))
                            require(objects.last().add(key) && objects.last().size <= 16)
                        }
                    }
                }
            } else when (c) {
                '"' -> { quoted = true; start = i }
                '{' -> { objects.addLast(mutableSetOf()); require(objects.size <= 2) }
                '}' -> { require(objects.isNotEmpty()); objects.removeLast() }
                '[', ']' -> require(false)
            }
        }
        require(!quoted && objects.isEmpty())
    }
    private fun fail(): Nothing = throw WebDavHttpClient.Failure(WebDavHttpClient.Problem.STORAGE)
    private inline fun <T> safe(block: () -> T): T = try { block() }
        catch (e: WebDavHttpClient.Failure) { throw e }
        catch (_: IOException) { fail() }
        catch (_: IllegalArgumentException) { fail() }
        catch (_: SecurityException) { fail() }

    companion object {
        private const val MAX_BYTES = 32L * 1024
        // No second channel may be opened/closed while this JVM owns a store's OS lock.
        private val owners = ConcurrentHashMap<Path, Any>()
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        /** accountId must be the same locally persisted account ID across process restarts. */
        fun open(directory: File, endpoint: WebDavEndpoint, accountId: UUID): WebDavTransferJournal {
            val endpointHash = digest(endpoint.collection.toASCIIString())
            val journal = WebDavTransferJournal(directory.absoluteFile, endpointHash, digest("$accountId\n$endpointHash"))
            return journal.safe {
                Files.createDirectories(directory.toPath())
                journal.pending() // Validate/create without contacting a server.
                journal
            }
        }
    }
}
