package dev.ownednotes.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import dev.ownednotes.core.HandwritingRestyle
import dev.ownednotes.core.InkStroke
import dev.ownednotes.core.Point
import dev.ownednotes.core.Sample
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * P415 handwriting style: write on the pad on the left and see it rewritten on the right in the chosen hand, live as the
 * hand and the Letters, Rhythm and (P416) Heights sliders change. Save keeps the choice, and with "Improve my
 * handwriting as I write" on, words written on a page are rewritten the same way as they're finished (HandStyleWriter).
 */
internal object HandStyleSheet {
    /** The same hands and preview serve both the writing pad and an existing ink selection. */
    fun show(context: Context, selected: List<InkStroke>? = null, apply: ((List<HandStyle.Piece>) -> Boolean)? = null) {
        var choice = HandStyle.choice(context)
        val night = context.night
        var readyPieces: List<HandStyle.Piece>? = null
        lateinit var status: TextView
        val sheet = Kit.Sheet(context, if (selected == null) "Handwriting style" else "Refine handwriting",
            if (selected == null) "Save" else "Apply", maxWidth = 1120f, centred = true) {
            if (selected == null) { HandStyle.save(context, choice); true }
            else {
                val pieces = readyPieces
                if (pieces != null && apply?.invoke(pieces) == true) { HandStyle.save(context, choice); true }
                else { status.text = "The selection changed or is busy. Close this preview and select it again."; false }
            }
        }
        val preview = Preview(context, selected != null)
        status = ToolControls.note(context, if (selected == null) "Write a word or two on the left." else "Reading…")
        var read: List<List<HandwritingRestyle.Word>> = emptyList()
        val generation = AtomicInteger()
        sheet.dialog.setOnDismissListener { generation.incrementAndGet() }
        var reading = false
        fun redraw() {
            if (reading) return // The pending reading will use the latest hand and slider choices.
            val lines = read; val now = choice; val ticket = generation.incrementAndGet()
            readyPieces = null
            if (selected != null) sheet.actionButton?.isEnabled = false
            HandStyle.worker.execute {
                if (generation.get() != ticket) return@execute // A newer preview or dismissal owns the worker now.
                val protectedInk=lines.flatten().filter {it.text==null}.flatMap {it.strokes}
                val pieces = runCatching { lines.flatMap { HandStyle.restyle(context, it, now, sharedHeights = selected != null, protectedInk = protectedInk) } }.getOrDefault(emptyList())
                preview.post {
                    if (ticket == generation.get() && sheet.dialog.isShowing) {
                        preview.pieces = pieces; preview.invalidate()
                        readyPieces = pieces.takeIf { it.any { p -> p.lines != null } }
                        if (selected != null) sheet.actionButton?.isEnabled = readyPieces != null
                    }
                }
            }
        }
        fun readWriting(strokes: List<InkStroke>) {
            val ticket = generation.incrementAndGet(); readyPieces = null; reading = strokes.isNotEmpty()
            if (selected != null) sheet.actionButton?.isEnabled = false
            status.text = if (strokes.isEmpty()) "Write a word or two on the left." else "Reading…"
            if (strokes.isEmpty()) { read = emptyList(); preview.pieces = emptyList(); preview.invalidate(); return }
            HandStyle.worker.execute {
                if (generation.get() != ticket) return@execute // A newer preview or dismissal owns the worker now.
                val result = runCatching { HandStyle.preview(strokes) { generation.get() != ticket } }
                preview.post {
                    if (ticket != generation.get() || !sheet.dialog.isShowing) return@post
                    reading = false
                    read = result.getOrDefault(emptyList())
                    val text = read.joinToString("  ") { line -> line.mapNotNull { it.text }.joinToString(" ") }.trim()
                    val unread = read.any { line -> line.any { it.text == null } }
                    status.text = when {
                        result.isFailure -> "Could not preview this selection. Your writing is kept; try a smaller area."
                        text.isNotEmpty() && unread -> "Read words: ${read.flatten().mapNotNull { it.text }.joinToString(", ")}. Some writing was kept unchanged."
                        text.isNotEmpty() -> "Read as: $text"
                        else -> "Could not read this reliably. Your writing is kept. First use may need Wi-Fi for the reader."
                    }
                    redraw()
                }
            }
        }
        val pad = Pad(context, ::readWriting)

        val wide = context.resources.configuration.screenWidthDp >= 720
        sheet.add(LinearLayout(context).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            fun panel(title: String, surface: View, under: View) = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(ToolControls.label(context, title))
                addView(surface, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dpi(280f)).apply { topMargin = context.dpi(8f) })
                addView(under, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val clear = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(ToolControls.note(context, "Write with the pen, as you would on a page."), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Kit.button(context, "Clear", Kit.Button.PLAIN, small = true) { pad.clear() })
            }
            val original = if (selected == null) pad else Preview(context, true).apply {
                pieces = listOf(HandStyle.Piece(HandwritingRestyle.Word(null, selected), null))
                contentDescription = "Selected writing as written"
            }
            val left = panel("Your writing", original, if (selected == null) clear else ToolControls.note(context, "The page changes only when you tap Apply."))
            val right = panel("In this hand", preview, status)
            if (wide) {
                addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = context.dpi(10f) })
                addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dpi(10f) })
            } else {
                addView(left); addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(12f) })
            }
        })

        sheet.add(ToolControls.label(context, "Hand"), 20f)
        val tiles = HandStyle.Hand.entries.map { hand ->
            val art = TextView(context).apply {
                text = "Handwriting"; typeface = HandStyle.typeface(context, hand); textSize = 24f; gravity = Gravity.CENTER
                setTextColor(Glass.text(night)); maxLines = 1; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            Kit.Tile(context, art, hand.label, hand == choice.hand) { choice = choice.copy(hand = hand); redraw() }
        }
        Kit.oneOf(tiles)
        sheet.add(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                tiles.forEach { addView(it, LinearLayout.LayoutParams(context.dpi(150f), context.dpi(92f)).apply { marginEnd = context.dpi(8f) }) }
            })
        }, 8f)

        fun percent(yours: String, hands: String): (Float) -> String = { v -> when (v) { 0f -> yours; 1f -> hands; else -> "${(v * 100).roundToInt()} %" } }
        val step: (Float) -> Float = { (it * 20).roundToInt() / 20f }
        sheet.add(ToolControls.inlineSlider(context, "Letters", 0f, 1f, choice.settings.letters, step, percent("Yours", "Hand's")) {
            choice = choice.copy(settings = choice.settings.copy(letters = it)); redraw()
        }.apply { contentDescription = "Letters: from your slant and letter sizes to the hand's own letters" }, 18f)
        sheet.add(ToolControls.inlineSlider(context, "Rhythm", 0f, 1f, choice.settings.rhythm, step, percent("Yours", "Even")) {
            choice = choice.copy(settings = choice.settings.copy(rhythm = it)); redraw()
        }.apply { contentDescription = "Rhythm: from your spacing and heights to the hand's even rhythm" }, 4f)
        if(selected!=null)sheet.add(ToolControls.inlineSlider(context, "Word spacing", 0f, 1f, choice.wordSpacing, step, percent("Original", "Even")) {
            choice = choice.copy(wordSpacing = it); redraw()
        }.apply { contentDescription = "Word spacing: from original gaps to even gaps between words; symbols stay in place" }, 4f)
        sheet.add(ToolControls.inlineSlider(context, "Heights", 0f, 1f, choice.settings.steady, step, percent("Yours", "Level")) {
            choice = choice.copy(settings = choice.settings.copy(steady = it)); redraw()
        }.apply { contentDescription = "Heights: from each letter as high or low as you wrote it to every letter sitting on the line" }, 4f)
        sheet.add(ToolControls.note(context, "Letters at Yours keeps your slant and the size of each letter; at Hand's they are the hand's own. " +
            "Rhythm at Yours keeps your letter spacing and the ups and downs of your words; at Even it is the hand's steady rhythm. " +
            (if(selected!=null) "Word spacing evens the gaps within each line where space allows; original marks and separate inserted lines stay in place. " else "") +
            (if (selected != null) "Heights at Level gives each line a consistent lowercase letter height and keeps your word spacing." else "Heights at Level sets every letter of a word on the line at one height, and keeps your spacing.")), 0f)

        val level = Kit.Switch(context, choice.settings.level, "Level lines") { on -> choice = choice.copy(settings = choice.settings.copy(level = on)); redraw() }
        sheet.add(Kit.row(context, "Level lines", "Rest every word of a line on one baseline.", trailing = level), 14f)
        if (selected == null) {
        val auto = Kit.Switch(context, choice.auto, "Improve my handwriting as I write") { on -> choice = choice.copy(auto = on) }
        sheet.add(Kit.row(context, "Improve my handwriting as I write", "Each word you finish on a page is rewritten in this hand. Undo puts it back as you wrote it.", trailing = auto), 4f)
        }
        sheet.add(Kit.privacy(context, "Read and rewritten on this device. Your ink never leaves it."), 14f)
        sheet.show()
        if (selected != null) readWriting(selected)
    }

    private fun inkPaint(context: Context) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = context.dp(2.4f); color = Glass.text(context.night)
    }
    /** Ruled writing paper, so both sides read as a page. */
    private fun paper(view: View, canvas: Canvas, rule: Paint) {
        val gap = view.dp(56f)
        var y = gap
        while (y < view.height) { canvas.drawLine(view.dp(12f), y, view.width - view.dp(12f), y, rule); y += gap }
    }
    private fun surface(view: View) {
        val night = view.context.night
        view.background = rounded(if (night) 0xff1c2027.toInt() else 0xffffffff.toInt(), view.dp(14f), if (night) Glass.RULE_NIGHT else Glass.RULE, view.dpi(1f))
    }
    private fun rule(context: Context) = Paint().apply { color = if (context.night) Glass.RULE_NIGHT else Glass.RULE; strokeWidth = context.dp(1f) }

    /** The writing pad: pen (or finger) strokes, kept as samples like the page's ink. */
    private class Pad(context: Context, private val changed: (List<InkStroke>) -> Unit) : View(context) {
        val strokes = mutableListOf<MutableList<Sample>>()
        private var current: MutableList<Sample>? = null
        private val ink = inkPaint(context); private val rule = rule(context); private val path = Path()
        private val settle = Runnable { changed(inkStrokes()) }
        init { surface(this); contentDescription = "Writing pad" }

        fun inkStrokes() = strokes.filter { it.size > 1 }.map { s -> InkStroke(width = ink.strokeWidth, samples = s.toList(), outlines = listOf(s.map { Point(it.x, it.y) })) }
        fun clear() { strokes.clear(); current = null; invalidate(); removeCallbacks(settle); changed(emptyList()) }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true); removeCallbacks(settle)
                    current = mutableListOf<Sample>().also { strokes += it }; add(event)
                }
                MotionEvent.ACTION_MOVE -> add(event)
                MotionEvent.ACTION_UP -> { add(event); current = null; postDelayed(settle, 700) }
                MotionEvent.ACTION_CANCEL -> { current?.let { strokes.remove(it) }; current = null }
                else -> return false
            }
            invalidate(); return true
        }
        private fun add(event: MotionEvent) {
            val s = current ?: return
            for (h in 0 until event.historySize) s += Sample(event.getHistoricalX(h), event.getHistoricalY(h), event.getHistoricalEventTime(h), event.getHistoricalPressure(h))
            s += Sample(event.x, event.y, event.eventTime, event.pressure)
        }
        override fun onDraw(canvas: Canvas) {
            paper(this, canvas, rule)
            strokes.forEach { s -> line(path, s.map { Point(it.x, it.y) }); canvas.drawPath(path, ink) }
        }
        override fun onDetachedFromWindow() { removeCallbacks(settle); super.onDetachedFromWindow() }
    }

    /** The same writing rewritten in the chosen hand; words it couldn't read stay as written. */
    private class Preview(context: Context, private val fitWriting: Boolean = false) : View(context) {
        var pieces: List<HandStyle.Piece> = emptyList()
        private val ink = inkPaint(context); private val rule = rule(context); private val path = Path()
        private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.secondary(context.night); textSize = context.dp(14f); textAlign = Paint.Align.CENTER }
        init { surface(this); contentDescription = "Your writing in the chosen hand" }
        override fun onDraw(canvas: Canvas) {
            paper(this, canvas, rule)
            if (pieces.isEmpty()) { canvas.drawText("Your writing in the chosen hand appears here.", width / 2f, height / 2f, hint); return }
            val lines = pieces.flatMap { piece -> piece.lines ?: piece.word.strokes.map { s -> s.samples.map { Point(it.x, it.y) } } }
            canvas.save()
            if (fitWriting) {
                val points = lines.flatten()
                if (points.isNotEmpty()) {
                    val left = points.minOf { it.x }; val top = points.minOf { it.y }
                    val w = (points.maxOf { it.x } - left).coerceAtLeast(1f)
                    val h = (points.maxOf { it.y } - top).coerceAtLeast(1f)
                    val margin = dp(20f)
                    val scale = minOf((width - margin * 2) / w, (height - margin * 2) / h, 2f).coerceAtLeast(.001f)
                    canvas.translate((width - w * scale) / 2f, (height - h * scale) / 2f)
                    canvas.scale(scale, scale); canvas.translate(-left, -top)
                }
            }
            lines.forEach { line(path, it); canvas.drawPath(path, ink) }
            canvas.restore()
        }
    }

    private fun line(path: Path, points: List<Point>) {
        path.rewind()
        if (points.isEmpty()) return
        path.moveTo(points[0].x, points[0].y)
        if (points.size == 1) path.lineTo(points[0].x + .1f, points[0].y)
        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
    }
}
