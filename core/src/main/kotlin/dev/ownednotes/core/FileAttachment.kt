package dev.ownednotes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID

/** Immutable content-addressed original file of any type (schema 14); its bytes are never interpreted or rewritten. */
class AttachmentAsset private constructor(private val payload: AttachmentPayload) {
    val id: String get() = payload.id
    val size get() = payload.size
    fun openStream(): InputStream = payload.openStream()
    fun writeTo(output: OutputStream) { openStream().use { it.copyTo(output) } }
    internal fun discardUnpublished() { payload.discard() }
    override fun equals(other: Any?) = other is AttachmentAsset && id == other.id && size == other.size
    override fun hashCode() = id.hashCode()
    companion object {
        const val MAX_BYTES = 64 * 1024 * 1024
        const val MAX_FILES = 8
        const val MAX_TOTAL_BYTES = 128 * 1024 * 1024
        internal const val LEGACY_MAX_BYTES = 8 * 1024 * 1024
        internal const val LEGACY_MAX_TOTAL_BYTES = 12 * 1024 * 1024
        /** Caller retains input ownership; ownedDirectory must outlive the asset and its open streams.
         * Android/PC consumers supply a leased workspace so copies outlive native/source streams.
         */
        fun fromStream(input: InputStream,ownedDirectory: File): AttachmentAsset =
            AttachmentAsset(StoredAttachment.read(input,ownedDirectory,MAX_BYTES))
        /** Build an unpublished result; an exception rolls back only this new owned snapshot.
         * The callback must not publish the asset before returning successfully.
         */
        fun <T> fromStream(input: InputStream,ownedDirectory: File,build: (AttachmentAsset)->T): T {
            val asset=fromStream(input,ownedDirectory)
            try { return build(asset) } catch(error: Throwable) { asset.discardUnpublished();throw error }
        }
        fun fromBytes(bytes: ByteArray): AttachmentAsset {
            require(bytes.isNotEmpty()) { "The file is empty" }
            require(bytes.size <= MAX_BYTES) { "Attached files must be at most 64 MiB" }
            return AttachmentAsset(MemoryAttachment(bytes))
        }
    }
}

/**
 * A card on the page that stands for one attached file. The name and MIME type are what the picker reported;
 * they are labels only and never decide how the stored bytes are read.
 */
@Serializable data class FileAttachment(
    val assetId: String,
    val name: String,
    val mimeType: String,
    val placement: ImagePlacement,
    val id: String = UUID.randomUUID().toString(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val locked: Boolean = false,
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Invalid attachment ID" }
        require(assetId.matches(Regex("[a-f0-9]{64}"))) { "Invalid attachment file reference" }
        require(name.length in 1..MAX_NAME && name == name.trim() && name.none { it.isISOControl() || it == '/' || it == '\\' } &&
            name.indices.all { i -> !Character.isSurrogate(name[i]) || (Character.isHighSurrogate(name[i]) && i + 1 < name.length && Character.isLowSurrogate(name[i + 1])) ||
                (Character.isLowSurrogate(name[i]) && i > 0 && Character.isHighSurrogate(name[i - 1])) }) { "Invalid attachment name" }
        require(mimeType.matches(MIME)) { "Invalid attachment type" }
    }
    /** Short upper-case label drawn in the card's icon, from the name's extension (e.g. "PDF", "DOCX"). */
    val label: String get() = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 && it != name && it.all(Char::isLetterOrDigit) }
        ?.uppercase(Locale.ROOT) ?: "FILE"

    companion object {
        const val MAX_NAME = 200
        const val MAX_PER_PAGE = 32
        const val MAX_TOTAL = 128
        const val CARD_WIDTH = 420f
        const val CARD_HEIGHT = 96f
        private val MIME = Regex("[a-z0-9][a-z0-9!#$&^_.+-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}")
        /** Picker names reduced to a safe one-line label; empty or unusable names fall back to "Attachment". */
        fun cleanName(raw: String?): String {
            val cleaned = raw.orEmpty().map { if (it.isISOControl() || it == '/' || it == '\\') ' ' else it }.joinToString("")
                .replace(Regex("\\s+"), " ").trim()
            var cut = cleaned.take(MAX_NAME)
            if (cut.isNotEmpty() && Character.isHighSurrogate(cut.last())) cut = cut.dropLast(1)
            return cut.trim().ifEmpty { "Attachment" }
        }
        fun cleanMimeType(raw: String?): String = raw?.trim()?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim()
            ?.takeIf { it.matches(MIME) } ?: "application/octet-stream"
        /** New cards stack down the page from near the top, centred, wrapping back to the top when the page is full. */
        fun cardPlacement(pageWidth: Float, pageHeight: Float, existing: Int): ImagePlacement {
            val width = minOf(CARD_WIDTH, pageWidth * .9f).coerceAtLeast(1f)
            val height = minOf(CARD_HEIGHT, pageHeight * .9f).coerceAtLeast(1f)
            val top = pageHeight * .06f
            val step = height + 16f
            val rows = maxOf(1, ((pageHeight - top - height) / step).toInt() + 1)
            return ImagePlacement((pageWidth - width) / 2f, top + (existing % rows) * step, width, height)
        }
        /** "2.4 MB", "820 KB", "12 bytes": decimal units, as Android's file managers show them. */
        fun sizeText(bytes: Int): String = when {
            bytes < 1000 -> "$bytes byte${if (bytes == 1) "" else "s"}"
            bytes < 1_000_000 -> "${(bytes + 500) / 1000} KB"
            else -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
        }
    }
}

/** Attached files a page's cards use. */
val Page.attachmentAssetIds: Set<String> get() = attachments.map { it.assetId }.toSet()
