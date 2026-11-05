package dev.ownednotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.LruCache
import dev.ownednotes.core.HandwritingInput
import dev.ownednotes.core.HandwritingLines
import dev.ownednotes.core.HandwritingRestyle
import dev.ownednotes.core.InkStroke
import dev.ownednotes.core.Point
import dev.ownednotes.core.Sample
import java.util.concurrent.Executors

/**
 * P415 handwriting style (docs/AUTO-REFINE-DESIGN.md, section 9): the chosen professional hand, how far your writing is
 * bent toward it, and the on-device work of reading a line and rewriting its words in that hand with your own pen.
 * The hands are open-licensed fonts bundled in assets/hands (each with its OFL text); nothing leaves the tablet.
 */
internal object HandStyle {
    enum class Hand(val label: String, private val file: String) {
        MODERN_ITALIC("Modern italic", "modern-italic.ttf"), FORMAL("Formal", "formal.ttf"), BRITISH_JOINED("British joined", "british-joined.ttf"),
        FLOWING("Flowing", "flowing.ttf"), AMERICAN_CURSIVE("American cursive", "american-cursive.ttf"), COPPERPLATE("Copperplate", "copperplate.ttf"),
        NEAT_PRINT("Neat print", "neat-print.ttf"),
        // P417: eight more, chosen by the human.
        ROUNDED_PRINT("Rounded print", "rounded-print.ttf"), SCHOOL_PRINT("School print", "school-print.ttf"), CASUAL_MARKER("Casual marker", "casual-marker.ttf"),
        SLANTED_CASUAL("Slanted casual", "slanted-casual.ttf"), NATURAL_CURSIVE("Natural cursive", "natural-cursive.ttf"), FRENCH_CURSIVE("French cursive", "french-cursive.ttf"),
        THIN_SCRIPT("Thin script", "thin-script.ttf"), GRAND_SCRIPT("Grand script", "grand-script.ttf");
        val asset get() = "hands/$file"
    }

    /** What the person chose: the [hand], how far toward it ([settings]) and whether lines are rewritten as they write. */
    data class Choice(val hand: Hand = Hand.MODERN_ITALIC, val settings: HandwritingRestyle.Settings = HandwritingRestyle.Settings(), val auto: Boolean = false, val wordSpacing: Float = 0f) {
        init { require(wordSpacing.isFinite() && wordSpacing in 0f..1f) }
    }

    private fun preferences(context: Context) = (context.applicationContext as NotesApplication).let { it.getSharedPreferences(it.preferencesName, Context.MODE_PRIVATE) }
    fun choice(context: Context): Choice {
        val p = preferences(context)
        val hand = Hand.entries.firstOrNull { it.name == p.getString("handStyle", null) } ?: Hand.MODERN_ITALIC
        val letters = p.getFloat("handStyleLetters", 0f).coerceIn(0f, 1f); val rhythm = p.getFloat("handStyleRhythm", 0f).coerceIn(0f, 1f)
        val steady = p.getFloat("handStyleSteady", 0f).coerceIn(0f, 1f)
        return Choice(hand, HandwritingRestyle.Settings(letters, rhythm, p.getBoolean("handStyleLevel", true), steady), p.getBoolean("handStyleAuto", false), p.getFloat("handStyleWordSpacing", 0f).takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: 0f)
    }
    fun save(context: Context, choice: Choice) = preferences(context).edit().putString("handStyle", choice.hand.name)
        .putFloat("handStyleLetters", choice.settings.letters).putFloat("handStyleRhythm", choice.settings.rhythm).putFloat("handStyleSteady", choice.settings.steady)
        .putBoolean("handStyleLevel", choice.settings.level).putBoolean("handStyleAuto", choice.auto).putFloat("handStyleWordSpacing", choice.wordSpacing).apply()
    /** The pen popover's one-line summary. */
    fun summary(context: Context) = choice(context).let { if (it.auto) "${it.hand.label} · as you write" else "Off · try it on a writing pad" }

    /** Reading and rewriting run here, one at a time, never on the main thread. */
    val worker = Executors.newSingleThreadExecutor { Thread(it, "hand-style").apply { isDaemon = true } }

    private val typefaces = HashMap<Hand, Typeface>()
    fun typeface(context: Context, hand: Hand): Typeface = synchronized(typefaces) {
        typefaces.getOrPut(hand) { Typeface.createFromAsset(context.applicationContext.assets, hand.asset) }
    }

    private const val RASTER = 160f
    private val skeletons = LruCache<String, HandwritingRestyle.Skeleton>(400)
    /** The [hand]'s [text] drawn large and thinned to centre lines; null when it can't be drawn. Background thread. */
    fun skeleton(context: Context, hand: Hand, text: String): HandwritingRestyle.Skeleton? {
        val key = "${hand.name}|$text"
        skeletons.get(key)?.let { return it }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = typeface(context, hand); textSize = RASTER; color = Color.BLACK }
        val ink = Rect(); paint.getTextBounds(text, 0, text.length, ink)
        if (ink.isEmpty) return null
        val pad = (RASTER * .5f).toInt()
        val w = ink.width() + 2 * pad; val h = ink.height() + 2 * pad
        if (w.toLong() * h > 4_000_000L) return null
        val baseline = (pad - ink.top).toFloat()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText(text, (pad - ink.left).toFloat(), baseline, paint)
        val pixels = IntArray(w * h); bitmap.getPixels(pixels, 0, w, 0, 0, w, h); bitmap.recycle()
        val x = Rect(); paint.getTextBounds("xvwz", 0, 4, x)
        val sk = HandwritingRestyle.skeleton(BooleanArray(w * h) { (pixels[it] ushr 24) > 110 }, w, h, baseline, (-x.top).toFloat().coerceAtLeast(1f), RASTER * .2f)
        return sk?.also { skeletons.put(key, it) }
    }

    /** The writing in [strokes] split into lines, top to bottom. */
    fun lines(strokes: List<InkStroke>): List<List<InkStroke>> = runCatching {
        HandwritingLines.split(strokes.map { s -> s.samples.map { Point(it.x, it.y) } }).map { line -> line.strokeIndices.map { strokes[it] } }
    }.getOrElse { listOf(strokes) }

    /**
     * One line read on the tablet and split into its words; null when the reader isn't ready or the reading doesn't
     * fit the ink. Background thread.
     */
    fun read(line: List<InkStroke>, cancelled: () -> Boolean = { false }): List<HandwritingRestyle.Word>? {
        if (cancelled()) return null
        val reader = InkText.reader
        val candidates = synchronized(reader) {
            if (cancelled()) return null
            if (!runCatching { reader.ready() }.getOrDefault(false)) return null
            if (cancelled()) return null
            runCatching { reader.readForStyle(line.map { s -> s.samples.map { Point(it.x, it.y) } }) }.getOrNull()
        }?.let { PersonalVocabulary.snapshot.prefer(it) } ?: return null
        if (cancelled()) return null
        // Keep the reader's order, but a rejected reading must not hide a fitting alternative.
        for (candidate in candidates) {
            val text = candidate.trim().takeIf { it.isNotBlank() } ?: continue
            val words = HandwritingRestyle.words(line, text) ?: continue
            if (words.any { it.text != null }) return words
        }
        return null
    }

    /** Manual preview only: an unsafe row may still contain a separately readable upper insertion.
     * Unconfirmed writing stays original; each region keeps its own baseline. Automatic writing uses [read] unchanged.
     */
    fun preview(strokes: List<InkStroke>, cancelled: () -> Boolean = { false }): List<List<HandwritingRestyle.Word>> {
        if (cancelled()) return emptyList()
        val result = mutableListOf<List<HandwritingRestyle.Word>>()
        for (source in lines(strokes)) {
            if (cancelled()) return emptyList()
            val marker = dev.ownednotes.core.HandwritingMarkers.leadingCircle(source)
            if (marker != null) result += listOf(HandwritingRestyle.Word(null, marker.marker))
            val unmarked = marker?.body ?: source
            val markers = dev.ownednotes.core.HandwritingMarkers
            val claimed = mutableSetOf<String>()
            val symbols = (markers.asterisks(unmarked) + markers.arrows(unmarked) + markers.quotationMarks(unmarked)).filter { symbol ->
                if (symbol.any { it.id in claimed }) false else { claimed.addAll(symbol.map { it.id }); true }
            }
            for (symbol in symbols) result += listOf(HandwritingRestyle.Word(null, symbol))
            val symbolIds = claimed
            val line = if (symbolIds.isEmpty()) unmarked else unmarked.filter { it.id !in symbolIds }
            if (line.isEmpty()) continue
            val whole = read(line, cancelled)
            if (cancelled()) return emptyList()
            if (whole != null) { result += repairPreview(whole, cancelled); continue }
            val range = HandwritingRestyle.upperInsertion(line)
            if (range == null) {
                result += leadingWord(line, cancelled) ?: listOf(HandwritingRestyle.Word(null, line))
                continue
            }
            val upper = read(line.subList(range.first, range.last + 1), cancelled)
            if (cancelled()) return emptyList()
            if (upper == null) { result += listOf(HandwritingRestyle.Word(null, line)); continue }
            val body = line.filterIndexed { index, _ -> index !in range }
            result += upper
            result += leadingWord(body, cancelled) ?: listOf(HandwritingRestyle.Word(null, body))
        }
        if (cancelled()) return emptyList()
        val protected=mutableListOf<List<HandwritingRestyle.Word>>()
        var closingConfirmed=false
        var openingConfirmed=false
        for(words in result) {
            val separated=mutableListOf<HandwritingRestyle.Word>()
            for(word in words) {
                if(cancelled())return emptyList()
                val closing=if(closingConfirmed)null else dev.ownednotes.core.HandwritingMarkers.closingLetters(word,strokes)
                val opening=if(closing!=null || openingConfirmed)null else dev.ownednotes.core.HandwritingMarkers.openingLetters(word,strokes)
                val split=closing?:opening
                if(split!=null) {
                    // One opening and one closing confirmation per complete preview, including disagreement.
                    if(closing!=null)closingConfirmed=true else openingConfirmed=true
                    val confirmed=read(split.letters,cancelled)?.singleOrNull()
                    if(cancelled())return emptyList()
                    if(confirmed?.text.equals(split.text,ignoreCase=true) &&
                        confirmed?.strokes?.map {it.id}?.toSet()==split.letters.map {it.id}.toSet()) {
                        if(opening!=null)separated+=HandwritingRestyle.Word(null,split.punctuation)
                        separated+=HandwritingRestyle.Word(split.text,split.letters)
                        if(closing!=null)separated+=HandwritingRestyle.Word(null,split.punctuation)
                        continue
                    }
                }
                separated+=word
            }
            val terminal=dev.ownednotes.core.HandwritingMarkers.protectTerminalPunctuation(separated,strokes)
            protected+=dev.ownednotes.core.HandwritingMarkers.protectOpeningMark(terminal,strokes)
        }
        return if(cancelled())emptyList() else protected
    }

    /** At most one independent confirmation for a rejected manual placement; automatic reading is unchanged. */
    private fun repairPreview(words: List<HandwritingRestyle.Word>, cancelled: () -> Boolean): List<HandwritingRestyle.Word> {
        val repaired = dev.ownednotes.core.HandwritingWordSpacing.repair(words)
        if (repaired.any { it.text != null } || cancelled()) return repaired
        val first = words.firstOrNull() ?: return repaired
        if (first.text?.count { it.isLetterOrDigit() }?.let { it >= 8 } != true) return repaired
        val confirmed = read(first.strokes, cancelled)?.singleOrNull()?.text
        if (cancelled()) return emptyList()
        if (!confirmed.equals(first.text, ignoreCase = true)) return repaired
        return dev.ownednotes.core.HandwritingWordSpacing.repair(words, confirmedFirstWord = true)
    }

    /** Manual fallback only. A word and the following text must agree independently at exactly one tested boundary.
     * Keep all remaining writing; this is bounded model agreement, not a confidence estimate or paragraph reflow.
     */
    private fun leadingWord(line: List<InkStroke>, cancelled: () -> Boolean): List<HandwritingRestyle.Word>? {
        val reader = InkText.reader
        val vocabulary = PersonalVocabulary.snapshot
        return synchronized(reader) {
            if (cancelled()) return@synchronized null
            if (!runCatching { reader.ready() }.getOrDefault(false)) return@synchronized null
            fun candidates(strokes: List<InkStroke>): List<String>? {
                if (cancelled()) return null
                val values = runCatching { reader.readForStyle(strokes.map { s -> s.samples.map { Point(it.x,it.y) } }) }.getOrNull()
                return if (cancelled()) null else values?.let { vocabulary.prefer(it) }
            }
            val sentence = candidates(line)?.firstOrNull()?.trim() ?: return@synchronized null
            val tokens = sentence.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val boundaries = HandwritingRestyle.leadingWordCandidates(line, sentence)
            if (boundaries.isEmpty()) return@synchronized null
            var confirmed: List<HandwritingRestyle.Word>? = null
            for ((prefix,suffix) in boundaries) {
                val first = candidates(prefix) ?: return@synchronized null
                if (!first.firstOrNull()?.trim().equals(tokens[0],ignoreCase=true)) continue
                val rest = candidates(suffix) ?: return@synchronized null
                if (!rest.firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull().equals(tokens[1],ignoreCase=true)) continue
                val word = HandwritingRestyle.words(prefix,tokens[0])?.singleOrNull() ?: continue
                if (confirmed != null) return@synchronized null
                val following = rest.asSequence().mapNotNull { text ->
                    HandwritingRestyle.words(suffix,text)?.takeIf { words -> words.any { it.text != null } }
                }.firstOrNull()?.let { repairPreview(it, cancelled) }?.takeIf { words -> words.any { it.text != null } }
                confirmed = listOf(word) + (following ?: listOf(HandwritingRestyle.Word(null,suffix)))
            }
            confirmed
        }
    }

    /** A word as rewritten: [lines] in page coordinates, or null to keep it as written. */
    class Piece(val word: HandwritingRestyle.Word, val lines: List<List<Point>>?)

    /** The chosen hand's centre lines for each of [words] that has text and can be drawn. Background thread. */
    fun styled(context: Context, words: List<HandwritingRestyle.Word>, choice: Choice) =
        words.mapNotNull { w -> w.text?.let { skeleton(context, choice.hand, it) }?.let { w to it } }

    /** One line's [words] rewritten in the chosen hand, set on [row] when given. Background thread. */
    fun restyle(context: Context, words: List<HandwritingRestyle.Word>, choice: Choice, row: Float? = null, sharedHeights: Boolean = false, protectedInk: List<InkStroke> = emptyList()): List<Piece> {
        val styled = styled(context, words, choice)
        val out = HandwritingRestyle.restyleLine(styled.map { (w, sk) -> w.strokes to sk }, choice.settings, row, sharedHeights)
        val rewritten = HashMap<HandwritingRestyle.Word, List<List<Point>>>()
        styled.forEachIndexed { i, (w, _) -> rewritten[w] = out[i] }
        val pieces=words.map { Piece(it, rewritten[it]) }
        if(!sharedHeights || choice.wordSpacing<=0f || styled.isEmpty())return pieces
        val heights=styled.mapNotNull { (word,sk)->rewritten[word]?.flatten()?.takeIf {it.isNotEmpty()}?.let {points->
            (points.maxOf {it.y}-points.minOf {it.y})*sk.xHeight/(sk.bottom-sk.top).coerceAtLeast(.5f)
        } }.sorted()
        if(heights.isEmpty())return pieces
        val spaced=dev.ownednotes.core.HandwritingWordLayout.arrange(pieces.map {dev.ownednotes.core.HandwritingWordLayout.Part(it.word.strokes,it.lines)},protectedInk,heights[heights.size/2],choice.wordSpacing)
        return pieces.mapIndexed {i,piece->Piece(piece.word,spaced[i])}
    }

    /**
     * A rewritten word as pen strokes with its first stroke's colour, width, pen, group and layer, and the word's
     * typical pressure and tilt. Outlines are left for the pen engine to draw (main thread). Null when kept as written.
     */
    fun strokes(piece: Piece): List<InkStroke>? {
        val lines = piece.lines?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() } ?: return null
        val old = piece.word.strokes; val first = old.first()
        val samples = old.flatMap { it.samples }
        fun middle(values: List<Float>) = values.sorted()[values.size / 2]
        val pressure = middle(samples.map { it.pressure }); val tilt = middle(samples.map { it.tilt }); val orientation = middle(samples.map { it.orientation })
        var t = first.samples.first().timeMs
        return lines.map { points ->
            InkStroke(color = first.color, width = first.width, brush = first.brush, outlines = emptyList(), group = first.group, layer = first.layer,
                samples = points.map { p -> Sample(p.x, p.y, t, pressure, tilt, orientation).also { t += 6 } })
        }
    }
}

/**
 * P415/P416: with "Improve my handwriting as I write" on, words are rewritten in the chosen hand as soon as they're
 * finished. A moment after each pen lift, every word of the line but the one still being written is rewritten; when the
 * pen rests, or starts the next line, the rest follow. Reading and rewriting happen in the background; the result goes
 * on the page between strokes, only where that writing is still exactly as it was, as one Undo per batch. A stroke
 * added later to a word already rewritten (an i's dot, a t's cross) stays as written.
 */
internal class HandStyleWriter(private val ink: InkCanvas) {
    /** The line being written: its strokes not yet rewritten, its height, the rewritten words' spans and its baseline. */
    private class Line(val notebookId: String, val pageId: String) {
        val pending = mutableListOf<InkStroke>()
        var top = Float.MAX_VALUE; var bottom = -Float.MAX_VALUE
        val done = mutableListOf<ClosedFloatingPointRange<Float>>()
        @Volatile var row: Float? = null // Set on the worker, by the first batch.
    }
    /** One finished word: its writing, what replaces it (null to keep it as written) and where it sits along the line. */
    private class Word(val strokes: List<InkStroke>, val rewritten: List<InkStroke>?, val span: ClosedFloatingPointRange<Float>)

    private var line: Line? = null
    private var reading = 0
    private val look = Runnable { line?.let { if (reading == 0) rewrite(it, all = false) } }
    // The last word waits while the pen is on the page: a slow stroke may still be part of it.
    private val idle: Runnable = Runnable { line?.let { if (ink.isEditing) ink.postDelayed(idle, IDLE_MS) else rewrite(it, all = true) } }

    /** A stroke the pen just finished on [pageId]. Main thread. */
    fun written(stroke: InkStroke, pageId: String, notebookId: String) {
        if (!HandStyle.choice(ink.context).auto) { cancel(); return }
        if (!HandwritingRestyle.eligible(stroke)) return
        val y0 = stroke.samples.minOf { it.y }; val y1 = stroke.samples.maxOf { it.y }
        val x = (stroke.samples.minOf { it.x } + stroke.samples.maxOf { it.x }) / 2
        var l = line
        if (l == null || l.pageId != pageId || l.notebookId != notebookId) { flush(); l = Line(notebookId, pageId).also { line = it } }
        // Start a lower row only when the whole stroke is below this row.
        // A descender can have a low centre while still belonging to the word being written.
        else if (l.top <= l.bottom && (y0 > l.bottom + (l.bottom - l.top) * .1f || (y1 < l.top && !nearbyUpperMark(l, stroke, y1, x)))) { flush(); l = Line(notebookId, pageId).also { line = it } }
        l.top = minOf(l.top, y0); l.bottom = maxOf(l.bottom, y1)
        if (l.done.any { x in it }) return
        l.pending += stroke
        ink.removeCallbacks(look); ink.removeCallbacks(idle)
        ink.postDelayed(look, LOOK_MS); ink.postDelayed(idle, IDLE_MS)
    }

    /** A nearby dot/crossbar may sit above its unfinished word; a separate upper row still starts a new line. */
    private fun nearbyUpperMark(l: Line, stroke: InkStroke, bottom: Float, x: Float): Boolean {
        val height = l.bottom - l.top
        if (l.pending.isEmpty() || height <= 0f || l.top - bottom > height * .75f) return false
        val candidates = l.pending + stroke
        if (candidates.size > HandwritingInput.MAX_STROKES || candidates.sumOf { it.samples.size.toLong() } > HandwritingInput.MAX_POINTS) return false
        val left = l.pending.minOf { s -> s.samples.minOf { it.x } }
        val right = l.pending.maxOf { s -> s.samples.maxOf { it.x } }
        if (x < left - height * .5f || x > right + height * .5f) return false
        // Reuse the existing body/mark classifier. Invalid/oversized input keeps the prior row boundary.
        return runCatching {
            HandwritingLines.split(candidates.map { s -> s.samples.map { Point(it.x, it.y) } }).size == 1
        }.getOrDefault(false)
    }

    /** Rewrites the rest of the line now, and starts a new one. */
    fun flush() {
        ink.removeCallbacks(look); ink.removeCallbacks(idle)
        line?.let { rewrite(it, all = true) }; line = null
    }

    fun cancel() { ink.removeCallbacks(look); ink.removeCallbacks(idle); line = null }

    /** Reads [l]'s waiting strokes and rewrites its finished words: [all] of them, or all but the last. */
    private fun rewrite(l: Line, all: Boolean) {
        if (all) ink.removeCallbacks(look)
        val strokes = l.pending.toList(); if (strokes.isEmpty()) return
        val context = ink.context.applicationContext; val choice = HandStyle.choice(context)
        reading++
        HandStyle.worker.execute {
            val words = runCatching { read(l, strokes, all, context, choice) }.getOrDefault(emptyList())
            ink.post { reading--; take(l, words, if (all) strokes else emptyList()) }
        }
    }

    private fun read(l: Line, strokes: List<InkStroke>, all: Boolean, context: Context, choice: HandStyle.Choice): List<Word> {
        val rows = HandStyle.lines(strokes)
        if (!all && rows.size != 1) return emptyList()
        return rows.flatMap { row ->
            val words = HandStyle.read(row) ?: return@flatMap emptyList()
            val finished = if (all) words else words.dropLast(1)
            if (finished.isEmpty()) return@flatMap emptyList()
            // The line's first batch sets the baseline every later word of it is levelled to.
            val base = if (rows.size != 1) null else l.row ?: HandStyle.styled(context, words, choice).takeIf { it.isNotEmpty() }
                ?.let { s -> HandwritingRestyle.rowBase(s.map { (w, sk) -> w.strokes to sk }) }?.also { l.row = it }
            HandStyle.restyle(context, finished, choice, base).map { piece ->
                val xs = piece.word.strokes.flatMap { s -> s.samples.map { it.x } }
                Word(piece.word.strokes, HandStyle.strokes(piece), xs.min()..xs.max())
            }
        }
    }

    /** Puts [words] on the page, leaving any another batch already took; [settled] strokes are no longer waited on. */
    private fun take(l: Line, words: List<Word>, settled: List<InkStroke>) {
        val waiting = l.pending.mapTo(HashSet()) { it.id }
        val fresh = words.filter { w -> w.strokes.all { it.id in waiting } }
        val gone = (fresh.flatMap { it.strokes } + settled).mapTo(HashSet()) { it.id }
        l.pending.removeAll { it.id in gone }
        fresh.forEach { l.done += it.span }
        val groups = fresh.mapNotNull { w -> w.rewritten?.let { w.strokes to it } }
        if (groups.isNotEmpty()) ink.applyHandStyle(l.notebookId, l.pageId, groups, 0)
    }

    private companion object { const val LOOK_MS = 200L; const val IDLE_MS = 700L }
}
