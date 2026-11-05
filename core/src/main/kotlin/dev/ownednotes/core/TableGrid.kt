package dev.ownednotes.core

import kotlin.math.abs

/**
 * F096: a table is ordinary grouped straight lines plus text boxes, with no format of its own. It is recognised from
 * its geometry, so copies, pastes, moves and resizes stay tables, and a rotated one is simply no longer offered for editing.
 */
data class TableGrid(val xs: List<Float>, val ys: List<Float>, val header: Boolean = false) {
    init {
        require(xs.size >= 2 && ys.size >= 2 && xs.size - 1 <= MAX_COLUMNS && ys.size - 1 <= MAX_ROWS) { "Invalid table size" }
        require((xs + ys).all { it.isFinite() } && xs.zipWithNext().all { (a, b) -> b > a } && ys.zipWithNext().all { (a, b) -> b > a }) { "Invalid table lines" }
    }
    val rows get() = ys.size - 1
    val columns get() = xs.size - 1

    /** One grid line; [heavy] marks the rule under a header row. */
    data class Line(val from: Point, val to: Point, val heavy: Boolean)
    val lines: List<Line> get() =
        ys.mapIndexed { i, y -> Line(Point(xs.first(), y), Point(xs.last(), y), header && i == 1) } +
            xs.map { x -> Line(Point(x, ys.first()), Point(x, ys.last()), false) }

    /** Where a cell's text sits: inside its lines, whatever the text's length. */
    fun cellBox(row: Int, column: Int): ImagePlacement {
        val w = xs[column + 1] - xs[column]; val h = ys[row + 1] - ys[row]
        return ImagePlacement(xs[column] + minOf(4f, w / 8), ys[row] + minOf(2f, h / 8), maxOf(1f, w - 2 * minOf(4f, w / 8)), maxOf(1f, h - 2 * minOf(2f, h / 8)))
    }
    fun cellAt(p: Point): Pair<Int, Int>? {
        val c = xs.zipWithNext().indexOfFirst { (a, b) -> p.x >= a && p.x < b }
        val r = ys.zipWithNext().indexOfFirst { (a, b) -> p.y >= a && p.y < b }
        return if (r >= 0 && c >= 0) r to c else null
    }
    /** Size for text in new cells, taken from the row height. */
    val textSize: Float get() = ((ys.last() - ys.first()) / rows * .42f).coerceIn(8f, 24f)

    /** Existing rows and columns keep their sizes and new ones take the average; the whole table shrinks only if it would leave the page. */
    fun resized(newRows: Int, newColumns: Int, header: Boolean, pageWidth: Float, pageHeight: Float): TableGrid {
        fun axis(old: List<Float>, count: Int, limit: Float): List<Float> {
            val size = (old.last() - old.first()) / (old.size - 1)
            val next = (0..count).map { if (it < old.size) old[it] else old.last() + (it - old.size + 1) * size }
            val start = next.first().coerceAtLeast(0f); val length = next.last() - next.first()
            val room = limit - start
            val k = if (length > room && room > 0f) room / length else 1f
            return next.map { start + (it - next.first()) * k }
        }
        return TableGrid(axis(xs, newColumns, pageWidth), axis(ys, newRows, pageHeight), header)
    }

    /**
     * P553: the same table with one inner line dragged to [to]; [column] picks a column line, else a row line. The line
     * stays at least [MIN_CELL] from its neighbours and the outside edges stay where they are.
     */
    fun movedLine(column: Boolean, index: Int, to: Float): TableGrid {
        val lines = if (column) xs else ys
        if (index <= 0 || index >= lines.size - 1 || !to.isFinite() || lines[index + 1] - lines[index - 1] < 2 * MIN_CELL) return this
        val next = lines.toMutableList().also { it[index] = to.coerceIn(lines[index - 1] + MIN_CELL, lines[index + 1] - MIN_CELL) }
        return if (column) copy(xs = next) else copy(ys = next)
    }

    /** Every column the same width and every row the same height, inside the same outside edges. */
    fun evened(): TableGrid {
        fun even(lines: List<Float>) = lines.indices.map { lines.first() + (lines.last() - lines.first()) * it / (lines.size - 1) }
        return copy(xs = even(xs), ys = even(ys))
    }
    val isEven: Boolean get() = evened().let { e -> (xs.zip(e.xs) + ys.zip(e.ys)).all { (a, b) -> abs(a - b) <= TOLERANCE } }

    companion object {
        const val MAX_ROWS = 12
        const val MAX_COLUMNS = 8
        /** The narrowest a column or the shortest a row can be dragged, in page units. */
        const val MIN_CELL = 12f
        private const val TOLERANCE = .5f

        /** A new table: most of the page's width, centred, with rows that would suit a line of writing. */
        fun fresh(rows: Int, columns: Int, header: Boolean, pageWidth: Float, pageHeight: Float): TableGrid {
            val width = pageWidth * .8f
            val rowHeight = minOf(pageHeight * .06f, pageHeight * .8f / rows)
            val left = (pageWidth - width) / 2; val top = (pageHeight - rowHeight * rows) / 2
            return TableGrid((0..columns).map { left + width * it / columns }, (0..rows).map { top + rowHeight * it }, header)
        }

        /** The grid these strokes draw, or null unless they are exactly a full grid of straight, level lines. */
        fun detect(strokes: List<InkStroke>): TableGrid? {
            if (strokes.size < 4 || strokes.any { it.shapeKind != ShapeRecognizer.Kind.LINE || it.erasures.isNotEmpty() || it.samples.size < 2 }) return null
            val across = mutableListOf<InkStroke>(); val down = mutableListOf<InkStroke>()
            for (stroke in strokes) {
                val a = stroke.samples.first(); val b = stroke.samples.last()
                when {
                    abs(a.y - b.y) <= TOLERANCE && abs(a.x - b.x) > TOLERANCE -> across += stroke
                    abs(a.x - b.x) <= TOLERANCE && abs(a.y - b.y) > TOLERANCE -> down += stroke
                    else -> return null
                }
            }
            if (across.size < 2 || down.size < 2 || across.size - 1 > MAX_ROWS || down.size - 1 > MAX_COLUMNS) return null
            val sortedAcross = across.sortedBy { it.samples.first().y }
            val xs = down.map { it.samples.first().x }.sorted(); val ys = sortedAcross.map { it.samples.first().y }
            if (xs.zipWithNext().any { (a, b) -> b - a <= TOLERANCE } || ys.zipWithNext().any { (a, b) -> b - a <= TOLERANCE }) return null
            val fullWidth = across.all { s -> listOf(s.samples.first().x, s.samples.last().x).sorted().let { abs(it[0] - xs.first()) <= TOLERANCE && abs(it[1] - xs.last()) <= TOLERANCE } }
            val fullHeight = down.all { s -> listOf(s.samples.first().y, s.samples.last().y).sorted().let { abs(it[0] - ys.first()) <= TOLERANCE && abs(it[1] - ys.last()) <= TOLERANCE } }
            if (!fullWidth || !fullHeight) return null
            val thinnest = strokes.minOf { it.width }
            val header = sortedAcross.size > 2 && sortedAcross[1].width > thinnest * 1.5f && sortedAcross.withIndex().all { (i, s) -> i == 1 || s.width <= thinnest * 1.5f }
            return TableGrid(xs, ys, header)
        }

        /** This group's table on [page], or null if the group holds anything but its lines and cell text. */
        fun of(page: Page, group: String): TableGrid? {
            if (page.images.any { it.group == group }) return null
            return detect(page.strokes.filter { it.group == group })
        }

        /**
         * P548: ungrouped ink lying wholly inside a table's outer lines, on an unlocked layer: handwriting in its cells.
         * Selecting the table takes this ink along, so it moves, resizes, copies and deletes with the table. It stays
         * out of the group because the Table sheet replaces the group's strokes.
         */
        fun cellInk(page: Page, group: String): Set<String> {
            val grid = of(page, group) ?: return emptySet()
            val locked = page.layers.filter { it.locked }.map { it.id }.toSet()
            val left = grid.xs.first() - TOLERANCE; val right = grid.xs.last() + TOLERANCE
            val top = grid.ys.first() - TOLERANCE; val bottom = grid.ys.last() + TOLERANCE
            return page.strokes.filter { s ->
                s.group == null && !s.locked && s.layer !in locked && s.samples.isNotEmpty() &&
                    s.samples.all { it.x in left..right && it.y in top..bottom }
            }.map { it.id }.toSet()
        }

        /** [Page.selectionGroup], also when the selection is a table together with the handwriting in its cells. */
        /**
         * P549: how far each word written in [group]'s cells moves when the table becomes [next], so it stays in the same
         * row and column. Words in a row or column that is removed stay where they are; nothing is deleted.
         */
        fun cellInkMoves(page: Page, group: String, next: TableGrid): Map<String, Point> {
            val grid = of(page, group) ?: return emptyMap()
            val ink = cellInk(page, group)
            return page.strokes.filter { it.id in ink }.mapNotNull { s ->
                val x = (s.samples.minOf { it.x } + s.samples.maxOf { it.x }) / 2; val y = (s.samples.minOf { it.y } + s.samples.maxOf { it.y }) / 2
                val (row, column) = grid.cellAt(Point(x, y)) ?: return@mapNotNull null
                if (row >= next.rows || column >= next.columns) return@mapNotNull null
                // Keep the word's place inside its cell, scaled with the cell if the table had to shrink to fit the page.
                val kx = (next.xs[column + 1] - next.xs[column]) / (grid.xs[column + 1] - grid.xs[column])
                val ky = (next.ys[row + 1] - next.ys[row]) / (grid.ys[row + 1] - grid.ys[row])
                val d = Point(next.xs[column] + (x - grid.xs[column]) * kx - x, next.ys[row] + (y - grid.ys[row]) * ky - y)
                if (d.x == 0f && d.y == 0f) null else s.id to d
            }.toMap()
        }
        fun selectionGroup(page: Page, ids: ObjectIds): String? {
            page.selectionGroup(ids)?.let { return it }
            val groups = page.strokes.filter { it.id in ids.strokes }.mapNotNull { it.group }.toSet()
            if (groups.size != 1) return null
            val group = groups.single()
            val rest = ObjectIds(ids.strokes - cellInk(page, group), ids.images, ids.texts)
            return group.takeIf { rest != ids && page.selectionGroup(rest) == it }
        }
    }
}
