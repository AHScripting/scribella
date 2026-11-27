package dev.ownednotes.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

/** Immutable original static PNG/JPEG. Decoders still probe pixels before user import. */
class ImageAsset private constructor(private val payload: ByteArray, val width: Int, val height: Int,
                                     val extension: String, private val exifRanges: List<IntRange>, val id: String) {
    val size get() = payload.size
    val mimeType get() = if (extension == "png") "image/png" else "image/jpeg"
    fun openStream(): InputStream = ByteArrayInputStream(payload)
    fun writeTo(output: OutputStream) { output.write(payload) }
    /** Browsers must not apply embedded EXIF a second time after our explicit SVG transform. */
    internal fun svgBytes(): ByteArray = ByteArrayOutputStream().also { output ->
        var start = 0
        exifRanges.forEach { range -> output.write(payload, start, range.first - start); start = range.last + 1 }
        output.write(payload, start, payload.size - start)
    }.toByteArray()
    override fun equals(other: Any?) = other is ImageAsset && id == other.id && payload.contentEquals(other.payload)
    override fun hashCode() = id.hashCode()

    companion object {
        // Preserve original compressed bytes; display decoding has a separate bitmap budget.
        const val MAX_BYTES = 64 * 1024 * 1024
        const val MAX_PIXELS = 40_000_000L
        private val pngSignature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        private data class Header(val width: Int, val height: Int, val extension: String, val exif: List<IntRange>)
        /**
         * P714: assets still in use, by checksum. Reading a notebook again (sync's copies, a reopen) shares each picture
         * already held instead of keeping one more copy of its bytes; the payload is private and never changed.
         */
        private class Held(asset: ImageAsset, val key: String, queue: java.lang.ref.ReferenceQueue<ImageAsset>) : java.lang.ref.WeakReference<ImageAsset>(asset, queue)
        private val heldQueue = java.lang.ref.ReferenceQueue<ImageAsset>()
        private val held = HashMap<String, Held>()
        private fun inUse(id: String, bytes: ByteArray): ImageAsset? = synchronized(held) {
            while (true) { val gone = heldQueue.poll() as Held? ?: break; if (held[gone.key] === gone) held.remove(gone.key) }
            held[id]?.get()?.takeIf { it.payload.size == bytes.size && it.payload.contentEquals(bytes) }
        }
        private fun keep(asset: ImageAsset): ImageAsset = synchronized(held) {
            held[asset.id]?.get()?.takeIf { it.payload.contentEquals(asset.payload) }
                ?: asset.also { held[it.id] = Held(it, it.id, heldQueue) }
        }
        /**
         * P715: the picture with checksum [id] while one is still in use, for a reader that has streamed a package entry
         * to exactly this checksum and size: it shares the picture instead of reading its bytes into memory again.
         */
        internal fun inMemory(id: String): ImageAsset? = synchronized(held) {
            while (true) { val gone = heldQueue.poll() as Held? ?: break; if (held[gone.key] === gone) held.remove(gone.key) }
            held[id]?.get()
        }
        fun fromBytes(bytes: ByteArray): ImageAsset {
            require(bytes.size in 12..MAX_BYTES) { "Image must be at most ${MAX_BYTES / (1024 * 1024)} MiB" }
            val id = sha256(bytes)
            inUse(id, bytes)?.let { return it }
            val owned = bytes.copyOf()
            val header = when {
                owned.take(8).toByteArray().contentEquals(pngSignature) -> png(owned)
                u8(owned, 0) == 255 && u8(owned, 1) == 216 -> jpeg(owned)
                else -> throw IllegalArgumentException("Choose a static PNG or JPEG image")
            }
            require(header.width >= 1 && header.height >= 1) { "Image has no width or height" }
            // B75 (P703): a picture that is simply too big says so, with its size (the app scales such photos down first).
            require(header.width <= 100_000 && header.height <= 100_000 && header.width.toLong() * header.height <= MAX_PIXELS) {
                "Image is too large: ${header.width}×${header.height} is ${megapixels(header.width, header.height)} megapixels; at most ${MAX_PIXELS / 1_000_000} megapixels"
            }
            return keep(ImageAsset(owned, header.width, header.height, header.extension, header.exif, id))
        }
        private fun megapixels(width: Int, height: Int) = "%.1f".format(java.util.Locale.ROOT, width.toLong() * height / 1_000_000.0)
        private fun u8(bytes: ByteArray, index: Int) = bytes[index].toInt() and 255
        private fun u16(bytes: ByteArray, index: Int) = u8(bytes, index) * 256 + u8(bytes, index + 1)
        private fun u32(bytes: ByteArray, index: Int): Long = (0..3).fold(0L) { value, i -> value * 256 + u8(bytes, index + i) }
        private fun png(bytes: ByteArray): Header {
            var at = 8; var width = 0; var height = 0; var chunks = 0; var data = false; var end = false
            val exif = mutableListOf<IntRange>()
            while (at < bytes.size) {
                require(++chunks <= 10000 && bytes.size - at >= 12) { "Invalid PNG chunks" }
                val length = u32(bytes, at)
                require(length <= MAX_BYTES && at.toLong() + length + 12 <= bytes.size) { "Truncated PNG chunk" }
                val count = length.toInt()
                val type = String(bytes, at + 4, 4, Charsets.US_ASCII)
                require(type.all { it in 'A'..'Z' || it in 'a'..'z' }) { "Invalid PNG chunk type" }
                require(type !in listOf("acTL", "fcTL", "fdAT")) { "Animated PNG backgrounds are unsupported" }
                require(type[0].isLowerCase() || type in listOf("IHDR", "PLTE", "IDAT", "IEND")) { "Unsupported PNG critical chunk" }
                val crc = CRC32().apply { update(bytes, at + 4, count + 4) }.value
                require(crc == u32(bytes, at + count + 8)) { "PNG checksum mismatch" }
                if (chunks == 1) {
                    require(type == "IHDR" && count == 13) { "Missing PNG dimensions" }
                    val w = u32(bytes, at + 8); val h = u32(bytes, at + 12)
                    require(w <= 100_000 && h <= 100_000) { "Image dimensions too large" }
                    width = w.toInt(); height = h.toInt()
                } else require(type != "IHDR") { "Duplicate PNG header" }
                if (type == "eXIf") exif.add(at until at + count + 12)
                if (type == "IDAT") data = true
                at += count + 12
                if (type == "IEND") { require(count == 0 && at == bytes.size) { "Invalid PNG end" }; end = true; break }
            }
            require(data && end) { "Incomplete PNG image" }
            return Header(width, height, "png", exif)
        }
        private fun jpeg(bytes: ByteArray): Header {
            require(u8(bytes, bytes.size - 2) == 255 && u8(bytes, bytes.size - 1) == 217) { "Incomplete JPEG image" }
            var at = 2; var width = 0; var height = 0; var scan = false
            val exif = mutableListOf<IntRange>()
            while (at < bytes.size - 2) {
                val start = at
                require(u8(bytes, at) == 255) { "Invalid JPEG marker" }
                while (at < bytes.size && u8(bytes, at) == 255) at++
                require(at < bytes.size) { "Truncated JPEG marker" }
                val marker = u8(bytes, at++)
                require(marker !in listOf(0, 216, 217)) { "Unexpected JPEG marker" }
                if (marker == 1 || marker in 208..215) continue
                require(at + 2 <= bytes.size) { "Missing JPEG segment" }
                val length = u16(bytes, at)
                require(length >= 2 && at + length <= bytes.size) { "Invalid JPEG segment length" }
                if (marker in 192..207 && marker !in listOf(196, 200, 204)) {
                    require(marker in listOf(192, 193, 194) && width == 0 && length >= 8 && u8(bytes, at + 2) == 8) { "Unsupported JPEG frame" }
                    val components = u8(bytes, at + 7)
                    require(components in 1..4 && length == 8 + 3 * components) { "Invalid JPEG frame" }
                    height = u16(bytes, at + 3); width = u16(bytes, at + 5)
                }
                if (marker == 225 && length >= 8 && String(bytes, at + 2, 6, Charsets.US_ASCII) == "Exif\u0000\u0000") exif.add(start until at + length)
                at += length
                if (marker == 218) { scan = true; break }
            }
            require(width > 0 && height > 0 && scan) { "Missing JPEG frame or scan" }
            return Header(width, height, "jpg", exif)
        }
    }
}
