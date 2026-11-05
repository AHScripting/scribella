package dev.ownednotes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

/** Versioned transport only; native notebook schema and existing folder sync are unchanged.
 * Callers supply a stable, validated native snapshot and an exclusively owned blob directory.
 * Blobs contain raw compressed ZIP ranges, never extracted attachment content.
 */
object PackageTransfer {
    const val MAX_PACKAGE_BYTES = 768L * 1024 * 1024
    const val MAX_PARTS = 20_001
    const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
    private val hashPattern = Regex("[a-f0-9]{64}")
    private val json = Json { encodeDefaults = true }
    @Serializable data class Part(val sha256: String, val size: Long)
    @Serializable data class Manifest(val version: Int = 1, val size: Long, val sha256: String, val parts: List<Part>)
    data class Staged(val manifest: Manifest, val newBytes: Long)

    fun encode(manifest: Manifest): ByteArray {
        validate(manifest)
        return json.encodeToString(Manifest.serializer(), manifest).toByteArray(Charsets.UTF_8).also {
            require(it.size <= MAX_MANIFEST_BYTES) { "Transfer manifest too large" }
        }
    }
    fun decode(bytes: ByteArray): Manifest {
        require(bytes.size <= MAX_MANIFEST_BYTES) { "Transfer manifest too large" }
        return json.decodeFromString(Manifest.serializer(), bytes.toString(Charsets.UTF_8)).also(::validate)
    }
    private fun validate(m: Manifest) {
        require(m.version == 1 && m.size in 1..MAX_PACKAGE_BYTES && hashPattern.matches(m.sha256)) { "Invalid transfer manifest" }
        require(m.parts.size in 2..MAX_PARTS) { "Invalid transfer part count" }
        var total = 0L
        val sizes = HashMap<String, Long>()
        m.parts.forEach {
            require(hashPattern.matches(it.sha256) && it.size in 1..MAX_PACKAGE_BYTES) { "Invalid transfer part" }
            require(sizes.put(it.sha256, it.size).let { old -> old == null || old == it.size }) { "Conflicting transfer part sizes" }
            total += it.size
            require(total <= m.size) { "Transfer size mismatch" }
        }
        require(total == m.size) { "Transfer size mismatch" }
    }
    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw InterruptedIOException("Transfer cancelled")
    }
    private fun digest() = MessageDigest.getInstance("SHA-256")
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun copyRange(input: InputStream, out: OutputStream, count: Long, cancelled: () -> Boolean, hash: MessageDigest) {
        var remaining = count
        val buffer = ByteArray(65536)
        while (remaining > 0) {
            checkCancelled(cancelled)
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("Truncated transfer part")
            if (n == 0) throw IOException("Transfer input made no progress")
            out.write(buffer, 0, n); hash.update(buffer, 0, n); remaining -= n
        }
    }
    private fun verified(file: File, part: Part, cancelled: () -> Boolean): Boolean {
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() != part.size) return false
        val hash = digest()
        file.inputStream().use { copyRange(it, object: OutputStream() { override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) {} }, part.size, cancelled, hash) }
        return hex(hash.digest()) == part.sha256
    }
    private fun commit(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    /** Stages immutable ranges. Existing blobs are hashed before reuse; corrupt blobs are repaired.
     * A cancelled/failed stage may leave verified reusable blobs, but publishes no manifest.
     */
    fun stage(source: File, blobs: File, cancelled: () -> Boolean = { false }): Staged {
        require(blobs.isDirectory && !Files.isSymbolicLink(blobs.toPath())) { "Owned blob directory required" }
        require(source.length() in 1..MAX_PACKAGE_BYTES) { "Package size outside transfer budget" }
        val snapshot = File.createTempFile("snapshot-", ".pending", blobs)
        try {
            val whole = digest()
            val length = source.length()
            source.inputStream().use { input -> snapshot.outputStream().use { out ->
                copyRange(input, out, length, cancelled, whole)
                require(input.read() == -1) { "Source changed during snapshot" }
            } }
            val ranges = ranges(snapshot)
            val parts = ArrayList<Part>()
            var newBytes = 0L
            snapshot.inputStream().use { input -> ranges.forEach { size ->
                val temp = File.createTempFile("part-", ".pending", blobs)
                try {
                    val hash = digest()
                    temp.outputStream().use { copyRange(input, it, size, cancelled, hash) }
                    val part = Part(hex(hash.digest()), size)
                    val target = File(blobs, part.sha256)
                    if (!verified(target, part, cancelled)) {
                        checkCancelled(cancelled); commit(temp, target); newBytes += size
                    }
                    parts += part
                } finally { temp.delete() }
            } }
            checkCancelled(cancelled)
            return Staged(Manifest(size = length, sha256 = hex(whole.digest()), parts = parts).also(::validate), newBytes)
        } finally { snapshot.delete() }
    }

    /** Reconstructs into a sibling temporary file. The destination changes only after every
     * blob, total length and complete-package SHA match. Provider publication is a separate layer.
     */
    fun hydrate(manifest: Manifest, destination: File, open: (Part) -> InputStream, cancelled: () -> Boolean = { false }) {
        validate(manifest)
        val temp = File.createTempFile("hydrate-", ".pending", destination.absoluteFile.parentFile)
        try {
            val whole = digest()
            temp.outputStream().use { out -> manifest.parts.forEach { part ->
                val hash = digest()
                open(part).use { input ->
                    val hashingOut = object: OutputStream() {
                        override fun write(b: Int) { out.write(b); whole.update(b.toByte()) }
                        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); whole.update(b, off, len) }
                    }
                    copyRange(input, hashingOut, part.size, cancelled, hash)
                    require(input.read() == -1) { "Transfer part exceeds declared size" }
                }
                require(hex(hash.digest()) == part.sha256) { "Transfer part checksum mismatch" }
            } }
            require(temp.length() == manifest.size && hex(whole.digest()) == manifest.sha256) { "Transfer package checksum mismatch" }
            checkCancelled(cancelled); commit(temp, destination)
        } finally { temp.delete() }
    }

    /** Canonical native ZIP writer layout only (no ZIP64, comments, spanning or descriptors).
     * Unsupported external ZIPs must use the existing complete-package transport.
     */
    private fun ranges(source: File): List<Long> = RandomAccessFile(source, "r").use { f ->
        fun u16(): Int = f.readUnsignedByte() or (f.readUnsignedByte() shl 8)
        fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
        val length = f.length()
        require(length >= 22) { "Invalid native ZIP" }
        f.seek(length - 22)
        require(u32() == 0x06054b50L && u16() == 0 && u16() == 0) { "Unsupported native ZIP footer" }
        val count = u16()
        require(count in 1 until MAX_PARTS && u16() == count) { "Invalid native ZIP count" }
        val centralSize = u32(); val centralStart = u32()
        require(u16() == 0 && centralStart > 0 && centralStart + centralSize == length - 22) { "Invalid native ZIP index" }
        f.seek(centralStart)
        val offsets = ArrayList<Long>()
        repeat(count) {
            require(f.filePointer + 46 <= length - 22 && u32() == 0x02014b50L) { "Invalid native ZIP entry" }
            f.skipBytes(4)
            require(u16() == 0x800 && u16() == 8) { "Unsupported native ZIP flags" }
            f.skipBytes(16)
            val name = u16(); val extra = u16(); val comment = u16()
            require(name > 0 && extra == 0 && comment == 0 && u16() == 0) { "Unsupported native ZIP entry metadata" }
            f.skipBytes(6)
            val offset = u32()
            require(offset < centralStart && f.filePointer + name <= length - 22) { "Invalid native ZIP offset" }
            offsets += offset; f.skipBytes(name)
        }
        require(f.filePointer == length - 22) { "Invalid native ZIP index size" }
        offsets.sort()
        require(offsets.first() == 0L && offsets.distinct().size == count) { "Invalid native ZIP ranges" }
        val boundaries = offsets + centralStart + length
        val sizes = boundaries.zipWithNext { a, b -> b - a }
        offsets.forEachIndexed { index, offset ->
            f.seek(offset)
            require(sizes[index] >= 30 && u32() == 0x04034b50L) { "Invalid native ZIP local entry" }
            f.skipBytes(2)
            require(u16() == 0x800 && u16() == 8) { "Unsupported native ZIP local flags" }
            f.skipBytes(8)
            val compressed = u32(); f.skipBytes(4)
            val name = u16(); val extra = u16()
            require(extra == 0 && name > 0 && 30L + name + compressed == sizes[index]) { "Invalid native ZIP local size" }
        }
        sizes
    }
}
