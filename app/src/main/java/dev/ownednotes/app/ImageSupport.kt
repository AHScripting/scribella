package dev.ownednotes.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import dev.ownednotes.core.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Original bytes stay in the notebook; only bounded, upright display copies are decoded. */
object ImageSupport {
    data class ImportedPhoto(val asset: ImageAsset, val orientation: Int) {
        fun placement(page: Page): ImagePlacement = ImagePlacement.fit(
            if (orientation >= 5) asset.height else asset.width,
            if (orientation >= 5) asset.width else asset.height, page.width, page.height)
    }
    /** Probes the actual decoder before any repository mutation; originals and alpha remain intact. */
    fun importPhoto(input: InputStream): ImportedPhoto {
        val head = input.readNBytes(ImageAsset.MAX_BYTES + 1)
        val bytes = if (head.size <= ImageAsset.MAX_BYTES) stillImageBytes(head) else largeFileBytes(head, input)
        val asset = ImageAsset.fromBytes(bytes)
        val orientation = asset.openStream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
            .takeIf { it in 1..8 } ?: 1
        decodeRaw(asset, 128).recycle()
        return ImportedPhoto(asset, orientation)
    }
    /**
     * B34: photo apps hand over HEIC, WebP and other formats. PNG and JPEG keep their original bytes;
     * anything else Android can decode becomes an upright JPEG (the decoder applies its orientation).
     * B75 (P703): a PNG or JPEG over [ImageAsset.MAX_PIXELS] (a 50 MP phone photo is about 8160x6144) is scaled down
     * the same way instead of being refused; a PNG stays a PNG, so its transparency is kept.
     */
    internal fun stillImageBytes(bytes: ByteArray): ByteArray {
        if (bytes.size > ImageAsset.MAX_BYTES) return bytes
        val png = isPng(bytes)
        val jpeg = bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        if ((png || jpeg) && !oversized(bytes)) return bytes
        return scaledCopy(png) { ImageDecoder.createSource(ByteBuffer.wrap(bytes)) } ?: bytes
    }
    /**
     * B75 (P703): a picture over [ImageAsset.MAX_BYTES] (a big PNG, say) is copied to a cache file and decoded from there
     * as a smaller copy, so it is never held whole in memory. Sources over [MAX_SOURCE_BYTES] are refused.
     */
    private fun largeFileBytes(head: ByteArray, rest: InputStream): ByteArray {
        val file = File.createTempFile("photo-import-", ".tmp")
        try {
            file.outputStream().use { out ->
                out.write(head)
                var total = head.size.toLong()
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = rest.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_SOURCE_BYTES) { "Image must be at most ${MAX_SOURCE_BYTES / (1024 * 1024)} MiB" }
                    out.write(buffer, 0, read)
                }
            }
            return scaledCopy(isPng(head)) { ImageDecoder.createSource(file) } ?: throw IllegalArgumentException("This picture cannot be read")
        } finally { file.delete() }
    }
    /**
     * An upright copy of at most [ImageAsset.MAX_PIXELS] that fits [ImageAsset.MAX_BYTES]. B80 (P707): it is shrunk by
     * whole halves (the decoder's sample size), so a 50 MP 8160x6144 photo becomes exactly 4080x3072 and the decoder
     * never reads the full picture; it halves again while the encoded copy is still too big (a PNG of a busy photo can be).
     * Null when Android cannot decode the source.
     */
    private fun scaledCopy(png: Boolean, source: () -> ImageDecoder.Source): ByteArray? {
        var sample = 0
        repeat(4) {
            val decoded = runCatching {
                ImageDecoder.decodeBitmap(source()) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    sample = if (sample == 0) sampleSize(info.size.width, info.size.height) else sample
                    if (sample > 1) decoder.setTargetSampleSize(sample)
                }
            }.getOrNull() ?: return null
            val encoded = try { encode(decoded, png) } finally { decoded.recycle() }
            if (encoded != null) return encoded
            sample *= 2
        }
        return null
    }
    /** The smallest power of two that brings a picture within [ImageAsset.MAX_PIXELS] (sides rounded up, as decoders do). */
    internal fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (((width + sample - 1) / sample).toLong() * ((height + sample - 1) / sample) > ImageAsset.MAX_PIXELS) sample *= 2
        return sample
    }
    /** The encoded copy, or null as soon as it grows past [ImageAsset.MAX_BYTES] (so a too-big copy never fills memory). */
    private fun encode(decoded: Bitmap, png: Boolean): ByteArray? {
        val out = CappedOutput()
        if (png) return out.takeIf { decoded.compress(Bitmap.CompressFormat.PNG, 100, it) && !it.over }?.toByteArray()
        val flat = if (decoded.hasAlpha()) Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(decoded, 0f, 0f, null) }
        } else decoded
        try { return out.takeIf { flat.compress(Bitmap.CompressFormat.JPEG, 92, it) && !it.over }?.toByteArray() }
        finally { if (flat !== decoded) flat.recycle() }
    }
    private class CappedOutput : ByteArrayOutputStream(1 shl 20) {
        var over = false
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (over || count + len > ImageAsset.MAX_BYTES) { over = true; throw java.io.IOException("Image copy is over the size limit") }
            super.write(b, off, len)
        }
    }
    private fun isPng(bytes: ByteArray) = bytes.size >= 8 && bytes[0] == 137.toByte() && bytes[1] == 'P'.code.toByte()
    private const val MAX_SOURCE_BYTES = 512L * 1024 * 1024
    /** Whether a PNG or JPEG is over the pixels a notebook keeps (reads only its header). */
    private fun oversized(bytes: ByteArray): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight > ImageAsset.MAX_PIXELS
    }

    fun import(input: InputStream, title: String = "Inserted image"): Notebook {
        val imported = importPhoto(input)
        val asset = imported.asset
        val orientation = imported.orientation
        val background = ImageBackground(asset.id, orientation)
        // Probe the actual Android decoder before accepting any notebook mutation.
        render(asset, background, 128).recycle()
        val sideways = orientation >= 5
        val width = if (sideways) asset.height else asset.width
        val height = if (sideways) asset.width else asset.height
        val scale = 1400f / maxOf(width, height)
        require(minOf(width, height) * scale >= 1f) { "Image aspect ratio is too extreme" }
        return Notebook(title = title.take(500), pages = listOf(Page(width = width * scale, height = height * scale,
            imageBackground = background)), imageAssets = listOf(asset)).also { it.validate() }
    }

    /** Raw sampled pixels, with alpha intact. Callers own and eventually recycle the returned bitmap. */
    fun decodeRaw(asset: ImageAsset, longestEdge: Int = 2048): Bitmap {
        require(longestEdge in 1..4096) { "Invalid image decode size" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        asset.openStream().use { BitmapFactory.decodeStream(it, null, options) }
        require(options.outWidth == asset.width && options.outHeight == asset.height) { "Image dimensions do not match decoder" }
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        options.inSampleSize = 1
        while (maxOf(asset.width, asset.height) / options.inSampleSize > longestEdge * 2) options.inSampleSize *= 2
        val decoded = asset.openStream().use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Image cannot be decoded")
        if (maxOf(decoded.width, decoded.height) <= longestEdge) return decoded
        try {
            val scale = longestEdge.toFloat() / maxOf(decoded.width, decoded.height)
            return Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt().coerceAtLeast(1),
                (decoded.height * scale).roundToInt().coerceAtLeast(1), true)
        } finally { decoded.recycle() }
    }

    /** The page's picture, upright and (B77) cut to its crop; [longestEdge] bounds the returned bitmap. */
    fun render(asset: ImageAsset, background: ImageBackground, longestEdge: Int = 2048): Bitmap {
        require(asset.id == background.assetId && longestEdge in 1..4096) { "Invalid image render request" }
        val sideways = background.orientation >= 5
        val rawWidth = if (sideways) asset.height else asset.width
        val rawHeight = if (sideways) asset.width else asset.height
        val crop = background.crop
        val shownWidth = rawWidth * crop.width; val shownHeight = rawHeight * crop.height
        val scale = minOf(1f, longestEdge.toFloat() / maxOf(shownWidth, shownHeight))
        // A crop needs the whole photo decoded at the crop's scale, so a small crop stays sharp.
        val decoded = decodeRaw(asset, kotlin.math.ceil(maxOf(rawWidth, rawHeight) * scale).toInt().coerceIn(1, 4096))
        try {
            val output = Bitmap.createBitmap((shownWidth * scale).roundToInt().coerceAtLeast(1),
                (shownHeight * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            try {
                output.eraseColor(Color.WHITE)
                val t = background.normalizedTransform()
                val w = output.width / crop.width; val h = output.height / crop.height
                val matrix = Matrix().apply { setValues(floatArrayOf(
                    t[0] * w / decoded.width, t[2] * w / decoded.height, (t[4] - crop.left) * w,
                    t[1] * h / decoded.width, t[3] * h / decoded.height, (t[5] - crop.top) * h,
                    0f, 0f, 1f)) }
                Canvas(output).drawBitmap(decoded, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                output.setHasAlpha(false)
                return output
            } catch (error: Throwable) { output.recycle(); throw error }
        } finally { decoded.recycle() }
    }

    internal fun draw(asset: ImageAsset, background: ImageBackground, target: Bitmap) {
        val bitmap = render(asset, background, maxOf(target.width, target.height).coerceAtMost(4096))
        try { Canvas(target).drawBitmap(bitmap, null, Rect(0, 0, target.width, target.height), Paint(Paint.FILTER_BITMAP_FLAG)) }
        finally { bitmap.recycle() }
    }
}
