package dev.ownednotes.core

import kotlin.math.*

/**
 * P413 R1 (docs/AUTO-REFINE-DESIGN.md): tidies finished handwriting on the device, measured only from the writing itself.
 *
 * Level 1 moves each word as a rigid piece, so letters keep their shapes: the line is levelled and, on ruled paper, sits on
 * the nearest rule; words stop bouncing; x-height, slant and word gaps move part of the way toward the line's own median.
 * Every step has a limit, and a word far outside it (capitals, a heading, a superscript, a column gap) counts as deliberate
 * and is left alone. Level 2 cuts the tick a pen makes landing or lifting and smooths tremor between pinned corners.
 *
 * Strokes the pen engine must redraw come back [Refined.reshaped]; the others carry exactly transformed outlines.
 */
object HandwritingRefine {
    /** How far each correction goes: 0 leaves that property as written, 1 goes all the way to the line's median. */
    enum class Strength(val size: Float, val slant: Float, val spacing: Float, val smoothing: Float) {
        GENTLE(.5f, 0f, .5f, 0f), NATURAL(.7f, .6f, .6f, .035f), NEAT(1f, 1f, 1f, .06f)
    }
    data class Refined(val stroke: InkStroke, val reshaped: Boolean)
    data class Result(val strokes: List<Refined>, val lines: Int, val words: Int)

    /** Highlights, tape, recognised shapes, locked ink and linked ink are never refined. */
    fun eligible(stroke: InkStroke) = !stroke.isHighlighter && !stroke.isTape && stroke.shapeKind == null && !stroke.locked &&
        stroke.link == null && stroke.samples.isNotEmpty()

    private const val MAX_SKEW = 10.0 // degrees; a steeper line was written at an angle on purpose
    private const val MAX_SIZE_STEP = .12f
    private const val DELIBERATE_SIZE = 1.3f
    private const val MAX_SLANT = 8.0 // degrees
    private const val DELIBERATE_SLANT = 20.0
    private const val DELIBERATE_LINE = 2.2f // times the selection's median stroke height
    private const val DELIBERATE_HEIGHT = 3.5f // a "word" this many x-heights tall is a drawing inside the line
    private const val WORD_GAP = .7f // in x-heights; letters printed apart sit closer than this

    fun refine(strokes: List<InkStroke>, paper: PaperStyle = PaperStyle.DEFAULT, strength: Strength = Strength.NATURAL): Result {
        val pens = strokes.filter(::eligible)
        if (pens.isEmpty()) return Result(emptyList(), 0, 0)
        val lines = HandwritingLines.split(pens.map { s -> s.samples.map { Point(it.x, it.y) } })
        val moves = HashMap<String, Affine>(); val units = HashMap<String, Float>()
        var words = 0; var refinedLines = 0
        val usual = medianHeight(pens)
        lines.forEach { line ->
            val members = line.strokeIndices.map { pens[it] }
            // Far larger than the rest of the selection: a drawing, an arrow or a heading, kept as drawn.
            if (usual > 0f && medianHeight(members) > usual * DELIBERATE_LINE) return@forEach
            val plan = planLine(members, paper, strength) ?: return@forEach
            refinedLines++; words += plan.words
            members.forEach { s -> plan.moves[s.id]?.let { moves[s.id] = it }; units[s.id] = plan.unit }
        }
        val result = pens.mapNotNull { stroke ->
            val move = moves[stroke.id] ?: Affine()
            val placed = stroke.samples.map { move.sample(it) }
            val cleaned = if (strength.smoothing > 0f && stroke.erasures.isEmpty()) cleanup(placed, units[stroke.id] ?: 0f, strength.smoothing) else placed
            val reshaped = cleaned.size != placed.size || cleaned.indices.any { i ->
                abs(cleaned[i].x - placed[i].x) > .02f || abs(cleaned[i].y - placed[i].y) > .02f || abs(cleaned[i].pressure - placed[i].pressure) > .01f
            }
            if (!reshaped && move.isIdentity) return@mapNotNull null
            Refined(stroke.copy(samples = if (reshaped) cleaned else placed,
                outlines = stroke.outlines.map { o -> o.map(move::apply) },
                erasures = stroke.erasures.map { move.erasure(it) }), reshaped)
        }
        return Result(result, refinedLines, words)
    }

    // ---- Level 1: one line ----

    private class Plan(val moves: Map<String, Affine>, val unit: Float, val words: Int)
    private class Word(val strokes: MutableList<InkStroke>, var left: Float, var right: Float)

    private fun planLine(line: List<InkStroke>, paper: PaperStyle, strength: Strength): Plan? {
        val heights = line.map { s -> s.samples.maxOf { it.y } - s.samples.minOf { it.y } }.filter { it > 0f }.sorted()
        if (heights.isEmpty()) return null
        val typical = heights[(heights.size * 4 + 4) / 5 - 1]
        // Turns smaller than this are wiggles inside a letter (an e's loop, a join); counting them shrank the measured
        // x-height to about two thirds on real pages.
        val delta = typical * .22f

        // Level the line: a straight line through the letters' bottoms, ignoring descenders.
        val rawBottoms = line.flatMap { s -> extrema(s.samples.map { Point(it.x, it.y) }, delta).first }
        val left = line.minOf { s -> s.samples.minOf { it.x } }; val right = line.maxOf { s -> s.samples.maxOf { it.x } }
        val level = fitBaseline(rawBottoms, typical)?.takeIf { right - left >= typical * 4f }?.let { (m, k) ->
            val angle = atan(m.toDouble())
            if (abs(Math.toDegrees(angle)) > MAX_SKEW || abs(m) < 1e-4f) null
            else { val px = (left + right) / 2; Affine.rotate(-angle, px, m * px + k) }
        } ?: Affine()

        // Measure in levelled coordinates.
        val rules = line.filter { s -> width(s, level) > typical * 4f && height(s, level) < typical * .35f }.map { it.id }.toSet()
        val body = line.filter { it.id !in rules }
        if (body.isEmpty()) return null
        val turns = body.associate { s -> s.id to extrema(s.samples.map { level.apply(Point(it.x, it.y)) }, delta) }
        val allBottoms = turns.values.flatMap { it.first }.map { it.y }.sorted(); val allTops = turns.values.flatMap { it.second }.map { it.y }.sorted()
        // Too few letters to measure a line from (a mark, a lone arrow): left as written.
        if (allBottoms.size < 2 || allTops.size < 2 || (body.size < 3 && allBottoms.size < 6)) return null
        val lineX = quantile(allBottoms, .5f) - quantile(allTops, .5f)
        if (lineX <= typical * .15f) return null

        val words = words(body, level, lineX)
        class Measure(val baseline: Float, val xHeight: Float?, val slant: Double?)
        val measures = words.map { w ->
            val bottoms = w.strokes.flatMap { turns.getValue(it.id).first }.map { it.y }.sorted()
            val tops = w.strokes.flatMap { turns.getValue(it.id).second }.map { it.y }.sorted()
            val baseline = if (bottoms.isEmpty()) Float.NaN else quantile(bottoms, .35f)
            val xh = if (bottoms.isEmpty() || tops.isEmpty()) null else (quantile(bottoms, .5f) - quantile(tops, .5f)).takeIf { it > lineX * .3f }
            Measure(baseline, xh, slant(w.strokes.map { s -> s.samples.map { level.apply(Point(it.x, it.y)) } }, lineX))
        }
        val known = measures.filter { !it.baseline.isNaN() }
        if (known.isEmpty()) return Plan(line.associate { it.id to level }, lineX, words.size)
        val median = quantile(known.map { it.baseline }.sorted(), .5f)
        var target = median
        val xTarget = measures.mapNotNull { it.xHeight }.sorted().takeIf { it.isNotEmpty() }?.let { quantile(it, .5f) } ?: lineX
        val slantTarget = measures.mapNotNull { it.slant }.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
        // Ruled paper: rest the baseline on the nearest rule when the writing fits between the rules.
        if (paper.pattern == PaperPattern.RULED && xTarget < paper.spacing * 1.1f) {
            val rule = (target / paper.spacing).roundToInt() * paper.spacing
            if (rule > 0f && abs(rule - target) < paper.spacing * .45f) target = rule
        }

        // The whole line moves by [lift]; a word also drops its own bounce, unless it sits far off the line on purpose.
        val lift = target - median
        val wordMoves = words.mapIndexed { i, w ->
            val m = measures[i]
            if (m.baseline.isNaN() || w.strokes.maxOf { s -> s.samples.maxOf { level.y(it.x, it.y) } } -
                w.strokes.minOf { s -> s.samples.minOf { level.y(it.x, it.y) } } > xTarget * DELIBERATE_HEIGHT) return@mapIndexed Affine.translate(0f, lift)
            val anchorX = (w.left + w.right) / 2
            val drift = lift + ((median - m.baseline).takeIf { abs(it) <= xTarget * .6f } ?: 0f)
            val ratio = m.xHeight?.let { xTarget / it } ?: 1f
            val scale = if (ratio !in 1f / DELIBERATE_SIZE..DELIBERATE_SIZE) 1f else 1f + strength.size * (ratio.coerceIn(1 - MAX_SIZE_STEP, 1 + MAX_SIZE_STEP) - 1f)
            val lean = if (m.slant == null || slantTarget == null || abs(Math.toDegrees(m.slant - slantTarget)) > DELIBERATE_SLANT) 0.0
                else (m.slant - slantTarget).coerceIn(-Math.toRadians(MAX_SLANT), Math.toRadians(MAX_SLANT)) * strength.slant
            val shear = (tan(m.slant ?: 0.0) - tan((m.slant ?: 0.0) - lean)).toFloat()
            Affine.translate(0f, drift)
                .after(Affine.scale(scale, anchorX, m.baseline))
                .after(Affine.shearX(-shear, m.baseline))
        }
        // Even word gaps, left to right; the first word stays where it was written. A gap beside a mark (a hyphen,
        // quote or dot) stays as written, so dates and quotes keep their shape.
        val placed = words.mapIndexed { i, w ->
            val move = wordMoves[i].after(level)
            val xs = w.strokes.flatMap { s -> s.samples.map { move.x(it.x, it.y) } }
            val ys = w.strokes.flatMap { s -> s.samples.map { move.y(it.x, it.y) } }
            Triple(xs.min(), xs.max(), xs.max() - xs.min() < xTarget * .7f && ys.max() - ys.min() < xTarget * .7f)
        }
        val gaps = (1 until placed.size).map { placed[it].first - placed[it - 1].second }
        val shifts = FloatArray(words.size)
        if (gaps.size >= 2) {
            val median = quantile(gaps.sorted(), .5f)
            var shift = 0f
            gaps.forEachIndexed { i, gap ->
                if (median > 0f && gap in 0f..median * 3f && !placed[i].third && !placed[i + 1].third) {
                    val wanted = (gap + strength.spacing * (median - gap)).coerceAtLeast(min(gap, xTarget * WORD_GAP))
                    shift += wanted - gap
                }
                shifts[i + 1] = shift
            }
        }
        val moves = HashMap<String, Affine>()
        words.forEachIndexed { i, w -> val move = Affine.translate(shifts[i], 0f).after(wordMoves[i]).after(level); w.strokes.forEach { moves[it.id] = move } }
        line.filter { it.id in rules }.forEach { moves[it.id] = Affine.translate(0f, lift).after(level) }
        return Plan(moves, xTarget, words.size)
    }

    /** Strokes grouped into words by horizontal gaps; the word gap is found from the line's own gaps (two-class split). */
    private fun words(body: List<InkStroke>, level: Affine, xHeight: Float): List<Word> {
        val boxes = body.map { s -> val xs = s.samples.map { level.x(it.x, it.y) }; Triple(s, xs.min(), xs.max()) }.sortedBy { it.second }
        fun group(threshold: Float): List<Word> = buildList {
            boxes.forEach { (s, l, r) ->
                val last = lastOrNull()
                if (last != null && l - last.right <= threshold) { last.strokes += s; last.right = max(last.right, r) }
                else add(Word(mutableListOf(s), l, r))
            }
        }
        val letters = group(xHeight * .15f)
        val gaps = (1 until letters.size).map { letters[it].left - letters[it - 1].right }.sorted()
        var threshold = xHeight * .8f
        if (gaps.size >= 3) {
            var best = -1f; var split = -1
            for (k in 1 until gaps.size) {
                val a = gaps.subList(0, k); val b = gaps.subList(k, gaps.size)
                val ma = a.average().toFloat(); val mb = b.average().toFloat()
                val between = a.size.toFloat() * b.size * (mb - ma) * (mb - ma)
                if (between > best) { best = between; split = k }
            }
            val ma = gaps.subList(0, split).average().toFloat(); val mb = gaps.subList(split, gaps.size).average().toFloat()
            // Only a split into small letter gaps and clearly larger word gaps counts; uneven word gaps alone are not letters.
            if (ma < xHeight * .5f && mb > max(ma, 0f) * 1.8f + xHeight * .1f) threshold = ((gaps[split - 1] + gaps[split]) / 2).coerceIn(xHeight * WORD_GAP, xHeight * 1.4f)
        }
        return group(threshold)
    }

    /** Theil–Sen line y = m x + k through [points]: the median slope of well-separated pairs, so descenders and one
     * bouncing word don't tip the line. */
    private fun fitBaseline(points: List<Point>, unit: Float): Pair<Float, Float>? {
        if (points.size < 4) return null
        val p = if (points.size <= 120) points else points.sortedBy { it.x }.let { s -> List(120) { s[it * s.size / 120] } }
        val slopes = buildList { for (i in p.indices) for (j in i + 1 until p.size) { val dx = p[j].x - p[i].x; if (abs(dx) >= unit) add((p[j].y - p[i].y) / dx) } }.sorted()
        if (slopes.size < 3) return null
        val m = quantile(slopes, .5f)
        return m to quantile(p.map { it.y - m * it.x }.sorted(), .5f)
    }

    /** Turning points ignoring wiggles below [delta]: the bottoms (y-down maxima) and tops. Flat marks have none. */
    internal fun extrema(points: List<Point>, delta: Float): Pair<List<Point>, List<Point>> {
        val bottoms = mutableListOf<Point>(); val tops = mutableListOf<Point>()
        if (points.size < 2) return bottoms to tops
        var dir = 0; var lowI = 0; var highI = 0; var cand = 0
        for (i in 1 until points.size) {
            val y = points[i].y
            when (dir) {
                0 -> {
                    if (y < points[lowI].y) lowI = i; if (y > points[highI].y) highI = i
                    if (points[highI].y - points[lowI].y > delta) {
                        if (lowI < highI) { tops += points[lowI]; dir = 1; cand = highI } else { bottoms += points[highI]; dir = -1; cand = lowI }
                    }
                }
                1 -> if (y > points[cand].y) cand = i else if (points[cand].y - y > delta) { bottoms += points[cand]; dir = -1; cand = i }
                else -> if (y < points[cand].y) cand = i else if (y - points[cand].y > delta) { tops += points[cand]; dir = 1; cand = i }
            }
        }
        if (dir == 1) bottoms += points[cand] else if (dir == -1) tops += points[cand]
        return bottoms to tops
    }

    /** Length-weighted median lean of the near-upright pieces of a word, radians from vertical (right lean positive). */
    internal fun slant(strokes: List<List<Point>>, unit: Float): Double? {
        val step = unit * .1f
        val pieces = mutableListOf<Pair<Double, Float>>()
        strokes.forEach { points ->
            var last = points.firstOrNull() ?: return@forEach
            points.forEach { p ->
                var dx = p.x - last.x; var dy = p.y - last.y
                val length = hypot(dx, dy)
                if (length < step) return@forEach
                if (dy > 0) { dx = -dx; dy = -dy }
                val angle = atan2(dx.toDouble(), -dy.toDouble())
                if (abs(angle) < Math.toRadians(40.0)) pieces += angle to length
                last = p
            }
        }
        if (pieces.sumOf { it.second.toDouble() } < unit * .8f) return null
        val sorted = pieces.sortedBy { it.first }; val half = sorted.sumOf { it.second.toDouble() } / 2
        var sum = 0.0
        return sorted.first { sum += it.second; sum >= half }.first
    }

    private fun medianHeight(strokes: List<InkStroke>) = strokes.map { s -> s.samples.maxOf { it.y } - s.samples.minOf { it.y } }
        .filter { it > 0f }.sorted().let { if (it.isEmpty()) 0f else quantile(it, .5f) }
    private fun width(s: InkStroke, a: Affine) = s.samples.map { a.x(it.x, it.y) }.let { it.max() - it.min() }
    private fun height(s: InkStroke, a: Affine) = s.samples.map { a.y(it.x, it.y) }.let { it.max() - it.min() }
    private fun quantile(sorted: List<Float>, q: Float): Float {
        val at = q * (sorted.size - 1); val i = at.toInt().coerceIn(0, sorted.size - 1); val j = min(i + 1, sorted.size - 1)
        return sorted[i] + (sorted[j] - sorted[i]) * (at - i)
    }

    // ---- Level 2: one stroke ----

    /** Cuts landing/lifting hooks and smooths tremor between corners; [unit] is the line's x-height. */
    internal fun cleanup(samples: List<Sample>, unit: Float, strength: Float): List<Sample> {
        if (unit <= 0f || samples.size < 6) return samples
        val hooked = trimHook(trimHook(samples, unit).asReversed(), unit).asReversed()
        return smooth(hooked, unit * strength, unit * .1f)
    }

    private fun cumulative(s: List<Sample>) = FloatArray(s.size).also { c -> for (i in 1 until s.size) c[i] = c[i - 1] + hypot(s[i].x - s[i - 1].x, s[i].y - s[i - 1].y) }

    /** A short start that doubles back sharply (over 110°) on the rest of the stroke is the pen's landing tick. */
    private fun trimHook(s: List<Sample>, unit: Float): List<Sample> {
        val c = cumulative(s); val length = c.last()
        if (length < unit * 1.2f) return s
        val limit = min(unit * .18f, length * .15f)
        val sharp = Math.toRadians(110.0)
        var best = -1; var bestTurn = sharp
        var k = 1
        while (k < s.size - 1 && c[k] <= limit) {
            val j = (k + 1 until s.size).firstOrNull { c[it] - c[k] >= limit } ?: break
            val turn = turn(s[0], s[k], s[j])
            // Nearly equal turns prefer the later point, so the whole tick goes.
            if (c[k] > 0f && turn > sharp && turn >= bestTurn - .05) { bestTurn = max(bestTurn, turn); best = k }
            k++
        }
        return if (best > 0 && s.size - best >= 4) s.subList(best, s.size) else s
    }

    private fun turn(a: Sample, b: Sample, c: Sample): Double {
        val ux = b.x - a.x; val uy = b.y - a.y; val vx = c.x - b.x; val vy = c.y - b.y
        val lu = hypot(ux, uy); val lv = hypot(vx, vy)
        if (lu == 0f || lv == 0f) return 0.0
        return acos(((ux * vx + uy * vy) / (lu * lv)).toDouble().coerceIn(-1.0, 1.0))
    }

    /** Gaussian smoothing by arc length that never crosses a pinned point: the ends and every corner sharper than 55°. */
    private fun smooth(s: List<Sample>, sigma: Float, reach: Float): List<Sample> {
        if (sigma <= 0f || s.size < 5) return s
        val c = cumulative(s)
        fun at(target: Float): Int { var i = 0; while (i < c.size - 1 && c[i] < target) i++; return i }
        val pinned = BooleanArray(s.size); pinned[0] = true; pinned[s.size - 1] = true
        for (i in 1 until s.size - 1) {
            val back = (i downTo 0).firstOrNull { c[i] - c[it] >= reach } ?: continue
            val ahead = (i until s.size).firstOrNull { c[it] - c[i] >= reach } ?: continue
            if (turn(s[back], s[i], s[ahead]) > Math.toRadians(55.0)) pinned[i] = true
        }
        val out = s.toMutableList()
        var start = 0
        while (start < s.size - 1) {
            val end = (start + 1 until s.size).first { pinned[it] }
            for (i in start + 1 until end) {
                var wx = 0.0; var wy = 0.0; var wp = 0.0; var total = 0.0
                for (j in start..end) {
                    val d = c[j] - c[i]; if (abs(d) > sigma * 3) continue
                    val w = exp(-(d * d) / (2.0 * sigma * sigma))
                    wx += w * s[j].x; wy += w * s[j].y; wp += w * s[j].pressure; total += w
                }
                out[i] = s[i].copy(x = (wx / total).toFloat(), y = (wy / total).toFloat(), pressure = (wp / total).toFloat())
            }
            start = end
        }
        return out
    }

    /** x' = a·x + c·y + e, y' = b·x + d·y + f (the [EraserBasis] convention plus a move). */
    internal data class Affine(val a: Float = 1f, val b: Float = 0f, val c: Float = 0f, val d: Float = 1f, val e: Float = 0f, val f: Float = 0f) {
        fun x(x: Float, y: Float) = a * x + c * y + e
        fun y(x: Float, y: Float) = b * x + d * y + f
        fun apply(p: Point) = Point(x(p.x, p.y), y(p.x, p.y))
        fun sample(s: Sample) = s.copy(x = x(s.x, s.y), y = y(s.x, s.y))
        /** This transform applied after [first]. */
        fun after(first: Affine) = Affine(a * first.a + c * first.b, b * first.a + d * first.b, a * first.c + c * first.d,
            b * first.c + d * first.d, a * first.e + c * first.f + e, b * first.e + d * first.f + f)
        val isIdentity get() = abs(a - 1) < 1e-5f && abs(b) < 1e-5f && abs(c) < 1e-5f && abs(d - 1) < 1e-5f && abs(e) < .01f && abs(f) < .01f
        private val similarity get() = abs(a - d) < 1e-5f && abs(b + c) < 1e-5f
        fun erasure(sweep: EraserSweep): EraserSweep {
            val points = sweep.points.map(::apply)
            val basis = sweep.basis
            if (basis == null && similarity) return sweep.copy(points = points, radius = sweep.radius * hypot(a, b))
            val m = basis ?: EraserBasis(1f, 0f, 0f, 1f)
            return sweep.copy(points = points, basis = EraserBasis(a * m.a + c * m.b, b * m.a + d * m.b, a * m.c + c * m.d, b * m.c + d * m.d))
        }
        companion object {
            fun translate(dx: Float, dy: Float) = Affine(e = dx, f = dy)
            fun scale(s: Float, px: Float, py: Float) = Affine(a = s, d = s, e = px - s * px, f = py - s * py)
            fun rotate(radians: Double, px: Float, py: Float): Affine {
                val cos = cos(radians).toFloat(); val sin = sin(radians).toFloat()
                return Affine(cos, sin, -sin, cos, px - cos * px + sin * py, py - sin * px - cos * py)
            }
            /** Leans everything above [baseY] right by [k] per unit of height (y grows downward). */
            fun shearX(k: Float, baseY: Float) = Affine(c = -k, e = k * baseY)
        }
    }
}
