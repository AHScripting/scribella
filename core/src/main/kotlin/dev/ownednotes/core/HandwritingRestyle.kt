package dev.ownednotes.core

import kotlin.math.*
import kotlin.random.Random

/**
 * P415 (docs/AUTO-REFINE-DESIGN.md, section 9): recognised handwriting rewritten in a chosen professional hand, the way
 * the human approved it on the PC preview. The hand's letters are thinned to centre lines (so they can be drawn with the
 * writer's own pen), sized and placed on the writer's word, and bent toward the writer:
 *
 * - [Settings.letters] 0 leans each line to the writer's slant and gives each letter the writer's own height, width and
 *   place on the line, with a little unevenness; 1 keeps the hand's own letters at the writer's rhythm.
 * - [Settings.rhythm] 0 keeps the writer's letter widths, spacing and heights; 1 is the hand's own even rhythm.
 * - [Settings.steady] (P416) 0 keeps each letter as high or low and as tall as the writer made it; 1 sets every letter of
 *   a word on one line at one height, keeping the writer's spacing.
 *
 * Only geometry happens here; the app renders the hand's word ([skeleton] takes its pixels) and redraws the results with
 * the writer's pen.
 */
object HandwritingRestyle {
    data class Settings(val letters: Float = 0f, val rhythm: Float = 0f, val level: Boolean = true, val steady: Float = 0f) {
        init { require(letters in 0f..1f && rhythm in 0f..1f && steady in 0f..1f) { "Settings run from 0 to 1" } }
    }

    /**
     * The hand's word as centre lines, in its raster's pixels: [lines] are x,y pairs, [dots] are x,y,radius (dots on i, j
     * and umlauts would thin away, so they stay round). [left]..[bottom] is the inked box, [baseline] its baseline.
     */
    class Skeleton(val lines: List<FloatArray>, val dots: List<FloatArray>, val left: Float, val top: Float, val right: Float,
        val bottom: Float, val baseline: Float, val xHeight: Float)

    /** One word of a line: its recognised [text] (null: kept as drawn, such as an arrow or a bullet) and its strokes. */
    class Word(val text: String?, val strokes: List<InkStroke>)

    /** Pen strokes that can be rewritten: no highlights, tape, shapes, locked, linked, recorded or partly erased ink. */
    fun eligible(stroke: InkStroke) = HandwritingRefine.eligible(stroke) && stroke.audio == null && stroke.erasures.isEmpty()

    /** Words get the hand's letters only when they read as words; arrows, stars, dashes and blanks stay as drawn. */
    fun readable(token: String) = token.any { it.isLetterOrDigit() } || token == "&"

    // ---- words of a line ----

    /**
     * Splits one line's strokes into the recognised [text]'s words at the widest gaps between touching stroke clusters.
     * Null when the gaps can't give that many words, so nothing is rewritten on a reading that doesn't fit the ink.
     */
    fun words(line: List<InkStroke>, text: String): List<Word>? {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || line.isEmpty()) return null
        class Cluster(val strokes: MutableList<InkStroke>, val left: Float, var right: Float)
        val clusters = mutableListOf<Cluster>()
        line.map { s -> Triple(s, s.samples.minOf { it.x }, s.samples.maxOf { it.x }) }.sortedBy { it.second }.forEach { (s, l, r) ->
            val last = clusters.lastOrNull()
            if (last != null && l < last.right + 1f) { last.strokes += s; last.right = max(last.right, r) } else clusters += Cluster(mutableListOf(s), l, r)
        }
        if (clusters.size < tokens.size) return null
        val breaks = (1 until clusters.size).sortedByDescending { clusters[it].left - clusters[it - 1].right }.take(tokens.size - 1).sorted()
        val out = mutableListOf<Word>(); var start = 0
        (breaks + clusters.size).forEachIndexed { i, end ->
            val strokes=clusters.subList(start,end).flatMap {it.strokes}
            val characters=tokens[i].count {it.isLetterOrDigit()}
            if(characters in 1..2) {
                val width=clusters[end-1].right-clusters[start].left
                val height=strokes.maxOf {s->s.samples.maxOf {it.y}}-strokes.minOf {s->s.samples.minOf {it.y}}
                // Reject only gross short readings (e.g. a whole phrase read as one stretched letter).
                // This is not recognition confidence; uncertain ink stays original, including very flat short words.
                if(height>0f && width>height*(2f*characters+1f))return null
            }
            if(characters>=8) {
                val width=clusters[end-1].right-clusters[start].left
                val height=strokes.maxOf {s->s.samples.maxOf {it.y}}-strokes.minOf {s->s.samples.minOf {it.y}}
                // A long horizontal word assigned to a narrower-than-tall fragment is unsafe placement,
                // not reading confidence. Keep original ink instead of squeezing many letters onto one glyph.
                if(height>0f && width<height)return null
            }
            out += Word(tokens[i].takeIf(::readable),strokes);start=end
        }
        return out
    }

    /**
     * A distinct phrase added above an already completed row, in original capture positions. This conservative
     * fallback is for manual styling after the whole-row reading was rejected, not recognition or line splitting.
     * Dots/crossbars, same-baseline revisions and more than one possible insertion stay ambiguous.
     */
    fun upperInsertion(line: List<InkStroke>): IntRange? {
        if (line.size !in 12..HandwritingInput.MAX_STROKES || line.any { !eligible(it) || it.samples.isEmpty() } ||
            line.sumOf { it.samples.size.toLong() } > HandwritingInput.MAX_POINTS) return null
        if (line.any { s -> s.samples.any { !it.x.isFinite() || !it.y.isFinite() || abs(it.x) > 1_000_000f || abs(it.y) > 1_000_000f } }) return null
        class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) { val height get() = bottom - top }
        val boxes = line.map { s -> Box(s.samples.minOf { it.x }, s.samples.minOf { it.y }, s.samples.maxOf { it.x }, s.samples.maxOf { it.y }) }
        val heights = boxes.map { it.height }.sorted()
        val typical = heights[heights.size * 4 / 5]
        if (typical <= 0f) return null
        val bodies = boxes.filter { it.height >= typical * .35f }
        if (bodies.size < 8) return null
        val bottoms = bodies.map { it.bottom }.sorted()
        val ceiling = bottoms[bottoms.size / 2] - typical * .55f
        var candidate: IntRange? = null
        var i = 0
        while (i < boxes.size) {
            if (boxes[i].bottom > ceiling) { i++; continue }
            val start = i
            while (i < boxes.size && boxes[i].bottom <= ceiling) i++
            val end = i
            if (start < 8 || end - start < 3 || (end - start) * 3 > line.size) continue
            val upper = boxes.subList(start, end)
            if (upper.count { it.height >= typical * .4f } < 2) continue
            val left = upper.minOf { it.left }; val right = upper.maxOf { it.right }
            if (right - left < typical * 1.5f || upper.maxOf { it.bottom } - upper.minOf { it.top } < typical * .6f) continue
            val earlier = boxes.subList(0, start)
            if (earlier.count { it.height >= typical * .35f } < 5 ||
                earlier.maxOf { it.right } - earlier.minOf { it.left } < typical * 6f ||
                boxes[start - 1].left - boxes[start].right < typical * 2f ||
                earlier.maxOf { it.right } - left < typical * 3f) continue
            if (candidate != null) return null
            candidate = start until end
        }
        return candidate
    }

    /** Candidate source boundaries only, never a new word assignment. Manual preview must independently confirm
     * both sides with its reader. Restrict this fallback to the existing narrow-first-long-word rejection.
     */
    fun leadingWordCandidates(line: List<InkStroke>, text: String): List<Pair<List<InkStroke>, List<InkStroke>>> {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < 2 || tokens.first().count { it.isLetterOrDigit() } < 8 || line.size !in 2..HandwritingInput.MAX_STROKES ||
            line.any { !eligible(it) } || line.sumOf { it.samples.size.toLong() } > HandwritingInput.MAX_POINTS ||
            line.map { it.id }.toSet().size != line.size) return emptyList()
        if (line.any { s -> s.samples.any { !it.x.isFinite() || !it.y.isFinite() || abs(it.x) > 1_000_000f || abs(it.y) > 1_000_000f } }) return emptyList()
        class Cluster(val strokes: MutableList<InkStroke>, val left: Float, var right: Float)
        val clusters = mutableListOf<Cluster>()
        line.map { s -> Triple(s, s.samples.minOf { it.x }, s.samples.maxOf { it.x }) }.sortedBy { it.second }.forEach { (s, l, rr) ->
            val last = clusters.lastOrNull()
            if (last != null && l < last.right + 1f) { last.strokes += s; last.right = max(last.right, rr) }
            else clusters += Cluster(mutableListOf(s), l, rr)
        }
        if (clusters.size < tokens.size) return emptyList()
        val gaps = (1 until clusters.size).sortedByDescending { clusters[it].left - clusters[it-1].right }
        val originalEnd = gaps.take(tokens.size-1).minOrNull() ?: return emptyList()
        val original = clusters.take(originalEnd).flatMap { it.strokes }
        val width = clusters[originalEnd-1].right - clusters.first().left
        val height = original.maxOf { s -> s.samples.maxOf { it.y } } - original.minOf { s -> s.samples.minOf { it.y } }
        if (height <= 0f || width >= height) return emptyList()
        return gaps.asSequence().filter { it > originalEnd && clusters.size-it >= tokens.size-1 }.map { end ->
            val ids = clusters.take(end).flatMap { it.strokes }.mapTo(HashSet()) { it.id }
            line.filter { it.id in ids } to line.filterNot { it.id in ids }
        }.filter { (prefix, _) -> words(prefix, tokens.first()) != null }.take(3).toList()
    }

    // ---- the hand's letters as centre lines ----

    /**
     * Thins the hand's word, drawn filled into [mask] ([width] × [height] pixels, a blank border kept), to centre lines
     * (Zhang–Suen) and traces them into polylines. Pieces smaller than [dotSize] that aren't taller than wide are dots;
     * a comma is thinned like a stroke so it stays a comma.
     */
    fun skeleton(mask: BooleanArray, width: Int, height: Int, baseline: Float, xHeight: Float, dotSize: Float): Skeleton? {
        require(mask.size == width * height && width >= 3 && height >= 3) { "Invalid mask" }
        val img = ByteArray(mask.size)
        var x0 = width; var x1 = -1; var y0 = height; var y1 = -1
        for (y in 1 until height - 1) for (x in 1 until width - 1) if (mask[y * width + x]) {
            img[y * width + x] = 1; x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
        }
        if (x1 < 0) return null
        val dots = mutableListOf<FloatArray>()
        val seen = BooleanArray(img.size); val stack = IntArray(img.size); val part = IntArray(img.size)
        for (y in y0..y1) for (x in x0..x1) {
            val s = y * width + x
            if (img[s].toInt() == 0 || seen[s]) continue
            var top = 0; var n = 0; stack[top++] = s; seen[s] = true
            var a0 = x; var a1 = x; var b0 = y; var b1 = y
            while (top > 0) {
                val i = stack[--top]; part[n++] = i
                val px = i % width; val py = i / width
                a0 = min(a0, px); a1 = max(a1, px); b0 = min(b0, py); b1 = max(b1, py)
                for (dy in -1..1) for (dx in -1..1) {
                    val j = i + dy * width + dx
                    if (img[j].toInt() != 0 && !seen[j]) { seen[j] = true; stack[top++] = j }
                }
            }
            if (max(a1 - a0, b1 - b0) < dotSize && b1 - b0 < 1.5f * (a1 - a0 + 1)) {
                dots += floatArrayOf((a0 + a1) / 2f, (b0 + b1) / 2f, sqrt(n / PI.toFloat()))
                for (k in 0 until n) img[part[k]] = 0
            }
        }
        thin(img, width, x0, x1, y0, y1)
        val lines = join(trace(img, width, x0, x1, y0, y1, xHeight * .08f), max(2f, xHeight * .06f))
        return Skeleton(lines, dots, x0.toFloat(), y0.toFloat(), x1 + 1f, y1 + 1f, baseline, xHeight)
    }

    /**
     * P416: the traced pieces that meet (tracing splits them at every junction) joined end to end, preferring the
     * straightest way on, so a word is drawn in a few strokes like a pen's rather than many short ones: the same ink,
     * lighter to erase and select. Left to right, as written.
     */
    private fun join(pieces: List<FloatArray>, gap: Float): List<FloatArray> {
        fun reversed(p: FloatArray) = FloatArray(p.size) { val k = p.size / 2 - 1 - it / 2; p[k * 2 + it % 2] }
        val left = pieces.toMutableList(); val out = mutableListOf<FloatArray>()
        while (left.isNotEmpty()) {
            var chain = left.removeAt(0)
            repeat(2) {
                while (true) {
                    val n = chain.size / 2; val ex = chain[n * 2 - 2]; val ey = chain[n * 2 - 1]
                    val back = max(0, n - 5); val dx = ex - chain[back * 2]; val dy = ey - chain[back * 2 + 1]
                    var best = -1; var flip = false; var score = Float.MAX_VALUE
                    left.forEachIndexed { k, p ->
                        val m = p.size / 2
                        for (r in 0..1) {
                            val s = if (r == 0) 0 else m - 1; val sx = p[s * 2]; val sy = p[s * 2 + 1]
                            val d = hypot(sx - ex, sy - ey); if (d > gap) continue
                            val i = if (r == 0) min(m - 1, 4) else max(0, m - 5)
                            val px = p[i * 2] - sx; val py = p[i * 2 + 1] - sy
                            val turn = 1 - (dx * px + dy * py) / (hypot(dx, dy) * hypot(px, py) + 1e-6f)
                            val v = d / gap + turn
                            if (v < score) { score = v; best = k; flip = r == 1 }
                        }
                    }
                    if (best < 0) break
                    val next = left.removeAt(best).let { if (flip) reversed(it) else it }
                    chain += next
                }
                chain = reversed(chain)
            }
            out += chain
        }
        return out.sortedBy { p -> (0 until p.size / 2).minOf { p[it * 2] } }
    }

    private fun thin(img: ByteArray, w: Int, x0: Int, x1: Int, y0: Int, y1: Int) {
        val del = IntArray((x1 - x0 + 1) * (y1 - y0 + 1))
        var changed = true
        fun at(i: Int) = img[i].toInt()
        while (changed) {
            changed = false
            for (pass in 0..1) {
                var n = 0
                for (y in y0..y1) for (x in x0..x1) {
                    val i = y * w + x
                    if (at(i) == 0) continue
                    val p2 = at(i - w); val p3 = at(i - w + 1); val p4 = at(i + 1); val p5 = at(i + w + 1)
                    val p6 = at(i + w); val p7 = at(i + w - 1); val p8 = at(i - 1); val p9 = at(i - w - 1)
                    val b = p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9
                    if (b < 2 || b > 6) continue
                    var a = 0
                    if (p2 == 0 && p3 == 1) a++; if (p3 == 0 && p4 == 1) a++; if (p4 == 0 && p5 == 1) a++; if (p5 == 0 && p6 == 1) a++
                    if (p6 == 0 && p7 == 1) a++; if (p7 == 0 && p8 == 1) a++; if (p8 == 0 && p9 == 1) a++; if (p9 == 0 && p2 == 1) a++
                    if (a != 1) continue
                    if (if (pass == 0) p2 * p4 * p6 != 0 || p4 * p6 * p8 != 0 else p2 * p4 * p8 != 0 || p2 * p6 * p8 != 0) continue
                    del[n++] = i
                }
                for (k in 0 until n) img[del[k]] = 0
                if (n > 0) changed = true
            }
        }
    }

    /**
     * The one-pixel centre lines as polylines from end to end or junction to junction, closed loops (an o) included.
     * A diagonal step counts only where no straight neighbour already links the two pixels, so corners don't look like
     * junctions. Short spurs and the bits inside a junction are dropped; each polyline is smoothed off its pixel steps.
     */
    private fun trace(img: ByteArray, w: Int, x0: Int, x1: Int, y0: Int, y1: Int, spur: Float): List<FloatArray> {
        fun on(i: Int) = img[i].toInt() != 0
        fun neighbours(i: Int, out: IntArray): Int {
            var n = 0
            if (on(i - w)) out[n++] = i - w; if (on(i + 1)) out[n++] = i + 1; if (on(i + w)) out[n++] = i + w; if (on(i - 1)) out[n++] = i - 1
            if (on(i - w - 1) && !on(i - w) && !on(i - 1)) out[n++] = i - w - 1
            if (on(i - w + 1) && !on(i - w) && !on(i + 1)) out[n++] = i - w + 1
            if (on(i + w - 1) && !on(i + w) && !on(i - 1)) out[n++] = i + w - 1
            if (on(i + w + 1) && !on(i + w) && !on(i + 1)) out[n++] = i + w + 1
            return n
        }
        val buf = IntArray(8)
        fun degree(i: Int) = neighbours(i, buf)
        val visited = HashSet<Long>()
        fun edge(a: Int, b: Int) = min(a, b).toLong() * img.size + max(a, b)
        val paths = mutableListOf<IntArray>()
        fun walk(a: Int, b: Int) {
            val path = mutableListOf(a, b); var cur = b
            val next = IntArray(8)
            while (degree(cur) == 2) {
                val n = neighbours(cur, next)
                var step = -1
                for (k in 0 until n) if (!visited.contains(edge(cur, next[k]))) { step = next[k]; break }
                if (step < 0) break
                visited += edge(cur, step); path += step; cur = step
            }
            paths += path.toIntArray()
        }
        val pixels = mutableListOf<Int>()
        for (y in y0..y1) for (x in x0..x1) { val i = y * w + x; if (on(i)) pixels += i }
        val start = IntArray(8)
        for (pass in 0..1) pixels.forEach { p ->
            val d = degree(p)
            if (d == 0 || (pass == 0) == (d == 2)) return@forEach
            val n = neighbours(p, start)
            for (k in 0 until n) if (visited.add(edge(p, start[k]))) walk(p, start[k])
        }
        return paths.mapNotNull { path ->
            val ends = degree(path.first()) to degree(path.last())
            val length = (1 until path.size).sumOf { k -> hypot(((path[k] % w) - (path[k - 1] % w)).toDouble(), ((path[k] / w) - (path[k - 1] / w)).toDouble()) }
            val junctionBit = path.size < 3 && ends.first >= 3 && ends.second >= 3
            val shortSpur = length < spur && (ends.first == 1) != (ends.second == 1) && path.first() != path.last()
            if (junctionBit || shortSpur) return@mapNotNull null
            val xs = FloatArray(path.size) { (path[it] % w).toFloat() }; val ys = FloatArray(path.size) { (path[it] / w).toFloat() }
            repeat(2) { soften(xs); soften(ys) }
            FloatArray(path.size * 2) { if (it % 2 == 0) xs[it / 2] else ys[it / 2] }
        }
    }
    private fun soften(v: FloatArray) {
        if (v.size < 3) return
        var prev = v[0]
        for (i in 1 until v.size - 1) { val here = v[i]; v[i] = (prev + 2 * here + v[i + 1]) / 4; prev = here }
    }

    // ---- placing the hand on the writer's words ----

    private class Box(val x0: Float, val x1: Float, val y0: Float, val y1: Float)
    private fun box(strokes: List<InkStroke>) = Box(strokes.minOf { s -> s.samples.minOf { it.x } }, strokes.maxOf { s -> s.samples.maxOf { it.x } },
        strokes.minOf { s -> s.samples.minOf { it.y } }, strokes.maxOf { s -> s.samples.maxOf { it.y } })

    /** Where each column of the hand lands in the writer's word ([xu]), how tall the writer made it ([a]) and how high ([b]). */
    private class Maps(val c: Float, val fx0: Float, val n: Int, val xu: FloatArray, val a: FloatArray, val b: FloatArray, val baseLine: Float) {
        /** A point of the hand moved [p] of the way from the writer's spacing to even, and [q] from their letter heights to level. */
        fun place(px: Float, py: Float, p: Float, q: Float = p): Point {
            val t = ((px - fx0) / c - .5f).coerceIn(0f, (n - 1).toFloat()); val i = t.toInt(); val u = t - i; val j = min(n - 1, i + 1)
            val xw = lerp(xu[i], xu[j], u); val aw = lerp(a[i], a[j], u); val bw = lerp(b[i], b[j], u)
            return Point(lerp(xw, px, p), baseLine + lerp(aw, 1f, q) * (py - baseLine) + lerp(bw, 0f, q))
        }
    }
    private class Limits(val sx: Float, val sab: Float, val lo: Float, val hi: Float, val amin: Float, val amax: Float, val bmax: Float)
    // Coarse follows syllables (the hand's own letters at the writer's rhythm); fine follows each letter.
    private val COARSE = Limits(.5f, .9f, .75f, 1.35f, .7f, 1.45f, .5f)
    private val FINE = Limits(.3f, .45f, .7f, 1.45f, .65f, 1.6f, .6f)

    private class Fit(val strokes: List<InkStroke>, val sk: Skeleton, val user: Box, val s: Float, val cx: Float, val cy: Float, val base: Float, var xh: Float) {
        var baseLine = base
        var dy = 0f
        lateinit var lines: List<FloatArray> // page coordinates
        lateinit var dots: List<FloatArray>
        lateinit var ux: FloatArray; lateinit var uy: FloatArray
        var lean = 0f
        lateinit var coarse: Maps; lateinit var fine: Maps
        lateinit var wobble: List<FloatArray>
    }

    /**
     * Each word of one line, given with the hand's [Skeleton] for its text, rewritten as polylines in page coordinates
     * (dots as short spirals), ready for the writer's pen. With [Settings.level] the words are set on [row], or on the
     * line's own [rowBase] when it isn't given (P416: a line rewritten a few words at a time keeps its first baseline).
     */
    fun restyleLine(words: List<Pair<List<InkStroke>, Skeleton>>, settings: Settings, row: Float? = null, sharedHeights: Boolean = false): List<List<List<Point>>> {
        if (words.isEmpty()) return emptyList()
        val fits = words.map { (strokes, sk) -> fit(strokes, sk) }
        val rowBase = row ?: median(fits.map { it.base })
        val rowHeight = median(fits.map { it.xh })
        val heightMix = 1 - (1 - settings.rhythm) * (1 - settings.steady)
        fits.forEachIndexed { index, f ->
            f.dy = if (settings.level) rowBase - f.base else 0f
            f.baseLine = f.base + f.dy
            // Selection Refine can level lowercase bodies across the row. Keep the default path bit-exact.
            val sy = if (sharedHeights && heightMix > 0f) lerp(f.s, rowHeight / f.sk.xHeight.coerceAtLeast(.5f), heightMix) else f.s
            if (sy == f.s) {
                f.lines = f.sk.lines.map { l -> FloatArray(l.size) { if (it % 2 == 0) f.cx + l[it] * f.s else f.cy + l[it] * f.s + f.dy } }
                f.dots = f.sk.dots.map { d -> floatArrayOf(f.cx + d[0] * f.s, f.cy + d[1] * f.s + f.dy, d[2] * f.s) }
            } else {
                f.xh = max(.5f, f.sk.xHeight * sy)
                f.lines = f.sk.lines.map { l -> FloatArray(l.size) { if (it % 2 == 0) f.cx + l[it] * f.s else f.baseLine + (l[it] - f.sk.baseline) * sy } }
                f.dots = f.sk.dots.map { d -> floatArrayOf(f.cx + d[0] * f.s, f.baseLine + (d[1] - f.sk.baseline) * sy, d[2] * min(f.s, sy)) }
            }
            val ux = ArrayList<Float>(); val uy = ArrayList<Float>()
            f.strokes.forEach { s -> resample(s.samples.map { Point(it.x, it.y) }, f.xh * .05f).forEach { ux += it.x; uy += it.y } }
            f.ux = ux.toFloatArray(); f.uy = uy.toFloatArray()
            f.wobble = wobble(index + 1, f.xh)
        }
        // Your slant, and the hand's; the hand is leant to yours.
        val ux = ArrayList<Float>(); val uh = ArrayList<Float>(); val fx = ArrayList<Float>(); val fh = ArrayList<Float>()
        fits.forEach { f ->
            for (k in f.ux.indices) { ux += f.ux[k]; uh += f.baseLine - f.uy[k] }
            f.lines.forEach { l -> for (k in l.indices step 2) { fx += l[k]; fh += f.baseLine - l[k + 1] } }
        }
        val bin = median(fits.map { it.xh }) * .1f
        val lean = (slantOf(ux, uh, bin) - slantOf(fx, fh, bin)).coerceIn(-.5f, .5f)
        return fits.map { f ->
            f.lean = lean
            val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val sx = ArrayList<Float>()
            f.lines.forEach { l -> for (k in l.indices step 2) { xs += l[k]; ys += l[k + 1]; sx += l[k] + lean * (f.baseLine - l[k + 1]) } }
            f.coarse = maps(f, xs, ys, COARSE, settings.level)
            f.fine = maps(f, sx, ys, FINE, settings.level)
            val t = settings.letters; val p = settings.rhythm
            // Even rhythm levels the letters too; Level alone keeps the writer's spacing.
            val q = 1 - (1 - p) * (1 - settings.steady)
            fun at(x: Float, y: Float): Point {
                val a = f.fine.place(x + lean * (f.baseLine - y), y, p, q); val c = f.coarse.place(x, y, p, q); val w = wobbleAt(f, x, y)
                return Point(lerp(a.x, c.x, t) + w.x * (1 - t), lerp(a.y, c.y, t) + w.y * (1 - t))
            }
            val width = f.strokes.first().width
            val step = max(.3f, f.xh * .04f)
            val lines = f.lines.map { l -> resample(List(l.size / 2) { at(l[it * 2], l[it * 2 + 1]) }, step) }.filter { it.size >= 2 }
            val dots = f.dots.map { d -> spiral(at(d[0], d[1]), max(width * .52f, d[2] * .8f), width) }
            lines + dots
        }
    }

    /** The baseline the line's words are levelled to: the middle of their own. */
    fun rowBase(words: List<Pair<List<InkStroke>, Skeleton>>): Float = median(words.map { (strokes, sk) -> fit(strokes, sk).base })

    private fun fit(strokes: List<InkStroke>, sk: Skeleton): Fit {
        val b = box(strokes)
        val rw = max(1f, sk.right - sk.left); val rh = max(1f, sk.bottom - sk.top)
        // Your word's size, but hardly wider than you wrote it, so it can't run into its neighbours.
        val wf = max(4f, b.x1 - b.x0) / rw
        val s = min(wf * 1.15f, sqrt(wf * max(4f, b.y1 - b.y0) / rh))
        val cx = (b.x0 + b.x1) / 2 - (sk.left + sk.right) / 2 * s; val cy = (b.y0 + b.y1) / 2 - (sk.top + sk.bottom) / 2 * s
        return Fit(strokes, sk, b, s, cx, cy, cy + sk.baseline * s, max(.5f, sk.xHeight * s))
    }

    private fun maps(f: Fit, xs: List<Float>, ys: List<Float>, o: Limits, level: Boolean): Maps {
        val c = f.xh * .12f; val ub = f.user
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE
        xs.forEach { x0 = min(x0, it); x1 = max(x1, it) }
        if (xs.isEmpty()) { x0 = ub.x0; x1 = ub.x1 }
        val nF = max(2, ceil((x1 - x0) / c).toInt()); val nU = max(2, ceil((ub.x1 - ub.x0) / c).toInt())
        val F = columns(xs, ys, x0, nF, c, f.baseLine, f.xh)
        val U = columns(f.ux.asList(), f.uy.asList(), ub.x0, nU, c, f.baseLine, f.xh)
        val j = align(F, U)
        val xu = smooth(FloatArray(nF) { ub.x0 + (j[it] + .5f) * c }, FloatArray(nF) { 1f }, o.sx / .12f)
        // A letter may be widened or narrowed, never squeezed out of shape; then the word keeps the writer's centre.
        for (i in 1 until nF) xu[i] = xu[i - 1] + (xu[i] - xu[i - 1]).coerceIn(o.lo * c, o.hi * c)
        val shift = (ub.x0 + ub.x1) / 2 - (xu[0] + xu[nF - 1]) / 2
        for (i in 0 until nF) xu[i] += shift
        val a = FloatArray(nF); val b = FloatArray(nF); val wt = FloatArray(nF)
        for (i in 0 until nF) {
            val k = j[i].roundToInt().coerceIn(0, nU - 1)
            if (F.top[i] < Float.MAX_VALUE && U.top[k] < Float.MAX_VALUE && F.bot[i] - F.top[i] > .25f) {
                a[i] = ((U.bot[k] - U.top[k]) / (F.bot[i] - F.top[i])).coerceIn(o.amin, o.amax)
                b[i] = (U.bot[k] - a[i] * F.bot[i]).coerceIn(-o.bmax, o.bmax) * f.xh; wt[i] = 1f
            }
        }
        val sa = smooth(a, wt, o.sab / .12f); val sb = smooth(b, wt, o.sab / .12f)
        for (i in 0 until nF) { if (sa[i].isNaN()) sa[i] = 1f; if (sb[i].isNaN()) sb[i] = 0f }
        if (level) {
            var total = 0f; var n = 0
            for (i in 0 until nF) if (wt[i] > 0f) { total += sb[i]; n++ }
            val mean = if (n > 0) total / n else 0f
            for (i in 0 until nF) sb[i] -= mean
        }
        return Maps(c, x0, nF, xu, sa, sb, f.baseLine)
    }

    private class Columns(val top: FloatArray, val bot: FloatArray, val n: Int)
    /** Top and bottom of the ink in each column, in x-heights above (−) or below (+) the baseline. */
    private fun columns(xs: List<Float>, ys: List<Float>, x0: Float, n: Int, c: Float, base: Float, xh: Float): Columns {
        val top = FloatArray(n) { Float.MAX_VALUE }; val bot = FloatArray(n) { -Float.MAX_VALUE }
        for (k in xs.indices) {
            val i = floor((xs[k] - x0) / c).toInt(); if (i < 0 || i >= n) continue
            val y = (ys[k] - base) / xh; if (y < top[i]) top[i] = y; if (y > bot[i]) bot[i] = y
        }
        return Columns(top, bot, n)
    }

    /** Lines up the hand's columns with the writer's, left to right (dynamic time warping), so each letter meets its letter. */
    private fun align(F: Columns, U: Columns): FloatArray {
        val n = F.n; val m = U.n; val w = m + 1
        val cost = FloatArray((n + 1) * w) { Float.POSITIVE_INFINITY }; cost[0] = 0f
        fun dist(i: Int, j: Int): Float {
            val fp = F.top[i] < Float.MAX_VALUE; val up = U.top[j] < Float.MAX_VALUE
            return if (fp && up) min(3f, abs(F.top[i] - U.top[j]) + abs(F.bot[i] - U.bot[j])) else if (fp == up) 0f else 1.2f
        }
        for (i in 1..n) for (j in 1..m) cost[i * w + j] = dist(i - 1, j - 1) + min(cost[(i - 1) * w + j - 1], min(cost[(i - 1) * w + j] + .15f, cost[i * w + j - 1] + .15f))
        val sum = FloatArray(n); val count = FloatArray(n)
        var i = n; var j = m
        while (i > 0 && j > 0) {
            sum[i - 1] += (j - 1).toFloat(); count[i - 1]++
            val d = cost[(i - 1) * w + j - 1]; val u = cost[(i - 1) * w + j] + .15f; val l = cost[i * w + j - 1] + .15f
            if (d <= u && d <= l) { i--; j-- } else if (u <= l) i-- else j--
        }
        return FloatArray(n) { if (count[it] > 0f) sum[it] / count[it] else it.toFloat() * m / n }
    }

    private fun smooth(v: FloatArray, w: FloatArray, sigma: Float): FloatArray {
        val r = ceil(sigma * 2.5f).toInt()
        return FloatArray(v.size) { i ->
            var s = 0f; var t = 0f
            for (k in max(0, i - r)..min(v.size - 1, i + r)) { val g = exp(-((k - i) * (k - i)) / (2 * sigma * sigma)) * w[k]; s += g * v[k]; t += g }
            if (t > 1e-6f) s / t else Float.NaN
        }
    }

    /** The lean at which strokes stack up most sharply: x drifts by the lean for every unit of height. */
    internal fun slantOf(xs: List<Float>, hs: List<Float>, bin: Float): Float {
        if (xs.isEmpty() || bin <= 0f) return 0f
        var best = 0f; var top = -1L
        val counts = HashMap<Int, Int>()
        for (step in -12..12) {
            val a = step * .05f; counts.clear()
            for (k in xs.indices) { val b = ((xs[k] - a * hs[k]) / bin).roundToInt(); counts[b] = (counts[b] ?: 0) + 1 }
            val score = counts.values.sumOf { it.toLong() * it }
            if (score > top) { top = score; best = a }
        }
        return best
    }

    /** A few long, soft waves per word (about 0.035 x-height), so the strokes aren't machine-straight. */
    private fun wobble(seed: Int, xh: Float): List<FloatArray> {
        val random = Random(seed)
        return List(4) {
            val d = random.nextFloat() * 6.283f
            floatArrayOf(cos(d), sin(d), xh * (.6f + .8f * random.nextFloat()), random.nextFloat() * 6.283f, random.nextFloat() - .5f, random.nextFloat() - .5f)
        }
    }
    private fun wobbleAt(f: Fit, x: Float, y: Float): Point {
        var dx = 0f; var dy = 0f
        f.wobble.forEach { w -> val v = sin((x * w[0] + y * w[1]) * 6.283f / w[2] + w[3]); dx += w[4] * v; dy += w[5] * v }
        val k = f.xh * .035f
        return Point(dx * k, dy * k)
    }

    /** A filled dot of [radius] drawn by a pen of [width]: a short spiral in, or a single touch when the pen covers it. */
    private fun spiral(center: Point, radius: Float, width: Float): List<Point> {
        val outer = radius - width / 2
        if (outer <= width * .15f) return listOf(center, Point(center.x + .05f, center.y))
        val turns = max(1f, outer / (width * .4f)); val n = (turns * 16).toInt()
        return List(n + 1) { k -> val t = k.toFloat() / n; val r = outer * (1 - t); val a = t * turns * 6.283f; Point(center.x + r * cos(a), center.y + r * sin(a)) }
    }

    internal fun resample(points: List<Point>, step: Float): List<Point> {
        if (points.size < 2 || step <= 0f) return points
        val out = mutableListOf(points[0]); var px = points[0].x; var py = points[0].y; var acc = 0f
        for (k in 1 until points.size) {
            val x = points[k].x; val y = points[k].y
            var d = hypot(x - px, y - py)
            while (acc + d >= step && d > 0f) {
                val t = (step - acc) / d; px += (x - px) * t; py += (y - py) * t; out += Point(px, py); d = hypot(x - px, y - py); acc = 0f
            }
            acc += d; px = x; py = y
        }
        if (out.last() != points.last()) out += points.last()
        return out
    }

    private fun median(values: List<Float>) = values.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
