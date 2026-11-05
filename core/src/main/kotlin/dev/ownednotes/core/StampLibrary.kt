package dev.ownednotes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/**
 * P130 (F109): a reusable piece of handwriting. [strokes] keep their page coordinates and [centre] is the middle of
 * their selection box, exactly as the ink clipboard holds them, so inserting a stamp is an ordinary paste.
 * Layer and lock belong to the page the ink came from and are never stored; groups are kept.
 */
@Serializable data class StampRecord(val id: String = UUID.randomUUID().toString(), val name: String, val centre: Point, val strokes: List<InkStroke>) {
    fun validate() {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Invalid stamp id" }
        require(name.length in 1..MAX_NAME && name == name.trim() && name.none { it.isISOControl() }) { "Stamp names must be 1–$MAX_NAME characters" }
        require(centre.x.isFinite() && centre.y.isFinite()) { "Invalid stamp position" }
        require(strokes.size in 1..MAX_STROKES) { "A stamp holds 1–$MAX_STROKES strokes" }
        require(strokes.all { it.layer == null && !it.locked }) { "Stamps carry no layer or lock" }
        // The same stroke rules as a notebook page, on a page large enough for any page coordinates.
        Notebook(id = "stamp", title = name, pages = listOf(Page(id = "stamp", width = 100_000f, height = 100_000f, strokes = strokes))).validate()
    }
    companion object {
        const val MAX_NAME = 80
        const val MAX_STROKES = 2000
        /** Strip what only makes sense on the source page. */
        fun of(name: String, strokes: List<InkStroke>, centre: Point) =
            StampRecord(name = name.trim(), centre = centre, strokes = strokes.map { it.copy(layer = null, locked = false) }).also { it.validate() }
    }
}

@Serializable private data class StampFile(val format: String = FORMAT, val schemaVersion: Int = 1, val stamps: List<StampRecord> = emptyList()) {
    companion object { const val FORMAT = "owned-notes-stamps" }
}

/** One application-owned instance serializes access, like [StickerLibrary]. Corrupt storage is never treated as empty. */
class StampLibrary(private val directory: File) {
    private val destination get() = File(directory, "library.inkstamps")
    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = false }

    @Synchronized fun read(): List<StampRecord> {
        val file = destination
        if (!file.exists()) return emptyList()
        require(file.length() <= MAX_BYTES) { "Stamp library is too large" }
        val stored = json.decodeFromString<StampFile>(file.readText(Charsets.UTF_8))
        require(stored.format == StampFile.FORMAT && stored.schemaVersion == 1) { "Unsupported stamp library" }
        validate(stored.stamps)
        return stored.stamps
    }

    @Synchronized fun add(name: String, strokes: List<InkStroke>, centre: Point): StampRecord {
        val record = StampRecord.of(name, strokes, centre)
        commit(read() + record)
        return record
    }

    @Synchronized fun rename(id: String, name: String): Boolean {
        val current = read()
        val old = current.firstOrNull { it.id == id } ?: return false
        val replacement = old.copy(name = name.trim())
        if (replacement != old) commit(current.map { if (it.id == id) replacement else it })
        return true
    }

    @Synchronized fun remove(id: String): Boolean {
        val current = read()
        val remaining = current.filterNot { it.id == id }
        if (remaining.size == current.size) return false
        commit(remaining)
        return true
    }

    private fun validate(stamps: List<StampRecord>) {
        require(stamps.size <= MAX_STAMPS && stamps.map { it.id }.distinct().size == stamps.size) { "At most $MAX_STAMPS stamps" }
        stamps.forEach { it.validate() }
    }

    private fun commit(stamps: List<StampRecord>) {
        validate(stamps)
        val bytes = json.encodeToString(StampFile(stamps = stamps)).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Stamp library is full" }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create stamp library directory")
        val pending = File(directory, ".stamps-${UUID.randomUUID()}.pending")
        try {
            FileOutputStream(pending).use { stream -> stream.write(bytes); stream.fd.sync() }
            Files.move(pending.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(pending.toPath())
        }
    }

    companion object {
        const val MAX_STAMPS = 128
        const val MAX_BYTES = 32L * 1024 * 1024
    }
}
