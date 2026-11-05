package dev.ownednotes.app

import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.*
import dev.ownednotes.core.HandwritingInput
import dev.ownednotes.core.HandwritingLines
import dev.ownednotes.core.Page
import dev.ownednotes.core.Point
import java.security.MessageDigest
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * B19 (P136): handwriting read by itself, for Search in notebook. Pages are read in the background by [SearchIndexer]
 * with ML Kit's on-device digital ink recognizer; the text is only a cache in [PageText.index], keyed by the visible
 * pen ink, so notebook files never change and a page is read again only when its ink does. Reviewed handwriting
 * (Read handwriting…) stays the saved, exact text; this is a best guess with alternatives, used only to find pages.
 */
internal object InkText {
    /**
     * P567: one written line: its readings, and where its ink is in page units, as stretches with clear space between
     * them, left to right. Kept only in the search cache, like the readings, so results can show the place.
     */
    class Spot(val text: String, val alternatives: List<String>, val parts: List<RectF>) {
        val box: RectF get() = RectF(parts.minOf { it.left }, parts.minOf { it.top }, parts.maxOf { it.right }, parts.maxOf { it.bottom })
        /** The whole words of [reading] that the characters [start, end) touch. The widest gaps in the ink are its word gaps. */
        fun place(reading: String, start: Int, end: Int): RectF {
            val words = Regex("\\S+").findAll(reading).map { it.range }.toList()
            val first = words.indexOfFirst { it.last >= start }; val last = words.indexOfLast { it.first < end }
            if (words.size < 2 || first < 0 || last < first) return box
            if (parts.size >= words.size) {
                val cuts = (0 until parts.size - 1).sortedByDescending { parts[it + 1].left - parts[it].right }.take(words.size - 1).sorted()
                val from = if (first == 0) 0 else cuts[first - 1] + 1
                val to = if (last == words.size - 1) parts.size - 1 else cuts[last]
                val chosen = parts.subList(from, to + 1)
                return RectF(chosen.minOf { it.left }, chosen.minOf { it.top }, chosen.maxOf { it.right }, chosen.maxOf { it.bottom })
            }
            // Joined-up writing has fewer stretches than words: go by the characters' share of the line.
            val whole = box; val share = whole.width() / reading.length.coerceAtLeast(1)
            return RectF(whole.left + words[first].first * share, whole.top, whole.left + (words[last].last + 1) * share, whole.bottom)
        }
    }

    /** One page's guess: the top reading of each line, and the other readings, for matching only. */
    class Result(val text: String, val alternatives: List<String>, val spots: List<Spot> = emptyList())

    /** The ink of these fragments on the page: their boxes in page units, overlapping ones as one, left to right. */
    private fun parts(input: HandwritingInput, indices: List<Int>): List<RectF> {
        val bounds = input.bounds
        val unit = maxOf(1f, bounds.right - bounds.left, bounds.bottom - bounds.top) / 1000f
        val merged = mutableListOf<RectF>()
        indices.map { input.strokes[it] }.map { points ->
            RectF(points.minOf { it.x } * unit + bounds.left, points.minOf { it.y } * unit + bounds.top,
                points.maxOf { it.x } * unit + bounds.left, points.maxOf { it.y } * unit + bounds.top)
        }.sortedBy { it.left }.forEach { box ->
            val last = merged.lastOrNull()
            if (last != null && box.left <= last.right) last.union(box) else merged += box
        }
        return merged
    }

    /** Reads lines of points (page shape kept, any scale) to candidate texts, best first; null when it cannot now. */
    interface Reader {
        /** Whether reading can start; may download the language model, on Wi-Fi. Background thread only. */
        fun ready(): Boolean
        fun read(line: List<List<Point>>): List<String>
        /** Styling can give the model a single-line area; ordinary search keeps its existing input. */
        fun readForStyle(line: List<List<Point>>): List<String> = read(line)
        fun close() {}
    }

    @Volatile var reader: Reader = MlKitReader
    private val cache = RecognitionCache<String, Result>(4 * 1024 * 1024) { result ->
        96L + result.text.length * 2L + result.alternatives.sumOf { 32L + it.length * 2L } +
            result.spots.sumOf { spot -> 96L + spot.text.length * 2L + spot.alternatives.sumOf { 32L + it.length * 2L } + spot.parts.size * 32L }
    }
    /** Pages read since start, for tests. */
    @Volatile var pagesRead = 0; private set

    internal fun clearMemory() { synchronized(cache) { cache.clear() } }

    internal fun vocabularyChanged() { clearMemory(); synchronized(keys) { keys.clear() } }

    /** A page with pen ink worth reading. */
    fun readable(page: Page) = page.drawnStrokes.isNotEmpty()

    /** What the visible pen ink is; null when the page has none a pen made. Cheap enough for the background walk. */
    private fun inputs(notebookId: String, page: Page): List<HandwritingInput>? {
        val notebook = dev.ownednotes.core.Notebook(id = notebookId, pages = listOf(page))
        val whole = runCatching { HandwritingInput.capture(notebook, page.id) }.getOrNull()
        if (whole != null) return listOf(whole)
        val pens = setOf(dev.ownednotes.core.InkStroke.PEN, dev.ownednotes.core.InkStroke.BALLPOINT,
            dev.ownednotes.core.InkStroke.CALLIGRAPHY, dev.ownednotes.core.InkStroke.PENCIL)
        val ink = page.drawnStrokes.filter { it.brush in pens }
        // Keep capture's safety limits. Search alone can read dense pages in bounded overlapping excerpts.
        if (ink.isEmpty() || (ink.size <= HandwritingInput.MAX_STROKES && ink.sumOf { it.samples.size.toLong() } <= HandwritingInput.MAX_POINTS)) return null
        val result = mutableListOf<HandwritingInput>()
        var start = 0
        while (start < ink.size) {
            var end = start; var points = 0
            while (end < ink.size && end - start < 256 && points.toLong() + ink[end].samples.size <= HandwritingInput.MAX_POINTS) {
                points += ink[end].samples.size; end++
            }
            if (end == start) return null
            val ids = ink.subList(start, end).map { it.id }.toSet()
            result += runCatching { HandwritingInput.capture(notebook, page.id, ids) }.getOrNull() ?: return null
            if (end == ink.size) break
            // Overlap keeps ordinary words that straddle the capture-order boundary in one reading.
            start = maxOf(start + 1, end - 32)
        }
        return result
    }

    fun key(notebookId: String, page: Page): String? = inputs(notebookId, page)?.let(::key)

    private fun key(inputs: List<HandwritingInput>, vocabulary: PersonalVocabulary.Snapshot = PersonalVocabulary.snapshot): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputs.forEach { input ->
            digest.update("batch;".toByteArray())
            input.strokes.forEach { fragment ->
                digest.update(fragment.size.toString().toByteArray())
                fragment.forEach { p -> digest.update("${(p.x * 4).toInt()},${(p.y * 4).toInt()};".toByteArray()) }
            }
        }
        // Rebuild once so results can show where the handwriting is (P567). No notebook data is changed.
        val models = if (reader === MlKitReader) MlKitReader.wantedLanguages().joinToString(",") { it.tag } else language().tag
        return "ink-v6|${vocabulary.fingerprint}|$models|" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The memory or disk reading of [key], if any. */
    fun cached(key: String): Result? {
        synchronized(cache) { cache[key] }?.let { return it }
        val data = PageText.index?.get(key) ?: return null
        return runCatching {
            val spots = data.getJSONArray("spots")
            Result(data.getString("text"), SearchIndex.strings(data.getJSONArray("alt")), (0 until spots.length()).map { i ->
                val spot = spots.getJSONObject(i); val boxes = spot.getJSONArray("p")
                Spot(spot.getString("t"), SearchIndex.strings(spot.getJSONArray("a")), (0 until boxes.length()).map { SearchIndex.box(boxes.getJSONArray(it)) })
            })
        }.getOrNull()
            ?.also { synchronized(cache) { cache[key] = it } }
    }

    /**
     * Page id to a weak identity of the exact page object the walk saw and its key, so search looks a page up without capturing it again
     * on the main thread. Pages are immutable: the same object means the same ink.
     */
    private val keys = object : LinkedHashMap<String, Pair<WeakReference<Page>, String>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<WeakReference<Page>, String>>?) = size > 2048
    }
    fun cachedPage(page: Page): Result? = synchronized(keys) { keys[page.id] }?.takeIf { it.first.get() === page && it.second.startsWith("ink-v6|${PersonalVocabulary.snapshot.fingerprint}|") }?.let { cached(it.second) }

    /**
     * Background thread: reads [page] unless it was read already. Returns false when the reader is not ready or fails,
     * so the walk can stop early and a later event can retry; cancelled work returns true without publication.
     */
    fun read(notebookId: String, page: Page, stop: () -> Boolean): Boolean {
        if (stop()) return true
        val inputs = inputs(notebookId, page) ?: return true
        val vocabulary = PersonalVocabulary.snapshot
        val key = key(inputs,vocabulary)
        if (stop()) return true
        if (cached(key) != null) {
            if (!stop()) synchronized(keys) { keys[page.id] = WeakReference(page) to key }
            return true
        }
        val r = reader
        // Share the styling lock: ready may replace/close recognizers while another caller is reading.
        val ready = try { synchronized(r) { if (stop()) return true; r.ready() } } catch (_: Exception) { false }
        if (stop()) return true
        if (!ready) return false
        // Lines top to bottom within each bounded excerpt. Publication still waits for every excerpt.
        val lines = inputs.flatMap { input ->
            val split = runCatching { HandwritingLines.split(input.strokes) }.getOrElse { listOf(dev.ownednotes.core.HandwritingLine(input.strokes.indices.toList(), input.strokes)) }
            split.map { it.strokes to parts(input, it.strokeIndices) }
        }
        val top = mutableListOf<String>(); val others = mutableListOf<String>(); val spots = mutableListOf<Spot>()
        for ((line, where) in lines) {
            if (stop()) return true
            val candidates = try { synchronized(r) { if (stop()) return true; r.read(line) } } catch (_: Exception) {
                // An unavailable reading is not a successful empty one. Do not cache partial earlier rows either.
                if (stop()) return true
                return false
            }
            val choices = vocabulary.prefer(candidates).map { it.trim() }.filter { it.isNotBlank() }.distinct()
            // Recognition can block while the notebook closes or a newer walk replaces this one.
            // In particular, the final line has no next iteration to observe cancellation.
            if (stop()) return true
            choices.firstOrNull()?.let { top += it }
            others += choices.drop(1).take(4)
            if (choices.isNotEmpty() && where.isNotEmpty()) spots += Spot(choices.first(), choices.drop(1).take(4), where)
        }
        if (PersonalVocabulary.snapshot.fingerprint != vocabulary.fingerprint) return true
        val result = Result(top.joinToString("\n"), others, spots)
        if (stop()) return true
        synchronized(cache) { cache[key] = result }
        PageText.index?.put(key, org.json.JSONObject().put("text", result.text).put("alt", SearchIndex.strings(result.alternatives))
            .put("spots", org.json.JSONArray().apply { spots.forEach { spot ->
                put(org.json.JSONObject().put("t", spot.text).put("a", SearchIndex.strings(spot.alternatives))
                    .put("p", org.json.JSONArray().apply { spot.parts.forEach { put(SearchIndex.box(it)) } }))
            } }))
        synchronized(keys) { keys[page.id] = WeakReference(page) to key }
        pagesRead++
        return true
    }

    /** The device language when a model exists for it, else English. */
    fun language(): HandwritingLanguage =
        HandwritingLanguage.entries.firstOrNull { it.tag.substringBefore('-') == Locale.getDefault().language } ?: HandwritingLanguage.ENGLISH

    /** Keep each available language's best guess among the search alternatives. */
    internal fun mergeCandidates(readings: List<List<String>>): List<String> =
        (0 until 5).flatMap { rank -> readings.mapNotNull { it.getOrNull(rank) } }.distinct().take(5)

    /** Primary model keeps its existing Wi-Fi download. Other supported languages are used only if already on-device. */
    private object MlKitReader : Reader {
        private var languages = emptyList<HandwritingLanguage>()
        private var recognizers = emptyList<DigitalInkRecognizer>()
        private var failedAt = 0L
        private fun model(language: HandwritingLanguage) =
            DigitalInkRecognitionModel.builder(requireNotNull(DigitalInkRecognitionModelIdentifier.fromLanguageTag(language.tag))).build()

        fun wantedLanguages(): List<HandwritingLanguage> {
            val primary = language()
            val manager = RemoteModelManager.getInstance()
            return listOf(primary) + HandwritingLanguage.entries.filter { candidate ->
                candidate != primary && runCatching {
                    Tasks.await(manager.isModelDownloaded(model(candidate)), 2, TimeUnit.SECONDS)
                }.getOrDefault(false)
            }
        }

        override fun ready(): Boolean {
            val wanted = wantedLanguages()
            if (recognizers.isNotEmpty() && languages == wanted) return true
            if (System.currentTimeMillis() - failedAt < 600_000) return false
            return runCatching {
                val primary = model(wanted.first())
                val manager = RemoteModelManager.getInstance()
                if (!Tasks.await(manager.isModelDownloaded(primary), 15, TimeUnit.SECONDS))
                    Tasks.await(manager.download(primary, DownloadConditions.Builder().requireWifi().build()), 180, TimeUnit.SECONDS)
                val created = mutableListOf<DigitalInkRecognizer>()
                try {
                    wanted.forEach { created += DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model(it)).build()) }
                } catch (failure: Exception) { created.forEach { it.close() }; throw failure }
                recognizers.forEach { it.close() }
                recognizers = created; languages = wanted
                true
            }.getOrElse { failedAt = System.currentTimeMillis(); false }
        }

        override fun read(line: List<List<Point>>): List<String> {
            val ink = Ink.builder()
            line.forEach { fragment ->
                val stroke = Ink.Stroke.builder()
                fragment.forEach { stroke.addPoint(Ink.Point.create(it.x, it.y)) }
                ink.addStroke(stroke.build())
            }
            return recognize(ink.build())
        }

        override fun readForStyle(line: List<List<Point>>): List<String> {
            val points = line.flatten()
            if (points.isEmpty()) return emptyList()
            val left = points.minOf { it.x }; val top = points.minOf { it.y }
            val height = (points.maxOf { it.y } - top).coerceAtLeast(1f)
            val width = (points.maxOf { it.x } - left).coerceAtLeast(height)
            val ink = Ink.builder()
            line.forEach { fragment ->
                val stroke = Ink.Stroke.builder()
                fragment.forEach { stroke.addPoint(Ink.Point.create(it.x - left, it.y - top)) }
                ink.addStroke(stroke.build())
            }
            // P498: measured on raw own ink. Keep capture order and use one line, never the page height.
            val context = RecognitionContext.builder().setPreContext("")
                .setWritingArea(WritingArea(width, height * 1.5f)).build()
            return recognize(ink.build(), context)
        }

        private fun recognize(input: Ink, context: RecognitionContext? = null): List<String> {
            check(recognizers.isNotEmpty())
            return mergeCandidates(recognizers.map { recognizer ->
                val task = if (context == null) recognizer.recognize(input) else recognizer.recognize(input, context)
                Tasks.await(task, 20, TimeUnit.SECONDS).candidates.map { it.text }.take(5)
            })
        }

        override fun close() { recognizers.forEach { it.close() }; recognizers = emptyList(); languages = emptyList() }
    }
}
