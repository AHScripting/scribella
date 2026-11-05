package dev.ownednotes.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * B24 (P140): a package entry compressed once, so a save can copy an unchanged page's bytes instead of encoding and
 * compressing it again. Each entry gets its own Deflater (see NotebookPackage.write for why).
 */
internal class ZipBody(val crc: Long, val size: Int, val compressed: ByteArray, val sha256: String) {
    companion object {
        fun of(bytes: ByteArray, sha256: String, level: Int = Deflater.DEFAULT_COMPRESSION): ZipBody {
            val crc = CRC32().apply { update(bytes) }.value
            val deflater = Deflater(level, true)
            try {
                deflater.setInput(bytes); deflater.finish()
                val out = ByteArrayOutputStream(maxOf(64, bytes.size / 3))
                val buffer = ByteArray(65536)
                while (!deflater.finished()) { val n = deflater.deflate(buffer); out.write(buffer, 0, n) }
                return ZipBody(crc, bytes.size, out.toByteArray(), sha256)
            } finally { deflater.end() }
        }
    }
}

/** Writes a plain ZIP (deflated entries, sizes in the local headers, no data descriptors, no ZIP64) that ZipInputStream reads. */
internal class ZipWriter(private val out: OutputStream) {
    private class Written(val name: ByteArray, val crc: Long, val size: Int, val compressedSize: Int, val offset: Long)
    private val written = ArrayList<Written>()
    private var position = 0L
    private val header = ByteArrayOutputStream(128)

    private fun short(v: Int) { header.write(v and 0xff); header.write(v ushr 8 and 0xff) }
    private fun int(v: Long) { short((v and 0xffff).toInt()); short((v ushr 16 and 0xffff).toInt()) }
    private fun flushHeader() { header.writeTo(out); position += header.size(); header.reset() }

    fun entry(name: String, body: ZipBody) {
        val bytes = name.toByteArray(Charsets.UTF_8)
        require(position + body.compressed.size < 0xffffffffL && written.size < 0xffff) { "Package too large" }
        written += Written(bytes, body.crc, body.size, body.compressed.size, position)
        int(0x04034b50); short(20); short(0x0800); short(8); short(0); short(0x21)
        int(body.crc); int(body.compressed.size.toLong()); int(body.size.toLong()); short(bytes.size); short(0)
        header.write(bytes); flushHeader()
        out.write(body.compressed); position += body.compressed.size
    }

    /** Bounded-memory compression into a temporary file; canonical ZIP headers stay unchanged. */
    fun streamEntry(name: String, expectedSize: Int, writeTo: (OutputStream) -> Unit) {
        val compressed = java.io.File.createTempFile("notebook-zip-", ".pending")
        val deflater = Deflater(Deflater.BEST_SPEED, true)
        try {
            val crc = CRC32(); var size = 0L
            compressed.outputStream().use { file ->
                val encoded = java.util.zip.DeflaterOutputStream(file, deflater, 65536)
                val counted = object: OutputStream() {
                    override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
                    override fun write(bytes: ByteArray, offset: Int, count: Int) {
                        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Notebook save cancelled")
                        require(count >= 0 && size + count <= expectedSize) { "PDF size changed during save" }
                        encoded.write(bytes, offset, count); crc.update(bytes, offset, count); size += count
                    }
                }
                writeTo(counted); require(size == expectedSize.toLong()) { "PDF size changed during save" }
                encoded.finish()
            }
            val bytes = name.toByteArray(Charsets.UTF_8); val length = compressed.length()
            require(length <= Int.MAX_VALUE && position + length < 0xffffffffL && written.size < 0xffff) { "Package too large" }
            written += Written(bytes, crc.value, expectedSize, length.toInt(), position)
            int(0x04034b50); short(20); short(0x0800); short(8); short(0); short(0x21)
            int(crc.value); int(length); int(expectedSize.toLong()); short(bytes.size); short(0)
            header.write(bytes); flushHeader()
            compressed.inputStream().use { it.copyTo(out, 65536) }; position += length
        } finally { deflater.end(); compressed.delete() }
    }

    fun finish() {
        val start = position
        written.forEach { w ->
            int(0x02014b50); short(20); short(20); short(0x0800); short(8); short(0); short(0x21)
            int(w.crc); int(w.compressedSize.toLong()); int(w.size.toLong()); short(w.name.size); short(0); short(0)
            short(0); short(0); int(0); int(w.offset); header.write(w.name); flushHeader()
        }
        val size = position - start
        int(0x06054b50); short(0); short(0); short(written.size); short(written.size); int(size); int(start); short(0); flushHeader()
        out.flush()
    }
}
