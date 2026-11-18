package dev.ownednotes.app

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import dev.ownednotes.core.Notebook
import dev.ownednotes.core.paperFit
import dev.ownednotes.core.validate
import dev.ownednotes.core.withPageShape
import kotlin.math.abs

/**
 * P671 (design canvas board 6, the user's decision of 17 November): when a PDF's pages are a different shape from the
 * user's paper, the import asks once how to place them. "Fit to my paper" makes each such page the paper's size with the
 * PDF page at the top and room to write below; "Keep the PDF's size" imports them as they are. "Remember for next time"
 * skips the question afterwards; Settings, Pages & layout, Imported PDFs changes it. The user's file is never changed:
 * a fitted page shows a padded copy of its PDF page (as Match page shape does, P412), so no notebook format changes.
 */
internal object PdfFit {
    const val KEY = "pdfImportFit"
    enum class Choice(val stored: String) { FIT("fit"), KEEP("keep") }

    fun remembered(preferences: SharedPreferences): Choice? = Choice.entries.firstOrNull { it.stored == preferences.getString(KEY, null) }
    fun remember(preferences: SharedPreferences, choice: Choice?) =
        preferences.edit().apply { if (choice == null) remove(KEY) else putString(KEY, choice.stored) }.apply()

    /** The paper an import lands on: the page it is inserted after, or the size New notebook offers. */
    fun paper(activity: MainActivity, target: PdfInsertTarget?): Pair<Float, Float> {
        target?.notebook?.pages?.firstOrNull { it.id == target.pageId }?.let { return it.width to it.height }
        val saved = activity.getSharedPreferences((activity.application as NotesApplication).preferencesName, 0).getString("libraryNewSize", null)
        return (PAGE_SIZES.firstOrNull { it.first == saved } ?: PAGE_SIZES[0]).second
    }

    /** The PDF pages whose shape differs from the paper; empty means nothing to ask. */
    fun differing(source: Notebook, paper: Pair<Float, Float>): Set<String> =
        source.pages.filter { it.pdfBackground != null && it.paperFit(paper.first, paper.second) != null }.map { it.id }.toSet()

    /** Fitted pages for every differing page. Runs off the main thread; throws when a page cannot be padded (locked or turned PDF). */
    fun fitted(app: NotesApplication, source: Notebook, paper: Pair<Float, Float>): Notebook {
        val ids = differing(source, paper)
        if (ids.isEmpty()) return source
        val (w, h) = paper
        val padded = StandardPageShape.padded(source, ids, app.cacheDir, app.attachmentDirectory) { it.paperFit(w, h) }
        return source.withPageShape(ids, padded) { it.paperFit(w, h) }.also { it.validate() }
    }

    private val NAMES = listOf("A3" to (841.89f to 1190.55f), "A4" to (595.28f to 841.89f), "A5" to (419.53f to 595.28f),
        "Letter" to (612f to 792f), "Legal" to (612f to 1008f), "Tabloid" to (792f to 1224f), "Standard" to (1000f to 1400f))

    fun landscape(w: Float, h: Float) = w > h * 1.02f
    /** "A4", "Letter", "16:9", or null for a size without a name. */
    fun sizeName(w: Float, h: Float): String? {
        val short = minOf(w, h); val long = maxOf(w, h)
        if (abs(long - short) <= long * .02f) return "Square"
        NAMES.firstOrNull { (_, s) -> abs(short - s.first) <= s.first * .015f && abs(long - s.second) <= s.second * .015f }?.let { return it.first }
        val ratio = long / short
        return listOf("16:9" to 16f / 9, "16:10" to 1.6f, "4:3" to 4f / 3).firstOrNull { abs(ratio - it.second) <= .015f * it.second }?.first
    }
    /** "Letter landscape", "A4 portrait", "Square", or "landscape" when the size has no name. */
    fun describe(w: Float, h: Float): String {
        val name = sizeName(w, h)
        if (name == "Square") return "square"
        val turn = if (landscape(w, h)) "landscape" else "portrait"
        return if (name == null) turn else "$name $turn"
    }
    /** The paper as the sheet calls it: "A4", "Standard page", "paper". */
    fun paperName(w: Float, h: Float): String = when (val name = sizeName(w, h)) {
        null -> "paper"; "Square", "Standard" -> "${name} page"; else -> name
    }
}

/** The board 6 sheet. [choose] gets the choice and whether to remember it; dismissing it imports nothing. */
internal class PdfFitSheet(private val context: Context, fileName: String, pages: Int, pdfSize: Pair<Float, Float>,
                           paper: Pair<Float, Float>, inserting: Boolean, private val choose: (PdfFit.Choice, Boolean) -> Unit) {
    private val night = context.night
    val dialog = Dialog(context)
    var choice = PdfFit.Choice.FIT; private set
    var remember = true; private set
    internal val fit: Card
    internal val keep: Card
    internal val box: CheckRow
    internal val action: TextView
    internal val subtitle: TextView

    init {
        val (pw, ph) = paper
        val (sw, sh) = pdfSize
        val slide = if (PdfFit.landscape(sw, sh)) "slide" else "PDF page"
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val r = dp(Glass.SHEET)
            background = GradientDrawable().apply {
                setColor(Kit.sheet(night)); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setStroke(1, if (night) Glass.EDGE_NIGHT else Glass.EDGE)
            }
            clipToOutline = true
            setPadding(dpi(24f), dpi(8f), dpi(24f), dpi(24f))
        }
        root.addView(View(context).apply { background = rounded(if (night) 0x40ffffff else 0x261d2433, dp(2.5f)) },
            LinearLayout.LayoutParams(context.dpi(40f), context.dpi(5f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = context.dpi(18f) })
        root.addView(Kit.text(context, fileName, Kit.Type.HEADLINE, name = true).apply {
            textSize = 19f; typeface = Kit.Type.TITLE.typeface; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; isAccessibilityHeading = true
        })
        subtitle = Kit.text(context, "$pages ${if (pages == 1) "page" else "pages"} · ${PdfFit.describe(sw, sh)} · your paper is ${PdfFit.describe(pw, ph)}", secondary = true).apply {
            textSize = 14f; setPadding(0, dpi(6f), 0, 0)
        }
        root.addView(subtitle)
        val paperName = PdfFit.paperName(pw, ph)
        fit = Card(context, "Fit to my paper", "Every page is your $paperName. The $slide sits at the top, room to write below.",
            Picture(context, paper, pdfSize, fitted = true)) { pick(PdfFit.Choice.FIT) }
        keep = Card(context, "Keep the PDF's size",
            "Pages stay ${if (PdfFit.landscape(sw, sh)) "landscape" else if (PdfFit.sizeName(sw, sh) == "Square") "square" else "portrait"}, as the PDF has them. The view fits each one to the screen.",
            Picture(context, paper, pdfSize, fitted = false)) { pick(PdfFit.Choice.KEEP) }
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; contentDescription = "How imported pages are placed"
            addView(fit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(keep, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dpi(12f) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(18f) })
        box = CheckRow(context, "Remember for next time", remember) { remember = it }
        root.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(10f) })
        action = Kit.button(context, "${if (inserting) "Insert" else "Import"} $pages ${if (pages == 1) "page" else "pages"}") {
            val chosen = choice; val keepIt = remember
            dialog.setOnDismissListener(null); dialog.dismiss(); choose(chosen, keepIt)
        }
        root.addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(10f) })
        pick(PdfFit.Choice.FIT)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setWindowAnimations(android.R.style.Animation_InputMethod)
            setDimAmount(.28f); addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }

    fun pick(next: PdfFit.Choice) { choice = next; fit.selected(next == PdfFit.Choice.FIT); keep.selected(next == PdfFit.Choice.KEEP) }

    fun show(onCancel: () -> Unit = {}): PdfFitSheet {
        dialog.setOnDismissListener { onCancel() }
        dialog.show()
        dialog.window?.let { window ->
            Kit.fullScreen(window)
            window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            window.setLayout(minOf(context.resources.displayMetrics.widthPixels, context.dpi(440f)), ViewGroup.LayoutParams.WRAP_CONTENT)
            window.decorView.setPadding(0, 0, 0, 0)
        }
        return this
    }
    fun dismiss() = dialog.dismiss()

    /** One of the two choices: a picture of the page, its name, and two lines on what it does. */
    internal class Card(context: Context, val title: String, detail: String, private val picture: Picture, onTap: () -> Unit) : LinearLayout(context) {
        private val night = context.night
        private val name: TextView
        init {
            orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(12f), dpi(14f), dpi(12f), dpi(14f))
            addView(picture, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(118f)))
            name = Kit.text(context, title, Kit.Type.HEADLINE).apply { textSize = 15f; gravity = Gravity.CENTER; setPadding(0, dpi(12f), 0, 0) }
            addView(name, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(Kit.text(context, detail, Kit.Type.CAPTION, secondary = true).apply {
                textSize = 12.5f; typeface = Kit.Type.BODY.typeface; gravity = Gravity.CENTER; setPadding(0, dpi(8f), 0, 0)
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            isClickable = true; isFocusable = true
            setOnClickListener { onTap() }
        }
        fun selected(on: Boolean) {
            isSelected = on
            background = if (on) rounded(Kit.accentFill(night), dp(18f), Glass.accent(night), dpi(2f))
                else rounded(Kit.group(night), dp(18f))
            name.setTextColor(if (on) Kit.accentText(night) else Glass.text(night))
            picture.invalidate()
        }
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = android.widget.RadioButton::class.java.name; info.isCheckable = true; info.isChecked = isSelected
        }
    }

    /** The page as each choice leaves it: the user's paper with the PDF page at the top and lines below, or the PDF page alone. */
    internal class Picture(context: Context, private val paper: Pair<Float, Float>, private val pdf: Pair<Float, Float>, private val fitted: Boolean) : View(context) {
        private val night = context.night
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(canvas: Canvas) {
            val room = RectF(0f, 0f, width.toFloat(), height.toFloat())
            fun placed(w: Float, h: Float, maxW: Float = room.width() * .62f): RectF {
                val s = minOf(maxW / w, room.height() / h)
                val pw = w * s; val ph = h * s
                return RectF(room.centerX() - pw / 2, room.centerY() - ph / 2, room.centerX() + pw / 2, room.centerY() + ph / 2)
            }
            val slideFill = if (night) 0xff3a4a66.toInt() else 0xffdce6f6.toInt()
            val slideEdge = if (night) 0xff6c86b0.toInt() else 0xffa9bfdf.toInt()
            val edge = if (night) 0x33ffffff else 0x2e1d2433
            if (fitted) {
                val page = placed(paper.first, paper.second, room.width() * .5f)
                paint.style = Paint.Style.FILL; paint.color = if (night) 0xffe9ecf2.toInt() else Color.WHITE
                canvas.drawRect(page, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f); paint.color = edge
                canvas.drawRect(page, paint)
                val inset = page.width() * .045f
                val inner = page.width() - inset * 2
                val fit = dev.ownednotes.core.Page(width = pdf.first, height = pdf.second).paperFit(paper.first, paper.second)
                val slideW = inner * minOf(1f, (fit?.scale ?: 1f) * pdf.first / paper.first)
                val slideH = minOf(slideW * pdf.second / pdf.first, page.height() * .7f)
                val slide = RectF(page.centerX() - slideW / 2, page.top + inset, page.centerX() + slideW / 2, page.top + inset + slideH)
                paint.style = Paint.Style.FILL; paint.color = slideFill; canvas.drawRect(slide, paint)
                paint.style = Paint.Style.STROKE; paint.color = slideEdge; canvas.drawRect(slide, paint)
                paint.color = if (night) 0xff9aa3b2.toInt() else 0xffd3d8e0.toInt(); paint.strokeWidth = dp(1.2f)
                var y = slide.bottom + page.height() * .1f
                while (y < page.bottom - page.height() * .08f) { canvas.drawLine(slide.left + inner * .1f, y, slide.right - inner * .1f, y, paint); y += page.height() * .08f }
            } else {
                val slide = placed(pdf.first, pdf.second, room.width() * .78f)
                paint.style = Paint.Style.FILL; paint.color = slideFill; canvas.drawRect(slide, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f); paint.color = slideEdge; canvas.drawRect(slide, paint)
            }
        }
    }

    /** The board's checkbox row: a 22 dp rounded box, accent and ticked when on. */
    internal class CheckRow(context: Context, label: String, checked: Boolean, private val change: (Boolean) -> Unit) : LinearLayout(context) {
        private val night = context.night
        var isChecked = checked; private set
        private val box = object : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                val r = RectF(dp(1f), dp(1f), width - dp(1f), height - dp(1f))
                if (isChecked) {
                    paint.style = Paint.Style.FILL; paint.color = Glass.accent(night); canvas.drawRoundRect(r, dp(6f), dp(6f), paint)
                    paint.style = Paint.Style.STROKE; paint.color = Color.WHITE; paint.strokeWidth = dp(2f)
                    paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
                    val w = width.toFloat(); val h = height.toFloat()
                    canvas.drawPath(android.graphics.Path().apply { moveTo(w * .28f, h * .52f); lineTo(w * .44f, h * .67f); lineTo(w * .73f, h * .35f) }, paint)
                } else {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.6f); paint.color = if (night) Glass.OFF_NIGHT else Glass.OFF
                    canvas.drawRoundRect(r, dp(6f), dp(6f), paint)
                }
            }
        }
        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dpi(44f)
            addView(box, LayoutParams(dpi(22f), dpi(22f)).apply { marginEnd = dpi(10f) })
            addView(Kit.text(context, label).apply { textSize = 14f; setTextColor(Glass.secondary(night)) })
            box.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            contentDescription = label
            isClickable = true; isFocusable = true
            setOnClickListener { isChecked = !isChecked; box.invalidate(); change(isChecked); sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED) }
        }
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = android.widget.CheckBox::class.java.name; info.isCheckable = true; info.isChecked = isChecked
        }
    }
}
