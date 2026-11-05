package dev.ownednotes.core

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink

/**
 * Blocking, one-operation-at-a-time HTTPS client. Caller runs off the UI thread and owns local
 * version/conflict policy. No account persistence, redirects or automatic retries.
 */
class WebDavHttpClient internal constructor(
    private val endpoint: WebDavEndpoint,
    client: OkHttpClient,
    private val authorization: String?,
) : Closeable {
    enum class Problem { ACCESS, CHANGED, UNSUPPORTED, NETWORK, CONTENT, STORAGE, CANCELLED, BUSY, UNCERTAIN, RECOVERY_REQUIRED }
    class Failure(val problem: Problem) : IOException("WebDAV ${problem.name.lowercase()} failure"), FolderSync.ClassifiedFailure {
        override val syncProblemKind: FolderSync.ProblemKind get() = when (problem) {
            Problem.ACCESS -> FolderSync.ProblemKind.ACCESS
            Problem.CONTENT -> FolderSync.ProblemKind.CONTENT
            Problem.CHANGED, Problem.UNSUPPORTED, Problem.NETWORK, Problem.STORAGE,
            Problem.CANCELLED, Problem.BUSY, Problem.UNCERTAIN, Problem.RECOVERY_REQUIRED -> FolderSync.ProblemKind.UNAVAILABLE
        }
    }
    data class Download(val bytes: Long, val sha256: String, val etag: String)

    // Retain injected TLS trust only for tests. The public factory always uses platform TLS trust.
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).cache(null).cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .addNetworkInterceptor { chain ->
            // The connection-retry flag alone does not suppress e.g. 503 Retry-After:0 follow-ups.
            val attempt = chain.request().tag(Attempt::class.java) ?: throw Failure(Problem.CONTENT)
            if (!attempt.sent.compareAndSet(false, true)) throw Failure(Problem.NETWORK)
            chain.proceed(chain.request())
        }.build()
    private val active = AtomicReference<Call?>()
    private val closed = AtomicBoolean()
    private val receiptSignal = Object()
    private val operating = AtomicBoolean()
    private var writePermit: Any? = null // Accessed only under whole-operation ownership.
    private class Attempt { val sent = AtomicBoolean() }

    /** Bound to this session's last successful probe; never persisted or transferable. */
    class Writer internal constructor(private val owner: WebDavHttpClient, private val permit: Any) {
        fun upload(journal: WebDavTransferJournal, name: String, source: File, expectedSha256: String, previousEtag: String? = null): Download =
            owner.authorized(permit) {
                journal.session(owner.endpoint) { owner.uploadLocked(name, source, expectedSha256, previousEtag, it) }
            }
        fun delete(journal: WebDavTransferJournal, entry: WebDavListing.Entry): Unit = owner.authorized(permit) {
            journal.session(owner.endpoint) { owner.deleteLocked(entry, it) }
        }
        /** Re-observe, optionally remove only the verified owned stage, then acknowledge this attempt.
         * Never replays a native mutation. Conflicting observations keep all native writes blocked.
         */
        fun reconcile(journal: WebDavTransferJournal, expectedAttemptId: String): WebDavRecovery = owner.authorized(permit) {
            journal.recovery(owner.endpoint) { owner.reconcileLocked(it, expectedAttemptId) }
        }
    }

    /** GET-only inspection, including on a server that has not earned a write permit. */
    fun inspectRecovery(journal: WebDavTransferJournal): WebDavRecovery? = operation {
        journal.recovery(endpoint) { it.current?.let(::inspectLocked) }
    }

    internal fun requireReadyJournal(journal: WebDavTransferJournal): Unit = operation {
        journal.session(endpoint) { }
    }

    internal fun downloadCache(journal: WebDavTransferJournal, privateCache: File, accountId: UUID): WebDavDownloadCache = operation {
        journal.requireAccount(endpoint, accountId)
        WebDavDownloadCache.open(privateCache, endpoint, accountId)
    }

    private fun inspectLocked(pending: WebDavTransferJournal.Pending): WebDavRecovery {
        val target = recoveryResource(endpoint.resource(pending.name).toASCIIString())
        val stage = pending.stageId?.let { recoveryResource(endpoint.stage(UUID.fromString(it)).toASCIIString()) }
        return WebDavRecovery.observed(pending, target, stage)
    }

    private fun reconcileLocked(session: WebDavTransferJournal.Session, expectedId: String): WebDavRecovery {
        val pending = session.current
        if (pending == null || pending.id != expectedId) throw Failure(Problem.CHANGED)
        val before = inspectLocked(pending)
        if (before.outcome == WebDavRecovery.Outcome.CONFLICT || before.stageState == WebDavRecovery.StageState.CHANGED) {
            throw Failure(Problem.RECOVERY_REQUIRED)
        }
        if (before.stageState == WebDavRecovery.StageState.VERIFIED) {
            val stage = before.stage!!
            session.confirmStage(stage.etag) // Durable ownership receipt before any cleanup request.
            exchange(request(endpoint.stage(UUID.fromString(pending.stageId)).toASCIIString())
                .header("If-Match", stage.etag).delete().build(), 204, commit = true) { }
        }
        val after = inspectLocked(session.current!!)
        if (after.stageState != WebDavRecovery.StageState.ABSENT) throw Failure(Problem.UNCERTAIN)
        if (after.target != before.target || after.outcome != before.outcome) throw Failure(Problem.CHANGED)
        checkOpen()
        session.resolved(expectedId)
        return after
    }

    /** Stream a bounded payload only into a digest; retain neither bytes nor a local file. */
    private fun recoveryResource(uri: String): WebDavRecovery.Resource? = exchange(request(uri).get().build(), null) { response ->
        when (response.code) {
            404 -> null
            200 -> {
                val tag = strongTag(response.headers.values("ETag").singleOrNull())
                val lengths = response.headers.values("Content-Length")
                val length = if (lengths.isEmpty()) null else lengths.singleOrNull()?.toLongOrNull()
                    ?.takeIf { it in 0..MAX_DOWNLOAD } ?: throw Failure(Problem.CONTENT)
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkOpen()
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        if (count > MAX_DOWNLOAD || length != null && count > length) throw Failure(Problem.CONTENT)
                        digest.update(buffer, 0, read)
                    }
                }
                if (length != null && count != length) throw Failure(Problem.CONTENT)
                WebDavRecovery.Resource(tag, count, digest.digest().joinToString("") { "%02x".format(it) })
            }
            401, 403 -> throw Failure(Problem.ACCESS)
            409, 412 -> throw Failure(Problem.CHANGED)
            in 300..399, 405, 501 -> throw Failure(Problem.UNSUPPORTED)
            else -> throw Failure(Problem.NETWORK)
        }
    }

    /**
     * Touches only random synthetic staging names. Passing is evidence for this server/configuration,
     * not a proof against future arbitrary server faults. Repository/version/deletion-intent and
     * dependency/security activation requirements still apply. A failed re-probe revokes old writers.
     */
    fun verifyWriteSupport(): Writer = operation {
        writePermit = null
        val owned = linkedMapOf<String, ProbeFile>()
        var preconditionsAccepted = false
        val a = endpoint.stage(UUID.randomUUID()).toASCIIString()
        val b = endpoint.stage(UUID.randomUUID()).toASCIIString()
        val c = endpoint.stage(UUID.randomUUID()).toASCIIString()
        try {
            fun create(uri: String, payload: String): ProbeFile {
                val bytes = payload.toByteArray(Charsets.UTF_8)
                exchange(request(uri).header("If-None-Match", "*").put(bytes.toRequestBody()).build(), 201) { }
                val item = ProbeFile(bytes.size.toLong(), FolderSync.sha256(bytes.inputStream()), head(uri, bytes.size.toLong()))
                verifyRemote(uri, item.size, item.tag, item.sha)
                owned[uri] = item
                return item
            }
            var first = create(a, "scribella synthetic write probe ${UUID.randomUUID()}")
            var second = create(b, "scribella different synthetic destination ${UUID.randomUUID()}")
            var wrong = "\"scribella-never-observed-${UUID.randomUUID()}\""
            while (wrong == first.tag || wrong == second.tag) wrong = "\"${UUID.randomUUID()}\""
            fun unchanged() {
                try {
                    for ((uri, item) in owned) verifyRemote(uri, item.size, item.tag, item.sha)
                    // GET avoids noncompliant servers sending an HTML error body for HEAD404,
                    // which can corrupt the next response on a reused HTTP/1 connection.
                    exchange(request(c).get().build(), null) { if (it.code != 404) throw Failure(Problem.UNSUPPORTED) }
                } catch (e: Failure) {
                    if (e.problem in setOf(Problem.CHANGED, Problem.CONTENT)) throw Failure(Problem.UNSUPPORTED)
                    throw e
                }
            }
            fun refuse(builder: Request.Builder) {
                exchange(builder.build(), null) {
                    if (it.code != 412) throw Failure(if (it.code == 401 || it.code == 403) Problem.ACCESS else Problem.UNSUPPORTED)
                }
                unchanged() // A server returning412 while mutating bytes is also unsupported.
            }
            fun move(target: String, overwrite: String = "T") = request(a)
                .header("Destination", target).header("Overwrite", overwrite).method("MOVE", null)
            fun conditions(sourceTag: String, target: String, targetTag: String) = "<$a> ([$sourceTag]) <$target> ([$targetTag])"
            refuse(request(a).header("If-None-Match", "*").put("collision probe".toRequestBody()))
            refuse(request(a).header("If-Match", wrong).get())
            refuse(request(a).header("If-Match", wrong).delete())
            refuse(move(c, "F").header("If-Match", wrong))
            refuse(move(b).header("If", conditions(wrong, b, second.tag)))
            refuse(move(b).header("If", conditions(first.tag, b, wrong)))
            refuse(move(c).header("If", conditions(first.tag, c, second.tag)))
            refuse(move(b, "F").header("If-Match", first.tag))

            // Unknown tags alone are insufficient: an implementation might accept any tag it
            // has issued before. Exercise real prior tags of the same resource after an edit.
            fun update(uri: String, previous: ProbeFile, text: String): ProbeFile {
                val bytes = text.toByteArray(Charsets.UTF_8)
                exchange(request(uri).header("If-Match", previous.tag).put(bytes.toRequestBody()).build(), 204) { }
                val item = ProbeFile(bytes.size.toLong(), FolderSync.sha256(bytes.inputStream()), head(uri, bytes.size.toLong()))
                if (item.tag == previous.tag) throw Failure(Problem.UNSUPPORTED)
                verifyRemote(uri, item.size, item.tag, item.sha)
                owned[uri] = item
                return item
            }
            val oldFirst = first
            val oldSecond = second
            first = update(a, first, "scribella changed source probe with new bytes ${UUID.randomUUID()}")
            second = update(b, second, "scribella changed destination probe with different bytes ${UUID.randomUUID()}")
            refuse(request(a).header("If-Match", oldFirst.tag).get())
            refuse(request(a).header("If-Match", oldFirst.tag).delete())
            refuse(move(c, "F").header("If-Match", oldFirst.tag))
            refuse(move(b).header("If", conditions(oldFirst.tag, b, second.tag)))
            refuse(move(b).header("If", conditions(first.tag, b, oldSecond.tag)))
            preconditionsAccepted = true

            // Positive operations ensure denying every mutation cannot earn a permit.
            exchange(move(c, "F").header("If-Match", first.tag).build(), 201) { }
            owned.remove(a)
            val moved = first.copy(tag = head(c, first.size))
            owned[c] = moved
            verifyRemote(c, moved.size, moved.tag, moved.sha)
            exchange(request(a).get().build(), 404) { }
            exchange(request(c).header("Destination", b).header("Overwrite", "T")
                .header("If", "<$c> ([${moved.tag}]) <$b> ([${second.tag}])").method("MOVE", null).build(), 204) { }
            owned.remove(c)
            val replaced = moved.copy(tag = head(b, moved.size))
            owned[b] = replaced
            verifyRemote(b, replaced.size, replaced.tag, replaced.sha)
            exchange(request(c).get().build(), 404) { }
            exchange(request(b).header("If-Match", replaced.tag).delete().build(), 204) { }
            owned.remove(b)
            exchange(request(b).get().build(), 404) { }
            checkOpen()
            val permit = Any()
            writePermit = permit
            Writer(this, permit)
        } finally {
            // A server failing ANY condition check is not trusted with cleanup deletion either.
            if (preconditionsAccepted && !closed.get()) for ((uri, item) in owned) {
                try { exchange(request(uri).header("If-Match", item.tag).delete().build(), 204) { } }
                catch (_: Exception) { /* Unknown state leaves only synthetic probe orphans. */ }
            }
        }
    }

    private data class ProbeFile(val size: Long, val sha: String, val tag: String)
    private fun <T> authorized(permit: Any, block: () -> T): T = operation {
        if (writePermit !== permit) throw Failure(Problem.UNSUPPORTED)
        block()
    }

    fun list(): Map<String, WebDavListing.Entry> = operation {
        val body = PROPFIND.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val request = request(endpoint.collection.toString()).header("Depth", "1")
            .header("Accept", "application/xml, text/xml").method("PROPFIND", body).build()
        exchange(request, 207) { response ->
            WebDavListing.read(endpoint, response.body.byteStream())
        }
    }

    /**
     * Only a fully read, listing-size/ETag/hash-verified body replaces the destination, atomically.
     * A strong listing tag is mandatory. A failed/cancelled/truncated transfer keeps existing bytes.
     * This is a transfer primitive, not permission to bypass NotebookLibrary's version/conflict rules.
     */
    fun download(entry: WebDavListing.Entry, destination: File, expectedSha256: String? = null): Download = operation {
        val uri = endpoint.resource(entry.name)
        val tag = entry.strongEtag ?: throw Failure(Problem.UNSUPPORTED)
        if (!STRONG_ETAG.matches(tag) || entry.size !in 0..MAX_DOWNLOAD) throw Failure(Problem.CONTENT)
        require(expectedSha256 == null || expectedSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid expected checksum" }
        val parent = destination.absoluteFile.parentFile
        if (!Files.isDirectory(parent.toPath(), NOFOLLOW_LINKS) ||
            Files.exists(destination.toPath(), NOFOLLOW_LINKS) && !Files.isRegularFile(destination.toPath(), NOFOLLOW_LINKS)) {
            throw Failure(Problem.STORAGE)
        }
        val request = request(uri.toString()).header("If-Match", tag).get().build()
        exchange(request, 200) { response ->
            if (response.headers.values("ETag").singleOrNull() != tag) throw Failure(Problem.CHANGED)
            val length = response.body.contentLength()
            if (length != -1L && length != entry.size) throw Failure(Problem.CONTENT)
            val temporary = storage { File.createTempFile("notes-dav-", ".part", parent) }
            try {
                var count = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                response.body.byteStream().use { input ->
                    val output = storage { FileOutputStream(temporary) }
                    try {
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkOpen()
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            if (count > entry.size) throw Failure(Problem.CONTENT)
                            digest.update(buffer, 0, read)
                            storage { output.write(buffer, 0, read) }
                        }
                        storage { output.fd.sync() }
                    } finally { storage { output.close() } }
                }
                if (count != entry.size) throw Failure(Problem.CONTENT)
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                if (expectedSha256 != null && sha != expectedSha256) throw Failure(Problem.CHANGED)
                checkOpen()
                storage { Files.move(temporary.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
                Download(count, sha, tag)
            } finally {
                // Only this call's uniquely created temporary file is owned here; never sweep a folder.
                discard(temporary)
            }
        }
    }

    /** Internal opt-in until the adapter owns a durable per-account cache lifecycle.
     * Cache must be a dedicated private slot outside the destination directory's files.
     * No network retry: a later caller resumes only fully committed blocks of this representation.
     */
    internal fun downloadResumable(entry: WebDavListing.Entry, destination: File, cache: File,
                                  accountId: UUID, expectedSha256: String? = null): Download = operation {
        val checkpoint = WebDavDownloadCheckpoint.open(cache, endpoint, accountId, entry, expectedSha256)
        val parent = destination.absoluteFile.parentFile
        storage {
            if (!Files.isDirectory(parent.toPath(), NOFOLLOW_LINKS) ||
                Files.exists(destination.toPath(), NOFOLLOW_LINKS) && !Files.isRegularFile(destination.toPath(), NOFOLLOW_LINKS) ||
                !Files.isDirectory(cache.toPath(), NOFOLLOW_LINKS) ||
                parent.toPath().toRealPath().startsWith(cache.toPath().toRealPath())) throw Failure(Problem.STORAGE)
        }
        val tag = entry.strongEtag!! // Validated by checkpoint.open, including header safety.
        val uri = endpoint.resource(entry.name).toASCIIString()
        checkpoint.session { state ->
            val offset = state.committedBytes
            val complete = offset == entry.size
            val builder = request(uri).header("If-Match", tag)
            if (complete) builder.head() else {
                builder.get()
                if (offset > 0) builder.header("Range", "bytes=$offset-")
            }
            exchange(builder.build(), null) { response ->
                if (response.code != 200 && !(response.code == 206 && offset > 0 && !complete)) {
                    throw Failure(when (response.code) {
                        401, 403 -> Problem.ACCESS
                        404, 409, 412, 416 -> Problem.CHANGED
                        in 300..399, 405, 501 -> Problem.UNSUPPORTED
                        206 -> Problem.CONTENT
                        else -> Problem.NETWORK
                    })
                }
                if (response.headers.values("ETag").singleOrNull() != tag) throw Failure(Problem.CHANGED)
                val ranged = response.code == 206
                val ranges = response.headers.values("Content-Range")
                if (ranged) {
                    if (ranges != listOf("bytes $offset-${entry.size - 1}/${entry.size}")) throw Failure(Problem.CONTENT)
                } else if (ranges.isNotEmpty()) throw Failure(Problem.CONTENT)
                if (response.headers.values("Content-Type").any { it.substringBefore(';').trim().equals("multipart/byteranges", true) })
                    throw Failure(Problem.CONTENT)
                val expectedLength = if (ranged) entry.size - offset else entry.size
                val lengths = response.headers.values("Content-Length")
                if (lengths.isNotEmpty() && lengths != listOf(expectedLength.toString()) || complete && lengths.isEmpty())
                    throw Failure(Problem.CONTENT)
                if (!complete) {
                    if (response.body.contentLength() != -1L && response.body.contentLength() != expectedLength)
                        throw Failure(Problem.CONTENT)
                    // Validate all headers before discarding a prefix when a server ignores Range.
                    if (!ranged && offset > 0) state.reset()
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(WebDavDownloadCheckpoint.BLOCK_BYTES)
                        var remaining = expectedLength
                        while (remaining > 0) {
                            val count = minOf(buffer.size.toLong(), remaining).toInt()
                            var at = 0
                            while (at < count) {
                                checkOpen()
                                val read = input.read(buffer, at, count - at)
                                if (read < 0) throw Failure(Problem.CONTENT)
                                at += read
                            }
                            remaining -= count
                            // EOF must be proven before the final block becomes reusable as complete.
                            if (remaining == 0L) {
                                checkOpen()
                                if (input.read() != -1) throw Failure(Problem.CONTENT)
                            }
                            checkOpen()
                            state.append(buffer, count)
                        }
                    }
                }
            }
            checkOpen()
            val sha = state.completedSha256 ?: throw Failure(Problem.CONTENT)
            val temporary = storage { File.createTempFile("notes-dav-", ".part", parent) }
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                storage {
                    state.completedFile().inputStream().use { input ->
                        FileOutputStream(temporary).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                checkOpen()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read); digest.update(buffer, 0, read)
                            }
                            output.fd.sync()
                        }
                    }
                }
                if (digest.digest().joinToString("") { "%02x".format(it) } != sha) throw Failure(Problem.STORAGE)
                checkOpen()
                storage { Files.move(temporary.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
                Download(entry.size, sha, tag)
            } finally { discard(temporary) }
        }
    }

    /**
     * Upload a private local snapshot to a unique staging name, read it back, then conditionally MOVE.
     * null previousEtag means create-only; replacement requires the last observed strong target tag.
     * UNCERTAIN requires fresh listing/content reconciliation, never an automatic retry or rollback.
     * This primitive neither versions local notebooks nor authorizes deletion/conflict resolution.
     */
    // Internal inactive protocol primitive: do not expose without per-endpoint conformance checks.
    internal fun upload(name: String, source: File, expectedSha256: String, previousEtag: String? = null): Download = operation {
        uploadLocked(name, source, expectedSha256, previousEtag)
    }

    private fun uploadLocked(name: String, source: File, expectedSha256: String, previousEtag: String?, journal: WebDavTransferJournal.Session? = null): Download {
        val destination = endpoint.resource(name).toASCIIString()
        require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid expected checksum" }
        previousEtag?.let { strongTag(it) }
        if (!Files.isRegularFile(source.toPath(), NOFOLLOW_LINKS)) throw Failure(Problem.STORAGE)
        val snapshot = storage { File.createTempFile("notes-dav-upload-", ".part", source.absoluteFile.parentFile) }
        val stageId = UUID.randomUUID()
        val stage = endpoint.stage(stageId).toASCIIString()
        var stageTag: String? = null
        var commitStarted = false
        try {
            val count = snapshot(source, snapshot, expectedSha256)
            journal?.begin(WebDavTransferJournal.Pending(UUID.randomUUID().toString(), WebDavTransferJournal.Kind.UPLOAD,
                name, count, expectedSha256, previousEtag, stageId.toString(), phase = WebDavTransferJournal.Phase.PREPARED))
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = count
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) {
                    storage { snapshot.inputStream() }.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkOpen()
                            val read = storage { input.read(buffer) }
                            if (read < 0) break
                            sink.write(buffer, 0, read)
                        }
                    }
                }
            }
            val putTag = exchange(request(stage).header("If-None-Match", "*").put(body).build(), 201) {
                it.headers.values("ETag").singleOrNull()?.takeIf { value -> STRONG_ETAG.matches(value) }
            }
            // PUT need not return ETag. HEAD supplies a strong tag, then GET verifies actual bytes.
            val candidateTag = head(stage, count)
            if (putTag == candidateTag) stageTag = candidateTag
            verifyRemote(stage, count, candidateTag, expectedSha256)
            stageTag = candidateTag
            journal?.staged(candidateTag)
            checkOpen()
            val move = request(stage).header("Destination", destination)
                .header("Overwrite", if (previousEtag == null) "F" else "T")
                .apply {
                    if (previousEtag == null) header("If-Match", stageTag)
                    else header("If", "<$stage> ([$stageTag]) <$destination> ([$previousEtag])")
                }
                .method("MOVE", null).build()
            journal?.committing()
            commitStarted = true
            exchange(move, if (previousEtag == null) 201 else 204, commit = true) { }
            // MOVE may change tags. Return success only after target read-back verifies the snapshot.
            try {
                val tag = head(destination, count)
                verifyRemote(destination, count, tag, expectedSha256)
                journal?.completed()
                return Download(count, expectedSha256, tag)
            } catch (_: Exception) { throw Failure(Problem.UNCERTAIN) }
        } finally {
            discard(snapshot)
            // Before commit only: conditional cleanup cannot delete another writer's replacement.
            // Unknown PUT ownership/tag, cancellation or ambiguous commit deliberately leaves an orphan.
            if (!commitStarted && stageTag != null && !closed.get()) {
                try { exchange(request(stage).header("If-Match", stageTag).delete().build(), 204) { } }
                catch (_: Exception) { /* Best effort only; never hide the original failure. */ }
            }
        }
    }

    /** Requires repository-level deletion intent. A changed/missing remote resource is not deleted. */
    internal fun delete(entry: WebDavListing.Entry): Unit = operation {
        deleteLocked(entry)
    }

    private fun deleteLocked(entry: WebDavListing.Entry, journal: WebDavTransferJournal.Session? = null) {
        val uri = endpoint.resource(entry.name)
        val tag = strongTag(entry.etag)
        journal?.begin(WebDavTransferJournal.Pending(UUID.randomUUID().toString(), WebDavTransferJournal.Kind.DELETE,
            entry.name, entry.size, previousEtag = tag, phase = WebDavTransferJournal.Phase.COMMITTING))
        exchange(request(uri.toASCIIString()).header("If-Match", tag).delete().build(), 204, commit = true) { }
        if (journal != null) {
            try {
                exchange(request(uri.toASCIIString()).get().build(), 404) { }
                journal.completed()
            } catch (_: Exception) { throw Failure(Problem.UNCERTAIN) }
        }
    }

    private fun strongTag(value: String?): String {
        if (value == null || value.startsWith("W/")) throw Failure(Problem.UNSUPPORTED)
        if (!STRONG_ETAG.matches(value)) throw Failure(Problem.CONTENT)
        return value
    }

    /** Only post-write receipts: Apache can issue weak tags during its one-second freshness window.
     * Observe metadata briefly; never promote a weak tag or repeat a mutation. Callers still verify
     * bytes with a conditional GET before accepting ownership or reporting a committed upload.
     */
    private fun head(uri: String, size: Long): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        repeat(11) { attempt ->
            checkOpen()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw Failure(Problem.UNSUPPORTED)
            val tag = exchange(request(uri).head().build(), 200, receiptDeadline = deadline) { response ->
                if (response.headers.values("Content-Length").singleOrNull()?.toLongOrNull() != size) throw Failure(Problem.CONTENT)
                val value = response.headers.values("ETag").singleOrNull()
                if (value?.startsWith("W/") == true) {
                    // Validate syntax too: malformed weak values do not earn more requests.
                    if (!STRONG_ETAG.matches(value.substring(2))) throw Failure(Problem.CONTENT)
                    null
                } else strongTag(value)
            }
            if (System.nanoTime() >= deadline) throw Failure(Problem.UNSUPPORTED)
            if (tag != null) return tag
            if (attempt == 10) throw Failure(Problem.UNSUPPORTED)
            synchronized(receiptSignal) {
                checkOpen()
                val wait = minOf(TimeUnit.MILLISECONDS.toNanos(200), deadline - System.nanoTime())
                if (wait > 0) try { TimeUnit.NANOSECONDS.timedWait(receiptSignal, wait) }
                catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw Failure(Problem.CANCELLED)
                }
                checkOpen()
            }
        }
        throw Failure(Problem.UNSUPPORTED)
    }

    private fun verifyRemote(uri: String, size: Long, tag: String, sha: String) {
        exchange(request(uri).header("If-Match", tag).get().build(), 200) { response ->
            if (response.headers.values("ETag").singleOrNull() != tag) throw Failure(Problem.CHANGED)
            val length = response.body.contentLength()
            if (length != -1L && length != size) throw Failure(Problem.CONTENT)
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            response.body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkOpen()
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    if (count > size) throw Failure(Problem.CONTENT)
                    digest.update(buffer, 0, read)
                }
            }
            if (count != size) throw Failure(Problem.CONTENT)
            if (digest.digest().joinToString("") { "%02x".format(it) } != sha) throw Failure(Problem.CHANGED)
        }
    }

    private fun snapshot(source: File, target: File, expectedSha: String): Long = storage {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        source.inputStream().use { input -> FileOutputStream(target).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkOpen()
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                if (count > MAX_DOWNLOAD) throw Failure(Problem.CONTENT)
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
            output.fd.sync()
        } }
        if (digest.digest().joinToString("") { "%02x".format(it) } != expectedSha) throw Failure(Problem.CHANGED)
        count
    }

    private fun <T> operation(block: () -> T): T {
        checkOpen()
        if (!operating.compareAndSet(false, true)) throw Failure(Problem.BUSY)
        try { checkOpen(); return block() }
        finally { operating.set(false) }
    }

    private fun request(url: String) = Request.Builder().url(url)
        .tag(Attempt::class.java, Attempt())
        .header("Accept-Encoding", "identity").apply { authorization?.let { header("Authorization", it) } }

    private fun <T> exchange(request: Request, expectedStatus: Int?, commit: Boolean = false, receiptDeadline: Long? = null, receive: (Response) -> T): T {
        checkOpen()
        val call = client.newCall(request)
        if (receiptDeadline != null) {
            val remaining = receiptDeadline - System.nanoTime()
            if (remaining <= 0) throw Failure(Problem.UNSUPPORTED)
            val configured = call.timeout().timeoutNanos()
            call.timeout().timeout(if (configured == 0L) remaining else minOf(configured, remaining), TimeUnit.NANOSECONDS)
        }
        if (!active.compareAndSet(null, call)) throw Failure(Problem.BUSY)
        try {
            checkOpen()
            return call.execute().use { response ->
                checkOpen()
                if (expectedStatus != null && response.code != expectedStatus) throw Failure(when (response.code) {
                    401, 403 -> Problem.ACCESS
                    404, 409, 412 -> Problem.CHANGED
                    in 300..399, 405, 501 -> Problem.UNSUPPORTED
                    else -> if (commit) Problem.UNCERTAIN else Problem.NETWORK
                })
                if (response.headers.values("Content-Encoding").any { !it.equals("identity", true) }) throw Failure(if (commit) Problem.UNCERTAIN else Problem.CONTENT)
                receive(response)
            }
        } catch (error: Failure) {
            if (commit && error.problem in setOf(Problem.CANCELLED, Problem.NETWORK) &&
                request.tag(Attempt::class.java)?.sent?.get() == true) throw Failure(Problem.UNCERTAIN)
            throw error
        } catch (_: IOException) {
            throw Failure(if (commit && request.tag(Attempt::class.java)?.sent?.get() == true) Problem.UNCERTAIN else if (closed.get()) Problem.CANCELLED else Problem.NETWORK)
        } catch (_: IllegalArgumentException) {
            throw Failure(if (commit && request.tag(Attempt::class.java)?.sent?.get() == true) Problem.UNCERTAIN else if (closed.get()) Problem.CANCELLED else Problem.CONTENT)
        } finally { active.compareAndSet(call, null) }
    }

    private fun checkOpen() { if (closed.get()) throw Failure(Problem.CANCELLED) }
    private fun discard(file: File) { try { file.delete() } catch (_: SecurityException) { /* Owned temp only, best effort. */ } }
    private fun <T> storage(block: () -> T): T = try { block() }
        catch (error: Failure) { throw error }
        catch (_: IOException) { throw Failure(Problem.STORAGE) }
        catch (_: SecurityException) { throw Failure(Problem.STORAGE) }

    /** Cancels blocked IO as well as future work. Cancellation is terminal; construct a fresh session to retry. */
    override fun close() {
        closed.set(true)
        synchronized(receiptSignal) { receiptSignal.notifyAll() }
        active.get()?.cancel()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        private const val MAX_DOWNLOAD = 1024L * 1024 * 1024
        // OkHttp's request-header API supports ASCII here. Refuse obs-text tags before building
        // headers rather than exposing its raw IllegalArgumentException (which includes the value).
        private val STRONG_ETAG = Regex("\"[\\x21\\x23-\\x7E]*\"")
        private const val PROPFIND = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getetag/></d:prop></d:propfind>"

        fun open(endpoint: WebDavEndpoint, username: String? = null, password: String? = null): WebDavHttpClient {
            require((username == null) == (password == null)) { "Supply both WebDAV username and password" }
            require(username == null || username.length in 1..1024 && ':' !in username && username.none { it.isISOControl() }) { "Invalid WebDAV username" }
            require(password == null || password.length <= 8192) { "Invalid WebDAV password" }
            val authorization = username?.let { Credentials.basic(it, password!!, Charsets.UTF_8) }
            val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS).build()
            return WebDavHttpClient(endpoint, client, authorization)
        }
    }
}
