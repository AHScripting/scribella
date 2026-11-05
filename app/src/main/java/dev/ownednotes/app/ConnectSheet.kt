package dev.ownednotes.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import dev.ownednotes.core.Connectors
import dev.ownednotes.core.ImagePlacement
import dev.ownednotes.core.InkStroke
import dev.ownednotes.core.Point
import dev.ownednotes.core.Sample
import dev.ownednotes.core.ShapeRecognizer.Kind
import dev.ownednotes.core.TextAlignment
import dev.ownednotes.core.TextStyle

/** F059: draws a connector along a new path in its own pen, colour and width; the editor's history uses it so connectors follow their shapes. */
internal object ConnectorInk {
    fun redraw(stroke: InkStroke, path: List<Point>): InkStroke? {
        if (stroke.isHighlighter || stroke.isTape || path.size < 2) return null
        val pressure = stroke.samples.firstOrNull()?.pressure ?: .6f
        val samples = path.mapIndexed { i, p -> Sample(p.x, p.y, i * 4L, pressure) }
        return runCatching {
            val style = PenStyle(stroke.color or 0xff000000L, stroke.width.coerceIn(.5f, 50f), PenKind.forBrush(stroke.brush))
            val batch = MutableStrokeInputBatch()
            samples.forEach { batch.add(InputToolType.STYLUS, it.x, it.y, it.timeMs, pressure = it.pressure) }
            val outlines = InkCanvas.outlinesOf(Stroke(InkCanvas.brushFor(style), batch))
            if (outlines.isEmpty()) null else stroke.copy(samples = samples, outlines = outlines)
        }.getOrNull()
    }

    /** P554: a label's words, centred, in page units at the connector's ink colour. */
    fun labelStyle(color: Long) = TextStyle(size = 16f, color = color or 0xff000000L, alignment = TextAlignment.CENTER)
    /** Where [text] sits by the connector from [from] to [to]: sized to its words, one line up to [maxWidth], then wrapped. */
    fun labelPlacement(text: String, color: Long, from: Connectors.End, to: Connectors.End, elbow: Boolean, penWidth: Float, maxWidth: Float, style: TextStyle = labelStyle(color)): ImagePlacement? {
        if (text.isBlank()) return null
        val one = TextRendering.build(TextRendering.Key(text, style, 100_000))
        val wide = (0 until one.lineCount).maxOf { one.getLineWidth(it) }
        val width = kotlin.math.ceil(wide + 8f).coerceIn(24f, maxWidth.coerceAtLeast(24f))
        val height = TextRendering.build(TextRendering.Key(text, style, kotlin.math.ceil(width).toInt())).height.toFloat()
        return Connectors.labelPlacement(from, to, elbow, width, height, 4f + penWidth)
    }
}

/**
 * P545 (F059) task: join two shapes with a line or arrow that stays joined when either shape moves or resizes.
 * Scope: selection of exactly two closed shapes on one page; P550: a photo or text box can be either end. Home: Connect on the selection bar. Frequency: sometimes.
 * Destructive: none on its own; deleting one of the shapes later also deletes its connector, in the same Undo step.
 * No format change: a connector is an ordinary line or arrow, recognised from where its ends sit.
 */
internal object ConnectSheet {
    /** The two shapes as they sit on the page, with the connector that would join them. */
    internal class Preview(context: Context, private val ends: List<Connectors.End>) : View(context) {
        constructor(context: Context, first: Connectors.End, second: Connectors.End) : this(context, listOf(first, second))
        private val night = context.night
        var kind = Kind.ARROW
            set(value) { field = value; invalidate() }
        var reverse = false
            set(value) { field = value; invalidate() }
        /** P552: two right angles instead of a straight line. */
        var elbow = false
            set(value) { field = value; invalidate() }
        /** P554: words that sit by the connector's middle; blank for none. */
        var label = ""
            set(value) { field = value; invalidate() }
        private val words = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Kit.accentText(night); textAlign = Paint.Align.CENTER }
        private val order get() = if (reverse) ends.reversed() else ends
        /** The path the connector would take, or null when the two overlap that way. */
        fun route(width: Float): List<Point>? = routes(width)?.singleOrNull()
        /** P557: one path per neighbouring pair, or null when any pair overlaps that way. */
        fun routes(width: Float): List<List<Point>>? = order.zipWithNext { from, to ->
            (if (elbow) Connectors.elbow(from, to, kind == Kind.ARROW, width)?.let { dev.ownednotes.core.ShapeRecognizer.densify(it, 2f) }
                else Connectors.route(from, to)?.let { (p, q) -> Connectors.path(kind, p, q, width) }) ?: return null
        }
        private val shapes = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Kit.caps(night); style = Paint.Style.STROKE; strokeWidth = context.dp(2f); strokeJoin = Paint.Join.ROUND }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Kit.accentText(night); style = Paint.Style.STROKE; strokeWidth = context.dp(2.5f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        private val path = Path()
        init { contentDescription = "Shapes with their connector"; minimumHeight = context.dpi(180f) }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), minimumHeight)
        override fun onDraw(canvas: Canvas) {
            val all = ends.flatMap { it.outline }
            val left = all.minOf { it.x }; val top = all.minOf { it.y }
            val w = (all.maxOf { it.x } - left).coerceAtLeast(1f); val h = (all.maxOf { it.y } - top).coerceAtLeast(1f)
            val pad = context.dp(12f)
            val scale = minOf((width - 2 * pad) / w, (height - 2 * pad) / h)
            val ox = (width - w * scale) / 2 - left * scale; val oy = (height - h * scale) / 2 - top * scale
            fun draw(points: List<Point>, paint: Paint) {
                path.reset()
                points.forEachIndexed { i, p -> if (i == 0) path.moveTo(ox + p.x * scale, oy + p.y * scale) else path.lineTo(ox + p.x * scale, oy + p.y * scale) }
                canvas.drawPath(path, paint)
            }
            ends.forEach { draw(it.outline, shapes) }
            routes(2f / scale)?.forEach { draw(it, line) }
            if (ends.size != 2) return
            val (from, to) = order
            ConnectorInk.labelPlacement(label.trim(), 0xff000000L, from, to, elbow, 2f / scale, w)?.let { box ->
                // P556: never smaller than a caption, so the words can be read; it still sits where it will go.
                words.textSize = maxOf(16f * scale, context.dp(11f))
                val grow = words.textSize / (16f * scale)
                // Enlarged words sit as far out from the line's middle as they grew, so they stay clear of it.
                val m = Connectors.middle(from, to, elbow)?.first ?: Point(box.x + box.width / 2, box.y + box.height / 2)
                val cx = m.x + (box.x + box.width / 2 - m.x) * grow; val cy = m.y + (box.y + box.height / 2 - m.y) * grow
                val baseline = oy + cy * scale - (words.descent() + words.ascent()) / 2
                canvas.drawText(android.text.TextUtils.ellipsize(label.trim(), words, box.width * scale * grow, android.text.TextUtils.TruncateAt.END).toString(), ox + cx * scale, baseline, words)
            }
        }
    }

    fun show(activity: MainActivity): Kit.Sheet? {
        val ink = activity.ink
        // P555: with one connector selected (and its label), the same sheet changes it in place.
        val shapes = ink.connectableShapes()
        val existing = if (shapes == null) ink.selectedConnector() else null
        // P557: three to twelve shapes are joined one after another.
        val chain = if (shapes == null && existing == null) ink.chainableShapes() ?: return null else null
        val c: Context = activity
        val preview = if (chain != null) Preview(c, chain) else (shapes ?: (existing!!.from to existing.to)).let { (first, second) -> Preview(c, first, second) }
        if (existing != null) { preview.kind = if (existing.found.arrow) Kind.ARROW else Kind.LINE; preview.elbow = existing.found.elbow; preview.label = existing.label?.text.orEmpty() }
        val choices = listOf(Kind.ARROW to false, Kind.ARROW to true, Kind.LINE to false)
        val sheet = if (chain != null) Kit.Sheet(c, "Connect ${chain.size} shapes", "Connect", onAction = { ink.connectChain(preview.kind, preview.reverse, preview.elbow) })
            else if (existing == null) Kit.Sheet(c, "Connect shapes", "Connect", onAction = { ink.connectShapes(preview.kind, preview.reverse, preview.elbow, preview.label.trim()) })
            else Kit.Sheet(c, "Change connector", "Apply", onAction = { ink.changeConnector(existing, preview.kind, preview.reverse, preview.elbow, preview.label.trim()) })
        val note = Kit.text(c, "", Kit.Type.CAPTION, secondary = true)
        fun update() {
            val joinable = preview.routes(ink.penStyle.width) != null
            sheet.actionButton?.isEnabled = joinable
            note.text = when {
                joinable && chain != null -> "Joins them one after another, in the order they are laid out. Each stays joined when its shapes move."
                chain != null && preview.elbow -> "Some of them overlap this way. Try Straight, or move them apart."
                chain != null -> "Some of them overlap. Move them apart to connect them."
                joinable && existing != null -> "Changes this connector where it is. Clear the label to remove it."
                joinable -> "It stays joined when either end moves or changes size. Deleting one deletes its connector too."
                preview.elbow -> "These overlap this way. Try Straight, or move them apart."
                else -> "These overlap. Move them apart to connect them."
            }
        }
        sheet.add(preview, 0f)
        sheet.add(Kit.segmented(c, listOf("Arrow", "Arrow back", "Line"), if (preview.kind == Kind.LINE) 2 else 0) { preview.kind = choices[it].first; preview.reverse = choices[it].second; update() }.apply { contentDescription = "Kind of connector" }, 12f)
        sheet.add(Kit.segmented(c, listOf("Straight", "Elbow"), if (preview.elbow) 1 else 0) { preview.elbow = it == 1; update() }.apply { contentDescription = "Connector path" }, 8f)
        val label = Kit.field(c, "Label (optional)", preview.label).apply {
            contentDescription = "Connector label"
            filters = arrayOf(android.text.InputFilter.LengthFilter(80))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { preview.label = s?.toString().orEmpty() }
            })
        }
        if (chain == null) sheet.add(label, 12f)
        sheet.add(note, 12f)
        update()
        return sheet.show()
    }
}
