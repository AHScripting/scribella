package dev.ownednotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Editable
import android.text.TextPaint
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import dev.ownednotes.core.Notebook

/** P201 step 8: the four ways out of the app on the Export board, each with its coloured icon square. */
internal enum class ExportFormat(val title: String, val detail: String, val color: Int, val path: String) {
    PDF("PDF copy", "Opens and prints anywhere", 0xffff3b30.toInt(), "M6 3h8l4 4v14H6zM14 3v4h4"),
    NOTEBOOK("Notebook file", "Full copy to back up or move", 0xff0a66d9.toInt(), "M5 4h11a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3zM5 17a3 3 0 0 1 3-3h11"),
    IMAGE("Page image", "This page as a PNG picture", 0xff34c759.toInt(), "M3 5h18v14H3zM3 16l5-5 4 4 3-3 6 6"),
    SVG("SVG drawing", "This page, with vector writing", 0xff34c759.toInt(), "M3 5h18v14H3zM3 16l5-5 4 4 3-3 6 6"),
    TEXT("Text / Markdown", "Typed text and reviewed handwriting", 0xff5e5ce6.toInt(), "M4 6h16M4 12h10M4 18h13"),
}

/** What the sheet asks [MainActivity.exportFromSheet] for. [pages] are page numbers from 1; null is every page. */
internal data class ExportRequest(val format: ExportFormat, val pages: Set<Int>? = null, val paper: Boolean = true,
    val reviewed: Boolean = true, val markdown: Boolean = false, val editableInk: Boolean = false, val smallerFile:Boolean=false)

/**
 * P201 step 8: the Glass Export board as the Export button's popover, built into [column]. The title and page count with
 * "Made on this tablet", the format cards, the pages that will go (a segmented choice over their thumbnails), what to
 * include, then Save to Files and Share. Each format runs the export it already had, behind its own guards; [close]
 * runs once an export has started. [chosenIds] are the pages chosen in the page strip, offered as "Chosen N".
 */
internal class ExportSheet(column: LinearLayout, private val activity: MainActivity, private val note: Notebook, pageId: String,
    chosenIds: Collection<String>, start: ExportFormat, private val close: () -> Unit) {
    private enum class Pages { ALL, THIS, RANGE, CHOSEN }

    private val c: Context = column.context
    private val night = c.night
    private val count = note.pages.size
    private val current = note.pages.indexOfFirst { it.id == pageId } + 1
    private val every = (1..count).toSet()
    private val chosen = note.pages.mapIndexedNotNull { index, page -> (index + 1).takeIf { page.id in chosenIds } }.toSet()

    var format = start; private set
    private var pages = if (chosen.isNotEmpty()) Pages.CHOSEN else Pages.ALL
    private var range = ""
    private var paper = true
    private var reviewed = true
    private var markdown = false
    private var editableInk = false
    private var smallerFile = false

    val cards: List<FormatCard>
    val strip = PageStrip(c, note, activity.thumbnails)
    private val scroller = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(strip) }
    private val pagesBody = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val includeCaption = caption("Include")
    private val includeBody = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val problem = Kit.text(c, "", Kit.Type.CAPTION).apply { setTextColor(Kit.dangerText(night)); visibility = View.GONE }
    /** The range field while "Pages…" is shown, for tests. */
    var rangeField: EditText? = null; private set
    val save: TextView = Kit.button(c, "Save to Files", Kit.Button.TINTED) { run(share = false) }
    val share: TextView = Kit.button(c, "Share", Kit.Button.PRIMARY) { run(share = true) }

    init {
        column.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(Kit.text(c, "Export", Kit.Type.TITLE).apply { isAccessibilityHeading = true })
                addView(Kit.text(c, "${note.title} · ${pagesWord(count)}", secondary = true).apply {
                    textSize = 13f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = c.dpi(3f) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            // Every format is made here; Share then hands the file only to the app the user picks.
            addView(TextView(c).apply {
                text = "Made on this tablet"; textSize = 12f; typeface = Tokens.SANS_SEMIBOLD; maxLines = 1
                setTextColor(if (night) 0xff7ee2a0.toInt() else 0xff1e6b36.toInt())
                background = rounded(if (night) 0x2930d158 else 0x1f34c759, c.dp(10f))
                setPadding(c.dpi(10f), c.dpi(6f), c.dpi(10f), c.dpi(6f))
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = c.dpi(12f) })
        })
        cards = ExportFormat.entries.map { f -> FormatCard(c, f, f == format) { choose(f) } }
        column.addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            cards.chunked(2).forEachIndexed { row, pair ->
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL
                    pair.forEachIndexed { i, card -> addView(card, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = c.dpi(12f) }) }
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (row > 0) topMargin = c.dpi(12f) })
            }
        }, gap(20f))
        column.addView(caption("Pages"), gap(20f))
        column.addView(pagesBody, gap(0f))
        column.addView(scroller, gap(10f))
        column.addView(includeCaption, gap(12f))
        column.addView(includeBody, gap(0f))
        column.addView(problem, gap(12f))
        column.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false
            addView(save, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(share, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.4f).apply { marginStart = c.dpi(10f) })
        }, gap(20f))
        showFormat()
    }

    private fun gap(top: Float) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dpi(top) }
    /** The board's sentence-case caption over a block (13 sp semibold secondary). */
    private fun caption(text: String) = TextView(c).apply {
        this.text = text; textSize = 13f; typeface = Tokens.SANS_SEMIBOLD; setTextColor(Glass.secondary(night))
        setPadding(c.dpi(2f), 0, 0, c.dpi(10f)); isAccessibilityHeading = true
    }
    private fun pagesWord(n: Int) = if (n == 1) "1 page" else "$n pages"

    private fun choose(next: ExportFormat) {
        if (next == format) return
        format = next
        cards.forEach { it.chosen = it.format == format }
        showFormat()
    }

    /** Rebuilds Pages and Include for [format]; only what that format can really do is offered. */
    private fun showFormat() {
        pagesBody.removeAllViews(); includeBody.removeAllViews(); rangeField = null; problem.visibility = View.GONE
        when (format) {
            ExportFormat.PDF -> {
                val options = listOf(Pages.ALL, Pages.THIS, Pages.RANGE) + if (chosen.isNotEmpty()) listOf(Pages.CHOSEN) else emptyList()
                if (pages !in options) pages = Pages.ALL
                val field = Kit.field(c, "1, 3–5", range).apply {
                    contentDescription = "Pages to export, such as 1, 3-5"
                    filters = arrayOf(android.text.InputFilter.LengthFilter(200))
                    visibility = if (pages == Pages.RANGE) View.VISIBLE else View.GONE
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(s: Editable?) { range = s?.toString().orEmpty(); problem.visibility = View.GONE; update() }
                    })
                }
                rangeField = field
                val well = Kit.segmented(c, options.map { label(it) }, options.indexOf(pages)) { which ->
                    pages = options[which]
                    field.visibility = if (pages == Pages.RANGE) View.VISIBLE else View.GONE
                    if (pages == Pages.RANGE) field.requestFocus()
                    problem.visibility = View.GONE; update()
                }
                well.contentDescription = "Pages to export"
                options.forEachIndexed { i, option ->
                    well.getChildAt(i).contentDescription = when (option) {
                        Pages.ALL -> "All ${pagesWord(count)}"; Pages.THIS -> "This page, page $current"
                        Pages.RANGE -> "A range of pages"; Pages.CHOSEN -> "The ${pagesWord(chosen.size)} chosen in the page strip"
                    }
                }
                pagesBody.addView(well, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                pagesBody.addView(field, gap(10f))
                includeBody.addView(Kit.Group(c, if (night) Kit.group(true) else Color.WHITE, 20f).apply {
                    elevation = c.dp(1f)
                    addView(Kit.row(c, "Paper lines, dots and colour", trailing = Kit.Switch(c, paper, "Paper lines, dots and colour") { paper = it }))
                    addView(Kit.row(c, "Reviewed handwriting as searchable text", trailing = Kit.Switch(c, reviewed, "Reviewed handwriting as searchable text") { reviewed = it }))
                    addView(Kit.row(c, "Ink stays editable in other PDF apps", trailing = Kit.Switch(c, editableInk, "Ink stays editable in other PDF apps") { editableInk = it }))
                })
            }
            ExportFormat.NOTEBOOK -> pagesBody.addView(remark("Every page, with its PDFs, pictures and attached files, in one file that opens in this app."))
            ExportFormat.IMAGE -> pagesBody.addView(remark("Page $current, the page you are on."))
            ExportFormat.SVG -> pagesBody.addView(remark("Page $current. Writing and text stay vector-based; PDF backgrounds are embedded pictures."))
            ExportFormat.TEXT -> {
                pagesBody.addView(remark("Every page: typed text, printed text from PDFs and pictures, attached file names and reviewed handwriting. Unreviewed ink is left out."))
                includeBody.addView(Kit.segmented(c, listOf("Plain text", "Markdown"), if (markdown) 1 else 0) { markdown = it == 1; update() }.apply {
                    contentDescription = "Text format"
                    getChildAt(0).contentDescription = "Plain text (.txt)"; getChildAt(1).contentDescription = "Markdown (.md)"
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
        }
        if(format==ExportFormat.PDF || format==ExportFormat.IMAGE) {
            includeBody.addView(Kit.section(c,"Image quality"))
            includeBody.addView(Kit.segmented(c,listOf("Original quality","Smaller file"),if(smallerFile)1 else 0) {smallerFile=it==1;update()}.apply {
                contentDescription="Image quality"
                getChildAt(0).contentDescription="Original quality";getChildAt(1).contentDescription="Smaller file"
            })
            includeBody.addView(remark("Applies to photos and scanned image pages. Imported PDF content stays unchanged."))
        }
        includeCaption.text = if (format == ExportFormat.TEXT) "Format" else "Include"
        val include = includeBody.childCount > 0
        includeCaption.visibility = if (include) View.VISIBLE else View.GONE
        includeBody.visibility = if (include) View.VISIBLE else View.GONE
        update()
    }
    private fun remark(text: String) = Kit.text(c, text, secondary = true).apply { textSize = 13.5f }
    private fun label(option: Pages) = when (option) {
        Pages.ALL -> "All $count"; Pages.THIS -> "This page"; Pages.RANGE -> "Pages…"; Pages.CHOSEN -> "Chosen ${chosen.size}"
    }

    /** The page numbers that would go now, or null while the range names none. */
    private fun numbers(): Set<Int>? = when (format) {
        ExportFormat.IMAGE, ExportFormat.SVG -> setOf(current)
        ExportFormat.NOTEBOOK, ExportFormat.TEXT -> every
        ExportFormat.PDF -> when (pages) {
            Pages.ALL -> every; Pages.THIS -> setOf(current); Pages.CHOSEN -> chosen
            Pages.RANGE -> runCatching { PdfExport.pageNumbers(note, range) }.getOrNull()
        }
    }

    /** Rings the pages that will go, scrolls the first into view and names the Share button after them. */
    private fun update() {
        val going = numbers()
        strip.chosen = going.orEmpty()
        going?.minOrNull()?.let { first -> scroller.post { scroller.smoothScrollTo(strip.left(first) - c.dpi(4f), 0) } }
        share.text = when (format) {
            ExportFormat.PDF -> if (going == null) "Share as PDF" else "Share ${pagesWord(going.size)} as PDF"
            ExportFormat.NOTEBOOK -> "Share notebook file"
            ExportFormat.IMAGE -> "Share page $current as PNG"
            ExportFormat.SVG -> "Share page $current as SVG"
            ExportFormat.TEXT -> if (markdown) "Share as Markdown" else "Share as text"
        }
    }

    private fun run(share: Boolean) {
        val going = numbers()
        if (going == null) return fail(runCatching { PdfExport.pageNumbers(note, range) }.exceptionOrNull()?.message ?: "Enter pages such as 1, 3-5")
        val request = ExportRequest(format, if (format == ExportFormat.PDF && going != every) going else null, paper, reviewed, markdown, editableInk, smallerFile)
        runCatching { activity.exportFromSheet(note, request, share) }
            .onSuccess { if (it) close() }
            .onFailure { fail(it.message ?: "Export failed") }
    }
    private fun fail(message: String) { problem.text = message; problem.visibility = View.VISIBLE }

    /** One format on the board: a 48 dp coloured icon square, the name and what it is for; ringed in accent when chosen. */
    internal class FormatCard(context: Context, val format: ExportFormat, chosen: Boolean, private val onChoose: () -> Unit) : LinearLayout(context) {
        private val night = context.night
        var chosen = chosen
            set(value) { field = value; paint() }
        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dpi(96f); setPadding(dpi(16f), dpi(12f), dpi(12f), dpi(12f))
            addView(FrameLayout(context).apply {
                background = rounded(format.color, dp(14f))
                addView(View(context).apply { background = Kit.Icon(format.path, Color.WHITE, dpi(24f)) }, FrameLayout.LayoutParams(dpi(24f), dpi(24f), Gravity.CENTER))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(dpi(48f), dpi(48f)).apply { marginEnd = dpi(14f) })
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(TextView(context).apply {
                    text = format.title; textSize = 15.5f; typeface = Kit.Type.TITLE.typeface; setTextColor(Glass.text(night)); maxLines = if (context.largeText) 3 else 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(context).apply {
                    text = format.detail; textSize = 12.5f; typeface = Tokens.SANS; setTextColor(Glass.secondary(night)); maxLines = if (context.largeText) Int.MAX_VALUE else 2
                    setLineSpacing(0f, 1.1f); setPadding(0, dpi(2f), 0, 0)
                })
            }, LayoutParams(0, WRAP_CONTENT, 1f))
            contentDescription = "${format.title}, ${format.detail}"; isClickable = true; isFocusable = true
            setOnClickListener { if (runCatching(onChoose).isSuccess) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
            paint()
        }
        private fun paint() {
            isSelected = chosen
            val fill = if (chosen) Kit.accentFill(night) else if (night) Kit.group(true) else Color.WHITE
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x1f1d2433),
                if (chosen) rounded(fill, dp(22f), Glass.accent(night), dpi(2f)) else rounded(fill, dp(22f)), rounded(-1, dp(22f)))
            elevation = if (chosen) 0f else dp(1f)
        }
        override fun getAccessibilityClassName(): CharSequence = android.widget.RadioButton::class.java.name
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info); info.isCheckable = true; info.isChecked = chosen
        }
    }

    /**
     * The board's row of page thumbnails: the pages that will go are ringed in accent with their numbers in accent, the
     * rest faded. Only the thumbnails in view are drawn and asked for, so long notebooks stay quick.
     */
    internal class PageStrip(context: Context, private val note: Notebook, private val thumbnails: PageThumbnails) : View(context) {
        /** Page numbers from 1. */
        var chosen: Set<Int> = emptySet()
            set(value) {
                if (value == field) return
                field = value; invalidate()
                contentDescription = when {
                    value.isEmpty() -> "No pages chosen"
                    value.size == note.pages.size -> "All ${note.pages.size} pages"
                    else -> "${value.size} of ${note.pages.size} pages: " + value.sorted().joinToString(", ")
                }
            }
        private val night = context.night
        private val cell = dp(72f)
        private val edge = dp(4f)
        private val pixels = dpi(62f).coerceIn(1, PageThumbnails.MAX_EDGE)
        private val shown = HashMap<String, Bitmap>()
        private val pending = HashMap<String, PageThumbnails.Request>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(11f); typeface = Tokens.SANS_SEMIBOLD; textAlign = Paint.Align.CENTER }
        private val clip = Rect()
        private val box = RectF()
        private val art = RectF()

        fun left(number: Int) = (edge + (number - 1) * cell).toInt()
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
            setMeasuredDimension((edge * 2 + note.pages.size * cell - dp(10f)).toInt(), dpi(112f))
        override fun onDraw(canvas: Canvas) {
            if (!canvas.getClipBounds(clip)) return
            val first = ((clip.left - edge) / cell).toInt().coerceAtLeast(0)
            val last = ((clip.right - edge) / cell).toInt().coerceAtMost(note.pages.size - 1)
            for (i in first..last) {
                val page = note.pages[i]; val on = (i + 1) in chosen
                val x = edge + i * cell
                box.set(x, edge, x + dp(62f), edge + dp(82f))
                val alpha = if (on) 255 else 115
                paint.style = Paint.Style.FILL; paint.color = Color.WHITE; paint.alpha = alpha
                canvas.drawRoundRect(box, dp(5f), dp(5f), paint)
                thumbnail(page.id)?.let { bitmap ->
                    // Fit the page inside the 62 × 82 frame, centred.
                    val scale = minOf(box.width() / bitmap.width, box.height() / bitmap.height)
                    val w = bitmap.width * scale; val h = bitmap.height * scale
                    art.set(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2)
                    paint.alpha = alpha; canvas.drawBitmap(bitmap, null, art, paint)
                }
                paint.style = Paint.Style.STROKE
                if (on) {
                    paint.color = Glass.accent(night); paint.strokeWidth = dp(2f)
                    canvas.drawRoundRect(box.left - dp(1f), box.top - dp(1f), box.right + dp(1f), box.bottom + dp(1f), dp(6f), dp(6f), paint)
                } else {
                    paint.color = if (night) 0x33ffffff else 0xffe3e7ee.toInt(); paint.strokeWidth = dp(1f); paint.alpha = alpha
                    canvas.drawRoundRect(box.left + dp(.5f), box.top + dp(.5f), box.right - dp(.5f), box.bottom - dp(.5f), dp(5f), dp(5f), paint)
                }
                label.color = if (on) Kit.accentText(night) else 0xff8a93a6.toInt()
                canvas.drawText("${i + 1}", box.centerX(), box.bottom + dp(17f), label)
            }
        }
        private fun thumbnail(id: String): Bitmap? {
            shown[id]?.let { return it }
            thumbnails.cached(note, id, pixels)?.let { shown[id] = it; return it }
            if (id !in pending) {
                var request: PageThumbnails.Request? = null
                request = thumbnails.request(note, id, pixels) { result ->
                    if (pending[id] === request) pending.remove(id)
                    result.onSuccess { shown[id] = it; invalidate() }
                }
                if (id !in shown) pending[id] = request
            }
            return null
        }
        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            pending.values.forEach { it.cancel() }; pending.clear()
        }
    }
}
