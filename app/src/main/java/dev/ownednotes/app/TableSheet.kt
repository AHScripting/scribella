package dev.ownednotes.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import dev.ownednotes.core.TableGrid
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * F096 feature record. Task: put a table on a page, then fill and resize it. Scope: page. Home: Insert → Table…, and
 * Table… on the selection bar while a table is selected. Frequency: occasional. Destructive: Apply can remove rows or
 * columns with their text, one Undo step; nothing changes before Apply. Handwriting in the cells is ordinary ink.
 */
internal object TableSheet {
    /** Drag across the squares to choose a size, as on paper; the steppers beside it give the exact count. */
    internal class SizePicker(context: Context, var rows: Int, var columns: Int, private val changed: () -> Unit) : View(context) {
        private val night = context.night
        private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Kit.well(night) }
        private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.ACCENT }
        private val square = RectF()
        init {
            isClickable = true; isFocusable = true
            minimumHeight = context.dpi(TableGrid.MAX_ROWS * 26f)
            describe()
        }
        fun describe() {
            contentDescription = "Table size"
            stateDescription = "$rows ${if (rows == 1) "row" else "rows"} by $columns ${if (columns == 1) "column" else "columns"}"
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(width, minimumHeight)
        }
        private val cellWidth get() = width.toFloat() / TableGrid.MAX_COLUMNS
        private val cellHeight get() = height.toFloat() / TableGrid.MAX_ROWS
        override fun onDraw(canvas: Canvas) {
            val gap = dp(2f); val radius = dp(4f)
            for (r in 0 until TableGrid.MAX_ROWS) for (c in 0 until TableGrid.MAX_COLUMNS) {
                square.set(c * cellWidth + gap, r * cellHeight + gap, (c + 1) * cellWidth - gap, (r + 1) * cellHeight - gap)
                canvas.drawRoundRect(square, radius, radius, if (r < rows && c < columns) on else off)
            }
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isEnabled || width == 0) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    parent?.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP)
                    choose((event.y / cellHeight).toInt() + 1, (event.x / cellWidth).toInt() + 1)
                }
                MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
        fun choose(newRows: Int, newColumns: Int) {
            val r = newRows.coerceIn(1, TableGrid.MAX_ROWS); val c = newColumns.coerceIn(1, TableGrid.MAX_COLUMNS)
            if (r == rows && c == columns) return
            rows = r; columns = c; describe(); invalidate(); changed()
        }
    }

    /**
     * P553: the table drawn to scale; drag an inner line to make a column wider or a row taller. The outside edges
     * stay, as the selection handles already move and resize the whole table.
     */
    internal class LinesPicker(context: Context, grid: TableGrid, private val changed: () -> Unit) : View(context) {
        private val night = context.night
        var grid = grid
            set(value) { field = value; describe(); invalidate() }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Kit.caps(night); style = Paint.Style.STROKE; strokeWidth = context.dp(1.5f) }
        private val heavy = Paint(line).apply { strokeWidth = context.dp(3f) }
        private val held = Paint(line).apply { color = Kit.accentText(night); strokeWidth = context.dp(3f) }
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.ACCENT }
        /** P556: each cell's typed words, drawn small in the cell so a column is recognisable while it is dragged. */
        var words: (Int, Int) -> String = { _, _ -> "" }
            set(value) { field = value; invalidate() }
        private val type = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.secondary(night); textSize = context.dp(11f); textAlign = Paint.Align.CENTER }
        /** The line being dragged: true for a column line, and its index. */
        private var dragging: Pair<Boolean, Int>? = null
        init {
            isClickable = true; isFocusable = true
            minimumHeight = context.dpi(200f)
            describe()
        }
        private fun describe() {
            contentDescription = "Column widths and row heights"
            fun shares(lines: List<Float>) = lines.zipWithNext { a, b -> ((b - a) / (lines.last() - lines.first()) * 100).roundToInt() }.joinToString(", ") { "$it%" }
            stateDescription = "Columns ${shares(grid.xs)}; rows ${shares(grid.ys)}"
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), minimumHeight)
        private val pad get() = dp(16f)
        private val scale get() = minOf((width - 2 * pad) / (grid.xs.last() - grid.xs.first()), (height - 2 * pad) / (grid.ys.last() - grid.ys.first()))
        /** Where page x or y [v] sits in this view. */
        internal fun viewX(v: Float) = (width - (grid.xs.last() - grid.xs.first()) * scale) / 2 + (v - grid.xs.first()) * scale
        internal fun viewY(v: Float) = (height - (grid.ys.last() - grid.ys.first()) * scale) / 2 + (v - grid.ys.first()) * scale
        override fun onDraw(canvas: Canvas) {
            if (width == 0) return
            val left = viewX(grid.xs.first()); val right = viewX(grid.xs.last()); val top = viewY(grid.ys.first()); val bottom = viewY(grid.ys.last())
            grid.ys.forEachIndexed { i, y -> canvas.drawLine(left, viewY(y), right, viewY(y), if (dragging == (false to i)) held else if (grid.header && i == 1) heavy else line) }
            grid.xs.forEachIndexed { i, x -> canvas.drawLine(viewX(x), top, viewX(x), bottom, if (dragging == (true to i)) held else line) }
            for (row in 0 until grid.rows) for (column in 0 until grid.columns) {
                val text = words(row, column).lineSequence().firstOrNull()?.trim().orEmpty()
                if (text.isEmpty()) continue
                val x0 = viewX(grid.xs[column]); val x1 = viewX(grid.xs[column + 1]); val y0 = viewY(grid.ys[row]); val y1 = viewY(grid.ys[row + 1])
                if (y1 - y0 < type.textSize || x1 - x0 < dp(12f)) continue
                val shown = android.text.TextUtils.ellipsize(text, type, x1 - x0 - dp(6f), android.text.TextUtils.TruncateAt.END)
                canvas.drawText(shown, 0, shown.length, (x0 + x1) / 2, (y0 + y1) / 2 - (type.descent() + type.ascent()) / 2, type)
            }
            val r = dp(5f)
            for (i in 1 until grid.xs.size - 1) canvas.drawCircle(viewX(grid.xs[i]), top, r, knob)
            for (i in 1 until grid.ys.size - 1) canvas.drawCircle(left, viewY(grid.ys[i]), r, knob)
        }
        /** The inner line nearest the finger, within reach, along the table. */
        private fun lineAt(x: Float, y: Float): Pair<Boolean, Int>? {
            val reach = dp(20f)
            val top = viewY(grid.ys.first()) - reach; val bottom = viewY(grid.ys.last()) + reach
            val left = viewX(grid.xs.first()) - reach; val right = viewX(grid.xs.last()) + reach
            val column = (1 until grid.xs.size - 1).filter { y in top..bottom }.minByOrNull { abs(viewX(grid.xs[it]) - x) }?.takeIf { abs(viewX(grid.xs[it]) - x) <= reach }
            val row = (1 until grid.ys.size - 1).filter { x in left..right }.minByOrNull { abs(viewY(grid.ys[it]) - y) }?.takeIf { abs(viewY(grid.ys[it]) - y) <= reach }
            return when {
                column != null && (row == null || abs(viewX(grid.xs[column]) - x) <= abs(viewY(grid.ys[row]) - y)) -> true to column
                row != null -> false to row
                else -> null
            }
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isEnabled || width == 0) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = lineAt(event.x, event.y) ?: return false
                    parent?.requestDisallowInterceptTouchEvent(true); invalidate()
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> dragging?.let { (column, index) ->
                    // The outside edges do not move, so the scale stays the same through the drag.
                    val to = if (column) grid.xs.first() + (event.x - viewX(grid.xs.first())) / scale else grid.ys.first() + (event.y - viewY(grid.ys.first())) / scale
                    val next = grid.movedLine(column, index, to)
                    if (next != grid) { grid = next; changed() }
                    if (event.actionMasked == MotionEvent.ACTION_UP) { dragging = null; parent?.requestDisallowInterceptTouchEvent(false); invalidate() }
                }
                MotionEvent.ACTION_CANCEL -> { dragging = null; parent?.requestDisallowInterceptTouchEvent(false); invalidate() }
            }
            return true
        }
    }

    /** "Rows − 3 +": named buttons, so a screen reader can change the size without dragging. */
    private fun stepper(context: Context, name: String, value: () -> Int, change: (Int) -> Unit): Pair<LinearLayout, () -> Unit> {
        lateinit var count: TextView
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false
            addView(Kit.text(context, name), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Kit.button(context, "−", Kit.Button.GLASS, small = true) { change(-1) }.apply { contentDescription = "One ${name.lowercase().removeSuffix("s")} fewer" })
            count = Kit.text(context, value().toString()).apply { gravity = Gravity.CENTER; minWidth = dpi(44f) }
            addView(count)
            addView(Kit.button(context, "+", Kit.Button.GLASS, small = true) { change(1) }.apply { contentDescription = "One ${name.lowercase().removeSuffix("s")} more" })
        }
        return row to { count.text = value().toString() }
    }

    fun insert(activity: MainActivity): Kit.Sheet? {
        val ink = activity.ink
        val c: Context = activity
        lateinit var picker: SizePicker
        var header = true
        lateinit var sync: () -> Unit
        val sheet = Kit.Sheet(c, "Insert table", "Insert", onAction = {
            ink.insertTable(picker.rows, picker.columns, header).also { if (it) activity.refresh() }
        })
        picker = SizePicker(c, 3, 3) { sync() }
        val (rowsRow, syncRows) = stepper(c, "Rows", { picker.rows }) { picker.choose(picker.rows + it, picker.columns) }
        val (columnsRow, syncColumns) = stepper(c, "Columns", { picker.columns }) { picker.choose(picker.rows, picker.columns + it) }
        sync = { syncRows(); syncColumns() }
        sheet.add(picker, 0f)
        sheet.add(rowsRow, 12f)
        sheet.add(columnsRow, 8f)
        sheet.add(Kit.segmented(c, listOf("Header row", "Plain"), 0) { header = it == 0 }.apply { contentDescription = "Table style" }, 12f)
        sheet.add(Kit.text(c, "Write in the cells with your pen, or select the table to type in them.", Kit.Type.CAPTION, secondary = true), 12f)
        return sheet.show()
    }

    /** Typed cell text and size for the selected table; handwriting stays where it is. */
    fun edit(activity: MainActivity): Kit.Sheet? {
        val ink = activity.ink
        val table = ink.selectedTable() ?: return null
        val c: Context = activity
        var rows = table.grid.rows; var columns = table.grid.columns; var header = table.grid.header
        lateinit var lines: LinesPicker
        lateinit var even: View
        fun reshape() {
            val kept = lines.grid
            lines.grid = (if (kept.rows == rows && kept.columns == columns) kept else kept.resized(rows, columns, header, table.pageWidth, table.pageHeight)).copy(header = header)
            even.isEnabled = !lines.grid.isEven
        }
        val cells = table.cells.toMutableMap()
        val fields = mutableMapOf<Pair<Int, Int>, EditText>()
        lateinit var cellGrid: GridLayout
        lateinit var warning: TextView
        fun keepTyped() { fields.forEach { (key, field) -> cells[key] = field.text.toString() } }
        fun rebuild() {
            fields.clear(); cellGrid.removeAllViews(); cellGrid.rowCount = rows; cellGrid.columnCount = columns
            for (r in 0 until rows) for (col in 0 until columns) {
                val field = Kit.field(c, "", cells[r to col].orEmpty()).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    contentDescription = "Row ${r + 1}, column ${col + 1}"
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(s: android.text.Editable?) { lines.invalidate() }
                    })
                }
                fields[r to col] = field
                cellGrid.addView(field, GridLayout.LayoutParams(GridLayout.spec(r), GridLayout.spec(col)).apply {
                    width = c.dpi(120f); height = ViewGroup.LayoutParams.WRAP_CONTENT
                    setMargins(c.dpi(2f), c.dpi(2f), c.dpi(2f), c.dpi(2f))
                })
            }
            val lost = cells.any { (key, text) -> text.isNotEmpty() && (key.first >= rows || key.second >= columns) }
            warning.visibility = if (lost) View.VISIBLE else View.GONE
        }
        val sheet = Kit.Sheet(c, "Table", "Apply", onAction = {
            keepTyped()
            ink.applyTable(table, lines.grid, cells.filterKeys { it.first < rows && it.second < columns }).also { if (it) activity.refresh() }
        })
        lateinit var syncRows: () -> Unit
        lateinit var syncColumns: () -> Unit
        val rowsRow = stepper(c, "Rows", { rows }) { step -> keepTyped(); rows = (rows + step).coerceIn(1, TableGrid.MAX_ROWS); syncRows(); reshape(); rebuild() }
        val columnsRow = stepper(c, "Columns", { columns }) { step -> keepTyped(); columns = (columns + step).coerceIn(1, TableGrid.MAX_COLUMNS); syncColumns(); reshape(); rebuild() }
        syncRows = rowsRow.second; syncColumns = columnsRow.second
        lines = LinesPicker(c, table.grid) { even.isEnabled = !lines.grid.isEven }
        lines.words = { r, col -> fields[r to col]?.text?.toString() ?: "" }
        even = Kit.button(c, "Make even", Kit.Button.GLASS, small = true) { lines.grid = lines.grid.evened(); even.isEnabled = false }.apply { contentDescription = "Make columns and rows even" }
        even.isEnabled = !table.grid.isEven
        sheet.add(lines, 0f)
        sheet.add(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false
            addView(Kit.text(c, "Drag a line to change a column or row.", Kit.Type.CAPTION, secondary = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(even)
        }, 8f)
        sheet.add(rowsRow.first, 12f)
        sheet.add(columnsRow.first, 8f)
        sheet.add(Kit.segmented(c, listOf("Header row", "Plain"), if (header) 0 else 1) { header = it == 0; reshape() }.apply { contentDescription = "Table style" }, 12f)
        warning = Kit.text(c, "Text in the removed rows or columns goes when you apply. Undo brings it back.", Kit.Type.CAPTION, secondary = true)
        sheet.add(warning, 8f)
        cellGrid = GridLayout(c)
        sheet.add(HorizontalScrollView(c).apply { addView(cellGrid) }, 12f)
        rebuild()
        return sheet.show()
    }
}
