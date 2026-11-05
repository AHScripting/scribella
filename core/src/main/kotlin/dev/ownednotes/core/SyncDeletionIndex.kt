package dev.ownednotes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** Cloud metadata only: never stores notebook titles, ink, text or attachments. */
object SyncDeletionIndex {
    const val FILE_NAME = ".notes-lab-deletions.json"
    const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAX_ENTRIES = 100_000
    private val idPattern = Regex("[a-zA-Z0-9-]{1,80}")
    private val shaPattern = Regex("[a-f0-9]{64}")
    private val json = Json { encodeDefaults = true }

    @Serializable data class Entry(val revision: Long, val event: String, val deleted: Boolean, val sha256: String? = null)
    @Serializable data class Snapshot(val version: Int, val entries: Map<String, Entry>)
    class InvalidIndex : IOException("The sync deletion index is invalid; existing notebooks were preserved."), FolderSync.ClassifiedFailure {
        override val syncProblemKind = FolderSync.ProblemKind.CONTENT
    }

    fun decode(bytes: ByteArray): Map<String, Entry> {
        if (bytes.size > MAX_BYTES) throw InvalidIndex()
        val snapshot = try { json.decodeFromString(Snapshot.serializer(), bytes.toString(Charsets.UTF_8)) }
        catch (_: IllegalArgumentException) { throw InvalidIndex() }
        if (snapshot.version != 1) throw InvalidIndex()
        validate(snapshot.entries)
        return snapshot.entries
    }

    fun encode(entries: Map<String, Entry>): ByteArray {
        validate(entries)
        return json.encodeToString(Snapshot.serializer(), Snapshot(1, entries.toSortedMap())).toByteArray(Charsets.UTF_8)
            .also { if (it.size > MAX_BYTES) throw InvalidIndex() }
    }

    private fun validate(entries: Map<String, Entry>) {
        if (entries.size > MAX_ENTRIES || entries.any { (id, entry) ->
                !id.matches(idPattern) || entry.revision < 1 || !entry.event.matches(idPattern) ||
                    entry.sha256?.matches(shaPattern) == false || (!entry.deleted && entry.sha256 != null)
            }) throw InvalidIndex()
    }

    /** Deterministic per-notebook merge, including explicit restoration events. */
    fun merge(a: Map<String, Entry>, b: Map<String, Entry>): Map<String, Entry> {
        validate(a); validate(b)
        val result = a.toMutableMap()
        b.forEach { (id, incoming) ->
            val old = result[id]
            if (old != null && old.revision == incoming.revision && old.event == incoming.event && old != incoming)
                throw InvalidIndex()
            if (old == null || compareValuesBy(incoming, old, { it.revision }, { it.event }) > 0) result[id] = incoming
        }
        validate(result)
        return result
    }

    /**
     * A source-bound durable replica repairs lost/stale writes on later syncs. Fresh merge plus
     * readback detects collisions, but Folder has no CAS: this is NOT a linearizable multiwriter store.
     */
    class Session(private val directory: File, private val folder: FolderSync.Folder,
                  listing: Map<String, FolderSync.Stat>, sourceKey: String,
                  private val cancelled: () -> Boolean = { false }, excluded: Set<String> = emptySet()) {
        private val key = FolderSync.sha256(sourceKey.byteInputStream())
        private val shadow = File(directory, ".sync-deletions-$key.json")
        private val legacy = listing.keys.filter { it.endsWith(".deleted") && it.removeSuffix(".deleted").matches(idPattern) && it.removeSuffix(".deleted") !in excluded }
        private var entries: Map<String, Entry>

        init {
            if (Files.exists(shadow.toPath(), NOFOLLOW_LINKS) && !Files.isRegularFile(shadow.toPath(), NOFOLLOW_LINKS)) throw InvalidIndex()
            val local = if (shadow.isFile) shadow.inputStream().use { readBounded(it) } else emptyMap()
            val remote = readRemote(listing)
            entries = merge(local, remote)
            val migrated = legacy.map { it.removeSuffix(".deleted") }.filter { it !in entries }.associateWith { id ->
                Entry(1, FolderSync.sha256("legacy:$id".byteInputStream()), true)
            }
            entries = merge(entries, migrated)
        }

        fun entry(id: String): Entry? = entries[id]
        fun isDeleted(id: String) = entries[id]?.deleted == true

        fun deleted(id: String, sha: String) {
            require(id.matches(idPattern) && sha.matches(shaPattern))
            val old = entries[id]
            if (old?.deleted == true && old.sha256 == sha) return
            change(id, true, sha)
        }

        fun restored(id: String) {
            if (isDeleted(id)) change(id, false, null)
        }

        private fun change(id: String, deleted: Boolean, sha: String?) {
            val revision = try { Math.addExact(entries[id]?.revision ?: 0L, 1L) } catch (_: ArithmeticException) { throw InvalidIndex() }
            entries = entries + (id to Entry(revision, UUID.randomUUID().toString(), deleted, sha))
            // A completed transfer may be followed immediately by cancellation. Retain its
            // restore intent locally even when final cloud publication cannot run this cycle.
            remember()
        }

        private fun checkRunning() { if (cancelled()) throw IOException("Sync stopped") }
        private fun readBounded(stream: java.io.InputStream): Map<String, Entry> {
            val bytes = stream.readNBytes(MAX_BYTES + 1)
            return decode(bytes)
        }
        private fun readRemote(list: Map<String, FolderSync.Stat> = folder.list()): Map<String, Entry> {
            checkRunning()
            val stat = list[FILE_NAME] ?: return emptyMap()
            if (stat.size > MAX_BYTES) throw InvalidIndex()
            return folder.read(FILE_NAME).use { readBounded(it) }
        }

        private fun remember() {
            if (Files.exists(shadow.toPath(), NOFOLLOW_LINKS) && !Files.isRegularFile(shadow.toPath(), NOFOLLOW_LINKS)) throw InvalidIndex()
            val bytes = encode(entries)
            if (shadow.isFile && shadow.length() <= MAX_BYTES && shadow.readBytes().contentEquals(bytes)) return
            val pending = File(directory, ".index-${UUID.randomUUID()}.pending")
            try {
                FileOutputStream(pending).use { out -> out.write(bytes); out.fd.sync() }
                Files.move(pending.toPath(), shadow.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(pending.toPath()) }
        }

        /** Batch publication: preserve the merged replica before changing cloud metadata. */
        fun publish() {
            checkRunning()
            repeat(3) {
                val remote = readRemote()
                entries = merge(entries, remote)
                if (entries.isEmpty()) return
                remember()
                if (remote == entries) return
                val pending = File(directory, ".index-upload-${UUID.randomUUID()}.pending")
                try {
                    pending.writeBytes(encode(entries))
                    folder.write(FILE_NAME, pending)
                } finally { Files.deleteIfExists(pending.toPath()) }
                val observed = readRemote()
                val merged = merge(entries, observed)
                if (merged == observed) { entries = observed; remember(); return }
                entries = merged
            }
            remember()
            throw IOException("The sync deletion index could not be verified; retry sync.")
        }

        /** Delete old cloud markers only after their meaning is in the verified shared index. */
        fun migrateLegacy() {
            if (legacy.isEmpty()) return
            publish()
            val remote = readRemote()
            if (merge(entries, remote) != remote || legacy.any { it.removeSuffix(".deleted") !in remote })
                throw IOException("Deletion marker migration could not be verified")
            for (name in legacy) { checkRunning(); folder.delete(name) }
            if (folder.list().keys.any { it in legacy }) throw IOException("Some old deletion markers are still awaiting removal")
        }

        fun verifyDeleted(ids: Collection<String>) {
            if (ids.isEmpty()) return
            val remote = readRemote()
            if (ids.any { remote[it] != entries[it] || remote[it]?.deleted != true })
                throw IOException("The sync deletion index has not confirmed the removal")
        }
    }
}
