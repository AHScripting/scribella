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
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Original bytes stay in the notebook; only bounded, upright display copies are decoded. */
object ImageSupport {
    data class ImportedPhoto(val asset: ImageAsset, val orientation: Int) {
        fun placement(page: Page): ImagePlacement = ImagePlacement.fit(
            if (orientation >= 5) asset.height else asset.width,
            if (orientation >= 5) asset.width else asset.height, page.width, page.height)
    }
    /** Probes the actual decoder before any repository mutation; originals and alpha remain intact. */
    fun importPhoto(input: InputStream): ImportedPhoto {
        val asset = ImageAsset.fromBytes(stillImageBytes(input.readNBytes(ImageAsset.MAX_BYTES + 1)))
        val orientation = asset.openStream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
            .takeIf { it in 1..8 } ?: 1
        decodeRaw(asset, 128).recycle()
        return ImportedPhoto(asset, orientation)
    }
    /**
     * B34: photo apps hand over HEIC, WebP and other formats. PNG and JPEG keep their original bytes;
     * anything else Android can decode becomes an upright JPEG (the decoder applies its orientation).
     */
    internal fun stillImageBytes(bytes: ByteArray): ByteArray {
        val png = bytes.size >= 8 && bytes[0] == 137.toByte() && bytes[1] == 'P'.code.toByte()
        val jpeg = bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        if (png || jpeg || bytes.size > ImageAsset.MAX_BYTES) return bytes
        val decoded = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val pixels = info.size.width.toLong() * info.size.height
                if (pixels > CONVERTED_PIXELS) {
                    val scale = sqrt(CONVERTED_PIXELS.toDouble() / pixels)
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                }
            }
        }.getOrNull() ?: return bytes
        try {
            val flat = if (decoded.hasAlpha()) Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(decoded, 0f, 0f, null) }
            } else decoded
            try { return ByteArrayOutputStream().also { flat.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray() }
            finally { if (flat !== decoded) flat.recycle() }
        } finally { decoded.recycle() }
    }
    private const val CONVERTED_PIXELS = 24_000_000L

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

    fun render(asset: ImageAsset, background: ImageBackground, longestEdge: Int = 2048): Bitmap {
        require(asset.id == background.assetId && longestEdge in 1..4096) { "Invalid image render request" }
        val decoded = decodeRaw(asset, longestEdge)
        try {
            val sideways = background.orientation >= 5
            val rawWidth = if (sideways) asset.height else asset.width
            val rawHeight = if (sideways) asset.width else asset.height
            val scale = minOf(1f, longestEdge.toFloat() / maxOf(rawWidth, rawHeight))
            val output = Bitmap.createBitmap((rawWidth * scale).roundToInt().coerceAtLeast(1),
                (rawHeight * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            try {
                output.eraseColor(Color.WHITE)
                val t = background.normalizedTransform()
                val w = output.width.toFloat(); val h = output.height.toFloat()
                val matrix = Matrix().apply { setValues(floatArrayOf(
                    t[0] * w / decoded.width, t[2] * w / decoded.height, t[4] * w,
                    t[1] * h / decoded.width, t[3] * h / decoded.height, t[5] * h,
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
