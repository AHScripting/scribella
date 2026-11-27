package dev.ownednotes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * P694 (C1, docs/PHONE-CAPTURE-PLAN.md): a page drop. A phone that scans pages for a synced notebook does not edit the
 * notebook: it leaves the finished JPEGs in the sync folder as `drops/<notebook id>_<drop id>.scandrop`, a zip of the
 * images and a `drop.json`, and another device that has the notebook adds them as one edit. Only one device writes the
 * notebook, so no conflict copy appears. The notebook format does not change; FolderSync never looks inside `drops/`.
 *
 * Reading checks everything before a page is made: only the expected entries, sizes within limits, each image's checksum.
 */
object PageDrop {
    const val FOLDER = "drops"
    const val EXTENSION = ".scandrop"
    const val MANIFEST = "drop.json"
    const val VERSION = 1
    const val MAX_IMAGES = 50
    const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
    const val MAX_BYTES = 200L * 1024 * 1024
    private const val MAX_MANIFEST = 64 * 1024
    /** New pages after [Manifest.pageId] (at the end when null or gone). */
    const val PAGES = "pages"
    /** Pictures on page [Manifest.pageId] (new pages at the end when that page is gone). */
    const val PICTURES = "pictures"
    private val ID = Regex("[a-zA-Z0-9-]{1,80}")
    private val IMAGE = Regex("([1-9][0-9]{0,2})\\.jpg")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable data class Image(val name: String, val size: Long, val sha256: String)
    /**
     * [sender] is the sending device's name, shown in the arrival notice; [senderId] is its install, so the sender never
     * applies its own drop. [pageNumber] is the page's number on the sender when it chose the place, for its own view.
     */
    @Serializable data class Manifest(
        val version: Int = VERSION, val id: String, val notebookId: String, val kind: String = PAGES,
        val pageId: String? = null, val pageNumber: Int? = null,
        val sender: String, val senderId: String, val time: Long, val images: List<Image> = emptyList(),
    ) {
        val fileName get() = name(notebookId, id)
    }

    fun name(notebookId: String, dropId: String): String {
        require(notebookId.matches(ID) && dropId.matches(ID)) { "Invalid drop name" }
        return "${notebookId}_$dropId$EXTENSION"
    }
    /** The notebook and drop ids in a drop's file name, or null when it is not one. */
    fun parse(name: String): Pair<String, String>? {
        if (!name.endsWith(EXTENSION)) return null
        val parts = name.removeSuffix(EXTENSION).split('_')
        return if (parts.size == 2 && parts.all { it.matches(ID) }) parts[0] to parts[1] else null
    }
    /** The id a dropped page (or picture) gets, the same on every device, so a drop can never add it twice. */
    fun pageId(dropId: String, index: Int): String = UUID.nameUUIDFromBytes("scandrop/$dropId/$index".toByteArray()).toString()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun jpeg(bytes: ByteArray) = bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

    /** Writes [images] (finished JPEGs, in page order) with [manifest] into [target]; returns the manifest as written. */
    fun write(target: File, manifest: Manifest, images: List<ByteArray>): Manifest {
        require(images.size in 1..MAX_IMAGES) { "A drop holds 1 to $MAX_IMAGES pages" }
        require(images.all { jpeg(it) && it.size <= MAX_IMAGE_BYTES }) { "Each page must be a JPEG of at most ${MAX_IMAGE_BYTES / (1024 * 1024)} MB" }
        require(images.sumOf { it.size.toLong() } <= MAX_BYTES) { "A drop can hold at most ${MAX_BYTES / (1024 * 1024)} MB" }
        val written = manifest.copy(version = VERSION, images = images.mapIndexed { i, b -> Image("${i + 1}.jpg", b.size.toLong(), sha256(b)) })
        check(written)
        FileOutputStream(target).use { file ->
            ZipOutputStream(file).use { zip ->
                fun stored(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name).apply {
                        method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                        crc = CRC32().apply { update(bytes) }.value
                    })
                    zip.write(bytes); zip.closeEntry()
                }
                // The manifest first, so a reader learns the limits before any image arrives.
                stored(MANIFEST, json.encodeToString(Manifest.serializer(), written).toByteArray())
                images.forEachIndexed { i, b -> stored("${i + 1}.jpg", b) }
                zip.finish(); zip.flush(); file.fd.sync()
            }
        }
        return written
    }

    private fun check(m: Manifest) {
        require(m.version == VERSION) { "This drop was made by a newer version" }
        require(m.id.matches(ID) && m.notebookId.matches(ID) && m.senderId.matches(ID)) { "Invalid drop identity" }
        require(m.kind == PAGES || m.kind == PICTURES) { "Unknown drop kind" }
        require(m.pageId == null || m.pageId.matches(ID)) { "Invalid drop page" }
        require(m.sender.length in 1..200) { "Invalid sender name" }
        require(m.images.size in 1..MAX_IMAGES) { "A drop holds 1 to $MAX_IMAGES pages" }
        require(m.images.mapIndexed { i, image -> image.name == "${i + 1}.jpg" && image.size in 4..MAX_IMAGE_BYTES && image.sha256.matches(Regex("[0-9a-f]{64}")) }.all { it }) { "Invalid drop images" }
        require(m.images.sumOf { it.size } <= MAX_BYTES) { "Drop too large" }
    }

    /**
     * Reads a drop from [input] into the empty folder [into] (images as `1.jpg`, `2.jpg`, …) and returns its manifest.
     * A drop cut short, changed, oversized or holding anything else is refused with nothing left in [into].
     */
    fun read(input: InputStream, into: File): Manifest {
        require(into.isDirectory && into.listFiles().isNullOrEmpty()) { "Drop folder must be empty" }
        try {
            var manifest: Manifest? = null
            val seen = HashSet<String>(); var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    require(seen.add(name) && seen.size <= MAX_IMAGES + 1) { "Unexpected drop entry" }
                    if (name == MANIFEST) {
                        val bytes = zip.readNBytes(MAX_MANIFEST + 1)
                        require(bytes.size <= MAX_MANIFEST) { "Drop description too large" }
                        manifest = runCatching { json.decodeFromString(Manifest.serializer(), bytes.decodeToString()) }
                            .getOrElse { throw IllegalArgumentException("Unreadable drop description") }.also { check(it) }
                        continue
                    }
                    val m = requireNotNull(manifest) { "Drop description missing" }
                    require(IMAGE.matches(name)) { "Unexpected drop entry" }
                    val expected = m.images.firstOrNull { it.name == name } ?: throw IllegalArgumentException("Unexpected drop image")
                    val digest = MessageDigest.getInstance("SHA-256"); var size = 0L
                    FileOutputStream(File(into, name)).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buffer); if (n < 0) break
                            size += n; total += n
                            require(size <= expected.size && total <= MAX_BYTES) { "Drop image larger than described" }
                            digest.update(buffer, 0, n); out.write(buffer, 0, n)
                        }
                    }
                    require(size == expected.size && digest.digest().joinToString("") { "%02x".format(it) } == expected.sha256) { "Drop image damaged" }
                }
            }
            val m = requireNotNull(manifest) { "Drop description missing" }
            require(m.images.all { it.name in seen }) { "Drop cut short" }
            require(m.images.all { jpeg(File(into, it.name).inputStream().use { s -> s.readNBytes(4) }) }) { "Drop pages must be JPEGs" }
            return m
        } catch (e: Throwable) {
            into.listFiles().orEmpty().forEach { it.delete() }
            throw when (e) {
                is ZipException, is java.io.EOFException -> IllegalArgumentException("Drop damaged or cut short", e)
                else -> e
            }
        }
    }

    /** The drops folder inside the sync folder; null when it does not exist yet and [create] is false. */
    fun folder(root: FolderSync.NestedFolder, create: Boolean): FolderSync.NestedFolder? =
        if (create) root.child(FOLDER, true) else try { root.child(FOLDER, false) } catch (_: IOException) { null }

    /** Every drop waiting in the sync folder, by file name. */
    fun pending(root: FolderSync.NestedFolder): Map<String, FolderSync.Stat> =
        folder(root, false)?.list().orEmpty().filterKeys { parse(it) != null }

    /** Puts [file] (written by [write]) into the sync folder and checks the copy there. */
    fun send(root: FolderSync.NestedFolder, manifest: Manifest, file: File) {
        val drops = folder(root, true)!!
        val name = manifest.fileName
        val sha = FolderSync.sha256(file.inputStream())
        drops.write(name, file)
        if (FolderSync.sha256(drops.read(name)) != sha) throw IOException("The drop in the sync folder differs from the one sent")
    }

    fun open(root: FolderSync.NestedFolder, name: String): InputStream {
        requireNotNull(parse(name)) { "Not a drop" }
        return (folder(root, false) ?: throw java.io.FileNotFoundException(name)).read(name)
    }

    fun delete(root: FolderSync.NestedFolder, name: String) {
        requireNotNull(parse(name)) { "Not a drop" }
        folder(root, false)?.delete(name)
    }

    /**
     * The drops this device has added, with when, so a drop is added once even after its pages were deleted here, and is
     * removed from the folder only once the notebook saved after that time has gone up. One line per drop, newest last.
     */
    class Applied(private val file: File) {
        data class Entry(val dropId: String, val notebookId: String, val time: Long)
        @Synchronized fun entries(): List<Entry> = if (!file.isFile) emptyList() else file.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size == 3 && p[0].matches(ID) && p[1].matches(ID)) p[2].toLongOrNull()?.let { Entry(p[0], p[1], it) } else null
        }
        @Synchronized operator fun contains(dropId: String) = entries().any { it.dropId == dropId }
        @Synchronized fun add(dropId: String, notebookId: String, time: Long) {
            val kept = (entries().filterNot { it.dropId == dropId } + Entry(dropId, notebookId, time)).takeLast(KEEP)
            file.parentFile?.mkdirs()
            val pending = File(file.parentFile, ".${file.name}.pending")
            pending.writeText(kept.joinToString("") { "${it.dropId}\t${it.notebookId}\t${it.time}\n" })
            if (!pending.renameTo(file)) { file.delete(); if (!pending.renameTo(file)) throw IOException("Cannot save the list of added drops") }
        }
        private companion object { const val KEEP = 2000 }
    }
}
