package dev.ownednotes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

@Serializable data class InkTextBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init { require(listOf(left, top, right, bottom).all { it.isFinite() && kotlin.math.abs(it) <= 1_000_001f } && right > left && bottom > top) { "Invalid handwriting text bounds" } }
}

@Serializable enum class ReviewedInkInput { RAW, VISIBLE_CENTERLINE_V1 }

/** User-reviewed transcription, not a claim of recognition accuracy or a signed approval. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class ReviewedInk(val id: String = UUID.randomUUID().toString(), val strokeIds: List<String>,
    val sourceHash: String, val text: String, val language: String, val bounds: InkTextBounds,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val inputMode: ReviewedInkInput = ReviewedInkInput.RAW) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")) && strokeIds.size in 1..HandwritingInput.MAX_STROKES &&
            strokeIds.distinct().size == strokeIds.size && strokeIds.all { it.matches(Regex("[a-zA-Z0-9-]{1,80}")) }) { "Invalid reviewed ink identities" }
        require(sourceHash.matches(Regex("[a-f0-9]{64}"))) { "Invalid reviewed ink fingerprint" }
        require(text.isNotBlank() && text.length <= 4096 && text.count { it == '\n' } < 32 &&
            text.none { it.isISOControl() && it != '\n' && it != '\t' }) { "Reviewed text must contain 1–4096 characters and at most32 lines" }
        require(language in setOf("en-US", "de-DE")) { "Unsupported handwriting language" }
    }
    fun matches(page: Page): Boolean = fingerprint(page, strokeIds, inputMode) == sourceHash

    companion object {
        const val MAX_PER_PAGE = 128
        private val json = Json { encodeDefaults = true }
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        internal fun fingerprint(page: Page, ids: List<String>, mode: ReviewedInkInput = ReviewedInkInput.RAW): String? {
            val visible = page.drawnStrokes.associateBy { it.id }
            val strokes = ids.map { visible[it] ?: return null }
            if (mode == ReviewedInkInput.RAW && strokes.any { it.erasures.isNotEmpty() }) return null
            return hash(json.encodeToString(strokes).toByteArray(Charsets.UTF_8))
        }
        internal fun create(notebook: Notebook, input: HandwritingInput, text: String, language: String): ReviewedInk {
            require(input.matches(notebook, input.pageId)) { "Handwriting changed. Read it again before saving." }
            val page = notebook.pages.first { it.id == input.pageId }
            val strokes = page.strokes.filter { it.id in input.strokeIds }
            val mode = if (strokes.any { it.erasures.isNotEmpty() }) ReviewedInkInput.VISIBLE_CENTERLINE_V1 else ReviewedInkInput.RAW
            return ReviewedInk(strokeIds = input.strokeIds, sourceHash = requireNotNull(fingerprint(page, input.strokeIds, mode)),
                text = text, language = language, bounds = input.bounds, inputMode = mode)
        }
    }
}

/** Whole-page copy keeps only active reviews, remapped to freshly copied stroke identities. */
internal fun Page.withCopiedReviews(source: Page): Page {
    val mapping = source.strokes.zip(strokes).associate { (old, fresh) -> old.id to fresh.id }
    val records = source.activeReviewedInk.map { record ->
        val ids = record.strokeIds.map { mapping.getValue(it) }
        record.copy(id = UUID.randomUUID().toString(), strokeIds = ids, sourceHash = requireNotNull(ReviewedInk.fingerprint(this, ids, record.inputMode)))
    }
    return copy(reviewedInk = records)
}
