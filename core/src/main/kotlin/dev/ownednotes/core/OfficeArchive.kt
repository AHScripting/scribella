package dev.ownednotes.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Read-only OPC container for forthcoming local Office import. Does not render, extract or follow relationships. */
class OfficeArchive private constructor(private val zip: ZipFile, val parts: Set<String>) : Closeable {
    private var delivered = 0L
    private var closed = false

    /** Bounded actual reads; central-directory sizes alone are never trusted. Caller owns returned bytes. */
    @Synchronized fun readPart(name: String, limit: Int = XML_BYTES): ByteArray {
        require(limit in 1..PART_BYTES)
        if (closed) throw IOException("Office package is closed")
        if (name !in parts) throw IOException("An Office document part is missing")
        val entry = zip.getEntry(name) ?: throw IOException("An Office document part is missing")
        if (entry.size > limit || entry.size > EXPANDED_BYTES - delivered)
            throw IOException("Office document part exceeds the import limit")
        val out = ByteArrayOutputStream(minOf(entry.size.toInt(), 65536))
        val crc = CRC32()
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                // Count even rejected work so repeated failed reads cannot bypass the archive budget.
                delivered += count
                if (delivered > EXPANDED_BYTES || out.size().toLong() + count > limit)
                    throw IOException("Office document expands beyond the import limit")
                crc.update(buffer, 0, count); out.write(buffer, 0, count)
            }
        }
        if (out.size().toLong() != entry.size || crc.value != entry.crc)
            throw IOException("Office document part could not be verified")
        return out.toByteArray()
    }

    @Synchronized override fun close() { if (!closed) { closed = true; zip.close() } }

    companion object {
        const val XML_BYTES = 8 * 1024 * 1024
        const val PART_BYTES = 64 * 1024 * 1024
        const val ARCHIVE_BYTES = 128L * 1024 * 1024
        const val EXPANDED_BYTES = 512L * 1024 * 1024
        const val ENTRIES = 10000
        fun open(file: File): OfficeArchive {
            if (!file.isFile || file.length() !in 1..ARCHIVE_BYTES)
                throw IOException("Office file is empty or exceeds the import limit")
            val zip = ZipFile(file)
            try {
                val seen = HashSet<String>(); val parts = LinkedHashSet<String>()
                var expanded = 0L
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement(); val name = entry.name
                    val segments = name.removeSuffix("/").split('/')
                    if (seen.size >= ENTRIES || !seen.add(name) || name.length !in 1..1024 ||
                        name.startsWith('/') || name.contains('\\') || name.contains(':') ||
                        name.any { it.isISOControl() } || segments.any { it.isEmpty() || it == "." || it == ".." })
                        throw IOException("Office package contains an unsafe or duplicate part")
                    if (entry.isDirectory) continue
                    if (entry.method != ZipEntry.STORED && entry.method != ZipEntry.DEFLATED ||
                        entry.size !in 0..PART_BYTES.toLong() || entry.compressedSize < 0 || entry.crc < 0)
                        throw IOException("Office package contains an unsupported part")
                    expanded += entry.size
                    if (expanded > EXPANDED_BYTES) throw IOException("Office package exceeds the expanded import limit")
                    parts.add(name)
                }
                if ("[Content_Types].xml" !in parts || "_rels/.rels" !in parts)
                    throw IOException("This is not an Open XML document package")
                return OfficeArchive(zip, java.util.Collections.unmodifiableSet(parts))
            } catch (error: Throwable) {
                try { zip.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
        }
    }
}
