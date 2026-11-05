package dev.ownednotes.app

import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.ownednotes.core.*

/**
 * P090 (F117): text on PDF and image pages, read from the rendered page with ML Kit's bundled
 * on-device recognizer (no model download, no network). Runs on NotesApplication.io only.
 */
internal object PageText {
    /** One recognised word: its box in page coordinates and its place [start, end) in [Result.text] (P096, F123). */
    data class Word(val text: String, val box: RectF, val start: Int, val end: Int)
    /** One recognised line, with its box in page coordinates. */
    data class Line(val text: String, val box: RectF, val words: List<Word> = emptyList())
    class Result(val lines: List<Line>, val blocks: List<String>) {
        val text: String get() = blocks.joinToString("\n\n")
    }

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    /** P106 (F142): read text kept across restarts; set by NotesApplication. Memory caches stay the fast path. */
    @Volatile var index: SearchIndex? = null
    // Keyed by the page background and size, so the same PDF page in another notebook is read once.
    private fun lineBytes(line: Line): Long = 96L + line.text.length * 2L +
        line.words.sumOf { 80L + it.text.length * 2L }
    private val cache = RecognitionCache<Any, Result>(8 * 1024 * 1024) { result ->
        96L + result.blocks.sumOf { 32L + it.length * 2L } + result.lines.sumOf(::lineBytes)
    }

    /** For tests: forgets what is held in memory, as after a restart. */
    internal fun clearMemory() { PdfBookText.clearMemory(); synchronized(cache) { cache.clear() }; synchronized(photos) { photos.clear() } }

    fun cached(page: Page): Result? = key(page)?.let { key ->
        synchronized(cache) { cache[key] } ?: index?.get(diskKey(key))?.let { data ->
            runCatching { Result(SearchIndex.lines(data.getJSONArray("lines")), SearchIndex.strings(data.getJSONArray("blocks"))) }.getOrNull()
        }?.also { synchronized(cache) { cache[key] = it } }
    }

    private fun diskKey(key: Key) = when (val b = key.background) {
        is PdfBackground -> "pdf|${b.assetId}|${b.pageIndex}|${key.width}|${key.height}"
        is ImageBackground -> "image|${b.assetId}|${b.orientation}|${key.width}|${key.height}"
        else -> error("Unsupported page background")
    }

    /** The PDF or image behind [page], rendered for reading. The caller owns the bitmap. */
    fun pageBitmap(app: NotesApplication, notebook: Notebook, page: Page): android.graphics.Bitmap =
        when (val background = requireNotNull(key(page)) { "This page has no PDF or image to read" }.background) {
            is PdfBackground -> PdfSupport.render(app.cacheDir, notebook.assets.first { it.id == background.assetId }, background)
            is ImageBackground -> ImageSupport.render(notebook.imageAssets.first { it.id == background.assetId }, background)
            else -> error("Unsupported page background")
        }

    fun read(app: NotesApplication, notebook: Notebook, page: Page): Result {
        val key = requireNotNull(key(page)) { "This page has no PDF or image to read" }
        cached(page)?.let { return it }
        val bitmap = pageBitmap(app, notebook, page)
        val result = try {
            val scaleX = page.width / bitmap.width; val scaleY = page.height / bitmap.height
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
            fun scaled(box: android.graphics.Rect) = RectF(box.left * scaleX, box.top * scaleY, box.right * scaleX, box.bottom * scaleY)
            // Offsets follow Result.text exactly: lines joined by one newline, blocks by a blank line.
            val lines = mutableListOf<Line>(); val blocks = mutableListOf<String>(); var offset = 0
            text.textBlocks.forEach { block ->
                val blockText = block.lines.joinToString("\n") { it.text }
                if (blockText.isBlank()) return@forEach
                if (blocks.isNotEmpty()) offset += 2
                block.lines.forEachIndexed { index, line ->
                    if (index > 0) offset += 1
                    line.boundingBox?.let { box ->
                        var at = 0
                        val words = line.elements.mapNotNull { element ->
                            val found = line.text.indexOf(element.text, at).takeIf { it >= 0 } ?: return@mapNotNull null
                            at = found + element.text.length
                            element.boundingBox?.let { Word(element.text, scaled(it), offset + found, offset + at) }
                        }
                        lines += Line(line.text, scaled(box), words)
                    }
                    offset += line.text.length
                }
                blocks += blockText
            }
            Result(lines, blocks)
        } finally { bitmap.recycle() }
        synchronized(cache) { cache[key] = result }
        index?.put(diskKey(key), org.json.JSONObject().put("lines", SearchIndex.lines(result.lines)).put("blocks", SearchIndex.strings(result.blocks)))
        return result
    }

    private data class Key(val background: Any, val width: Float, val height: Float)
    private fun key(page: Page): Key? = (page.pdfBackground ?: page.imageBackground)?.let { Key(it, page.width, page.height) }

    /** P095 (F148): a photo's recognised blocks, each a list of lines boxed in the upright image (0..1), before cropping. */
    private class Photo(val blocks: List<List<Line>>)
    // Keyed by image and orientation, so the same photo placed twice (or cropped differently) is read once.
    private val photos = RecognitionCache<Pair<String, Int>, Photo>(4 * 1024 * 1024) { photo ->
        64L + photo.blocks.sumOf { block -> 32L + block.sumOf(::lineBytes) }
    }
    private fun photoKey(image: ImageObject) = image.assetId to image.orientation

    /** Pages with a PDF or image background, or with photos placed on them. */
    fun readable(page: Page) = page.hasBackground || page.images.isNotEmpty()

    /** Only lines whose centre falls inside the visible crop count; text cut off by the crop is not shown. */
    private fun visible(photo: Photo, crop: ImageCrop) = photo.blocks
        .map { block -> block.filter { it.box.centerX() in crop.left..crop.right && it.box.centerY() in crop.top..crop.bottom } }
        .map { block -> block.joinToString("\n") { it.text } }.filter { it.isNotBlank() }

    private fun cachedPhoto(image: ImageObject): Photo? {
        val key = photoKey(image)
        synchronized(photos) { photos[key] }?.let { return it }
        return index?.get("photo|${key.first}|${key.second}")?.let { data ->
            runCatching { data.getJSONArray("blocks").let { a -> Photo((0 until a.length()).map { SearchIndex.lines(a.getJSONArray(it)) }) } }.getOrNull()
        }?.also { synchronized(photos) { photos[key] = it } }
    }
    private fun readPhoto(notebook: Notebook, image: ImageObject): List<String> {
        val key = photoKey(image)
        val diskKey = "photo|${key.first}|${key.second}"
        val stored = cachedPhoto(image)
        val photo = stored ?: run {
            val bitmap = ImageSupport.render(notebook.imageAssets.first { it.id == image.assetId }, ImageBackground(image.assetId, image.orientation))
            try {
                val width = bitmap.width.toFloat(); val height = bitmap.height.toFloat()
                val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                Photo(text.textBlocks.map { block ->
                    block.lines.mapNotNull { line ->
                        val box = line.boundingBox ?: return@mapNotNull null
                        Line(line.text, RectF(box.left / width, box.top / height, box.right / width, box.bottom / height))
                    }
                })
            } finally { bitmap.recycle() }
        }.also { read -> index?.put(diskKey, org.json.JSONObject().put("blocks", org.json.JSONArray().apply { read.blocks.forEach { put(SearchIndex.lines(it)) } })) }
        synchronized(photos) { photos[key] = photo }
        return visible(photo, image.crop)
    }

    /**
     * The whole page's text: the background first, then each placed photo in stacking order. [Result.lines] carries the
     * background lines only (page coordinates), which is what the PDF export layer uses. Null until everything was read.
     */
    fun cachedPage(page: Page): Result? {
        val background = if (page.hasBackground) cached(page) ?: return null else null
        val photoBlocks = page.images.flatMap { image -> visible(cachedPhoto(image) ?: return null, image.crop) }
        return Result(background?.lines.orEmpty(), background?.blocks.orEmpty() + photoBlocks)
    }

    fun readPage(app: NotesApplication, notebook: Notebook, page: Page): Result {
        require(readable(page)) { "This page has no PDF, image or photo to read" }
        cachedPage(page)?.let { return it }
        val background = if (page.hasBackground) read(app, notebook, page) else null
        val photoBlocks = page.images.flatMap { readPhoto(notebook, it) }
        return Result(background?.lines.orEmpty(), background?.blocks.orEmpty() + photoBlocks)
    }

    /**
     * P096 (F123): one band per printed line that the characters [start, end) of [result]'s text touch, covering only
     * the selected words on that line. Used to highlight or underline a text selection.
     */
    fun bands(result: Result, start: Int, end: Int): List<RectF> {
        if (end <= start) return emptyList()
        return result.lines.mapNotNull { line ->
            val words = line.words.filter { it.end > start && it.start < end }
            if (words.isEmpty()) return@mapNotNull null
            RectF(words.minOf { it.box.left }, line.box.top, words.maxOf { it.box.right }, line.box.bottom)
        }
    }

    /** P093 (F118): one search hit, on a page, with a one-line excerpt around the match and (P567) where it is, in page units. */
    data class Hit(val pageId: String, val pageIndex: Int, val excerpt: String, val typed: Boolean, val handwriting: Boolean = false, val boxes: List<RectF> = emptyList())
    private const val MOST_ON_A_PAGE = 20

    /** Collapses whitespace and case, so a phrase split over two printed lines still matches. */
    fun normalize(text: String) = text.lowercase().replace(Regex("\\s+"), " ").trim()

    /**
     * Matches [query] in the pages' read text (only pages already read), their saved handwriting text, the handwriting
     * read by itself (B19, P136: a best guess, so its other readings count too) and their typed text boxes.
     * P567: printed text and handwriting read by itself give one hit for each place on the page, with its position.
     */
    fun search(notebook: Notebook, query: String): List<Hit> {
        val needle = normalize(query)
        if (needle.isEmpty()) return emptyList()
        return notebook.pages.withIndex().flatMap { (index, page) ->
            val handwriting = page.activeReviewedInk.mapNotNull { record ->
                excerpt(record.text, needle)?.let { Hit(page.id, index, it, typed = false, boxes = listOf(record.bounds.let { b -> RectF(b.left, b.top, b.right, b.bottom) })) }
            }
            val guessed = if (handwriting.isNotEmpty()) emptyList() else InkText.cachedPage(page)?.let { guessedHits(page, index, it, needle) }.orEmpty()
            printedHits(page, index, needle) + handwriting + guessed + page.textBoxes.mapNotNull { box ->
                excerpt(box.text, needle)?.let { Hit(page.id, index, it, typed = true, boxes = listOf(box.placement.let { p -> RectF(p.x, p.y, p.x + p.width, p.y + p.height) })) }
            }
        }
    }

    /** A PDF's own text says nothing about position, so the places come from reading the page, when it has been read. */
    private fun printedHits(page: Page, index: Int, needle: String): List<Hit> {
        val embedded = PdfBookText.cached(page)?.takeIf { it.isNotBlank() }
        val source = embedded ?: cachedPage(page)?.text ?: return emptyList()
        val found = occurrences(source, needle).take(MOST_ON_A_PAGE)
        val alone = if (found.isEmpty()) excerpt(source, needle) ?: return emptyList() else null
        // Only for a page with a match: on a long book the walk through the pages stays away from the disk.
        val places = cachedPage(page)?.let { result -> occurrences(result.text, needle).map { bands(result, it.first, it.last + 1) } }.orEmpty()
        if (alone != null) return listOf(Hit(page.id, index, alone, typed = false, boxes = places.flatten()))
        return found.mapIndexed { i, range -> Hit(page.id, index, excerptAt(source, range), typed = false, boxes = if (places.size == found.size) places[i] else places.flatten()) }
    }

    private fun guessedHits(page: Page, index: Int, ink: InkText.Result, needle: String): List<Hit> {
        var offset = 0
        val starts = ink.spots.map { spot -> offset.also { offset += spot.text.length + 1 } }
        val joined = ink.spots.joinToString("\n") { it.text }
        val lit = HashSet<Int>()
        val top = occurrences(joined, needle).map { range ->
            Hit(page.id, index, excerptAt(joined, range), typed = false, handwriting = true, boxes = ink.spots.indices.mapNotNull { i ->
                val from = maxOf(range.first - starts[i], 0); val to = minOf(range.last + 1 - starts[i], ink.spots[i].text.length)
                if (from >= to) null else { lit += i; ink.spots[i].place(ink.spots[i].text, from, to) }
            })
        }
        // Another reading of a line can find it too, when the best reading of that line did not.
        val other = ink.spots.indices.filter { it !in lit }.mapNotNull { i ->
            ink.spots[i].alternatives.firstNotNullOfOrNull { reading ->
                occurrences(reading, needle).firstOrNull()?.let { range ->
                    Hit(page.id, index, excerptAt(reading, range), typed = false, handwriting = true, boxes = listOf(ink.spots[i].place(reading, range.first, range.last + 1)))
                }
            }
        }
        // Dense pages are read in overlapping excerpts, so the same ink can be read twice.
        val kept = mutableListOf<Hit>()
        (top + other).sortedBy { hit -> hit.boxes.minOfOrNull { it.top } ?: 0f }.forEach { hit ->
            if (kept.none { it.boxes.any { a -> hit.boxes.any { b -> mostlyOver(a, b) } } }) kept += hit
        }
        return kept.take(MOST_ON_A_PAGE)
    }

    private fun mostlyOver(a: RectF, b: RectF): Boolean {
        val width = minOf(a.right, b.right) - maxOf(a.left, b.left); val height = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return width > 0 && height > 0 && width * height * 2 > minOf(a.width() * a.height(), b.width() * b.height())
    }

    /** Every place [needle] (already normalized) is in [text], as character ranges of [text]: spaces and line breaks count as one space. */
    internal fun occurrences(text: String, needle: String): List<IntRange> {
        val flat = StringBuilder(text.length); val place = IntArray(text.length)
        text.forEachIndexed { i, c ->
            if (!c.isWhitespace()) { place[flat.length] = i; flat.append(c.lowercaseChar()) }
            else if (flat.isNotEmpty() && flat.last() != ' ') { place[flat.length] = i; flat.append(' ') }
        }
        val found = mutableListOf<IntRange>()
        var at = flat.indexOf(needle)
        while (at >= 0 && needle.isNotEmpty()) {
            found += place[at]..place[at + needle.length - 1]
            at = flat.indexOf(needle, at + needle.length)
        }
        return found
    }

    /** The match in [range] of [text] with up to 40 characters on each side, on one line. */
    private fun excerptAt(text: String, range: IntRange): String {
        fun flat(from: Int, to: Int) = text.substring(from, to).replace(Regex("\\s+"), " ")
        val startsAt = maxOf(0, range.first - 120); val endsAt = minOf(text.length, range.last + 121)
        val before = flat(startsAt, range.first).let { if (startsAt == 0) it.trimStart() else it }
        val after = flat(range.last + 1, endsAt).let { if (endsAt == text.length) it.trimEnd() else it }
        val left = before.takeLast(40); val right = after.take(40)
        return (if (left.length < before.length) "…" else "") + left + flat(range.first, range.last + 1) + right + (if (right.length < after.length) "…" else "")
    }

    private fun excerpt(text: String, needle: String): String? {
        val spaced = text.replace(Regex("\\s+"), " ").trim(); val lower = spaced.lowercase()
        val at = lower.indexOf(needle).takeIf { it >= 0 } ?: return null
        // Show the original letter case unless lower-casing changed the length (then indexes would not line up).
        val flat = if (lower.length == spaced.length) spaced else lower
        val start = maxOf(0, at - 40); val end = minOf(flat.length, at + needle.length + 40)
        return (if (start > 0) "…" else "") + flat.substring(start, end) + (if (end < flat.length) "…" else "")
    }
}
