package dev.ownednotes.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * P688 (docs/PHONE-CAPTURE-PLAN.md, step A1b): once a shared photo's notebook is open, asks how the photos go in, with
 * two pictured cards drawn from the first photo:
 * - **New pages**: each photo's page is found, straightened and checked on the scan review, then added after the open page;
 * - **Pictures on page N**: the photos are placed on the open page as pictures to move, resize or crop.
 * Either way the notebook takes them as one Undo step. Closing the sheet without a choice adds nothing and keeps no copy.
 */
internal class SharedPhotoChoice(private val activity: MainActivity, notebookTitle: String, pageNumber: Int,
    private val files: List<File>, private val choose: (asPages: Boolean) -> Unit, private val closedThen: (chosen: Boolean) -> Unit) {
    private val c get() = activity
    private val night = c.night
    private var chosen = false
    private var sample: Bitmap? = null
    internal val title = if (files.size == 1) "Add this photo to ${notebookTitle.ifBlank { "this notebook" }}"
        else "Add ${files.size} photos to ${notebookTitle.ifBlank { "this notebook" }}"
    internal val sheet = Kit.Sheet(c, title, maxWidth = 720f, centred = true, subtitle = "Choose how they go in. Undo takes them all out again.")
    private val pagesArt = Art(asPages = true)
    private val picturesArt = Art(asPages = false)
    /** Placing them as pictures keeps a page readable only up to a few photos; more go in as pages. */
    private val picturesAllowed = files.size <= MOST_PICTURES
    internal val pages = card(pagesArt, if (files.size == 1) "New page" else "New pages",
        "After page $pageNumber. Find the page in each photo and crop it first.") { pick(true) }
    internal val pictures = card(picturesArt, if (files.size == 1) "Picture on page $pageNumber" else "Pictures on page $pageNumber",
        if (picturesAllowed) "Placed on the open page, to move, resize or crop." else "Up to $MOST_PICTURES photos fit on one page. Choose New pages.") { pick(false) }
        .apply { if (!picturesAllowed) { isEnabled = false; alpha = .45f } }
    val isShowing get() = sheet.dialog.isShowing
    fun dismiss() = sheet.dismiss()
    /** Chooses as a tap on a card does: true for new pages, false for pictures on the open page. */
    internal fun pick(asPages: Boolean) {
        if (chosen || (!asPages && !picturesAllowed)) return
        chosen = true; dismiss(); choose(asPages)
    }

    private fun card(art: Art, name: String, hint: String, onClick: () -> Unit) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Glass.ACCENT and 0x30ffffff),
            rounded(Kit.cardFill(night), c.dp(18f), if (night) Glass.EDGE_NIGHT else Glass.EDGE, 1), null)
        setPadding(c.dpi(14f), c.dpi(14f), c.dpi(14f), c.dpi(16f))
        addView(art, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dpi(150f)))
        addView(Kit.text(c, name, Kit.Type.HEADLINE).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dpi(12f) })
        addView(Kit.text(c, hint, Kit.Type.CAPTION, secondary = true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dpi(4f) })
        contentDescription = "$name. $hint"
        isClickable = true; isFocusable = true; setOnClickListener { onClick() }
    }

    fun show(): SharedPhotoChoice {
        val wide = c.resources.configuration.screenWidthDp >= 520
        sheet.add(LinearLayout(c).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            val gap = c.dpi(12f)
            if (wide) {
                addView(pages, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(pictures, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = gap })
            } else {
                addView(pages, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(pictures, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
            }
        }, 8f)
        val observer = object : DefaultLifecycleObserver { override fun onDestroy(owner: LifecycleOwner) { dismiss() } }
        activity.lifecycle.addObserver(observer)
        sheet.dialog.setOnDismissListener {
            activity.lifecycle.removeObserver(observer)
            pagesArt.photo = null; picturesArt.photo = null; sample?.recycle(); sample = null
            closedThen(chosen)
        }
        sheet.show()
        // The cards show the first photo itself, read small and upright away from the main thread.
        val first = files.firstOrNull()
        if (first != null) (activity.application as NotesApplication).io.execute {
            val small = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(first)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSampleSize(max(1, max(info.size.width, info.size.height) / ART_EDGE))
                }
            }.getOrNull()
            activity.runOnUiThread {
                if (!isShowing || small == null) { small?.recycle(); return@runOnUiThread }
                sample = small; pagesArt.photo = small; picturesArt.photo = small
            }
        }
        return this
    }

    /** The card's picture: two pages made from the photo, or one page with the photo placed on it with its handles. */
    private inner class Art(private val asPages: Boolean) : View(c) {
        var photo: Bitmap? = null
            set(value) { field = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val box = RectF()
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(canvas: Canvas) {
            val ph = height - c.dp(8f); val pw = ph * .74f
            val cx = width / 2f; val top = c.dp(4f)
            if (asPages) {
                // A page behind, turned a little, and the photo's page in front filling its sheet.
                canvas.save(); canvas.rotate(-7f, cx, height / 2f)
                page(canvas, RectF(cx - pw / 2 - c.dp(10f), top, cx + pw / 2 - c.dp(10f), top + ph), null)
                canvas.restore()
                page(canvas, RectF(cx - pw / 2 + c.dp(8f), top, cx + pw / 2 + c.dp(8f), top + ph), photo)
            } else {
                val sheet = RectF(cx - pw / 2, top, cx + pw / 2, top + ph)
                page(canvas, sheet, null)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = c.dp(1.5f); paint.color = 0x332f3a4f
                for (i in 1..6) { val y = sheet.top + sheet.height() * i / 7f; canvas.drawLine(sheet.left + c.dp(8f), y, sheet.right - c.dp(8f), y, paint) }
                paint.style = Paint.Style.FILL
                // The photo sits on the page as a picture, selected, with its corner handles.
                val p = photo
                val aspect = if (p != null && p.height > 0) p.width.toFloat() / p.height else 4f / 3f
                val w = sheet.width() * .7f; val h = min(sheet.height() * .5f, w / aspect); val ww = h * aspect
                box.set(cx - ww / 2, sheet.centerY() - h / 2, cx + ww / 2, sheet.centerY() + h / 2)
                if (p != null) canvas.drawBitmap(p, null, box, paint) else { paint.color = 0xffc9d6e6.toInt(); canvas.drawRect(box, paint) }
                paint.style = Paint.Style.STROKE; paint.strokeWidth = c.dp(2f); paint.color = Glass.ACCENT; canvas.drawRect(box, paint)
                paint.style = Paint.Style.FILL
                for ((x, y) in listOf(box.left to box.top, box.right to box.top, box.left to box.bottom, box.right to box.bottom)) {
                    paint.color = Color.WHITE; canvas.drawCircle(x, y, c.dp(5.5f), paint)
                    paint.color = Glass.ACCENT; canvas.drawCircle(x, y, c.dp(3.5f), paint)
                }
            }
        }
        /** A white sheet with a soft shadow, holding [content] cropped to fill it. */
        private fun page(canvas: Canvas, r: RectF, content: Bitmap?) {
            paint.style = Paint.Style.FILL; paint.color = Color.WHITE; paint.setShadowLayer(c.dp(5f), 0f, c.dp(2f), 0x40000000)
            canvas.drawRoundRect(r, c.dp(4f), c.dp(4f), paint); paint.clearShadowLayer()
            if (content == null) return
            val inner = RectF(r.left + c.dp(4f), r.top + c.dp(4f), r.right - c.dp(4f), r.bottom - c.dp(4f))
            val k = max(inner.width() / content.width, inner.height() / content.height)
            val sw = (inner.width() / k).toInt(); val sh = (inner.height() / k).toInt()
            val sx = (content.width - sw) / 2; val sy = (content.height - sh) / 2
            canvas.drawBitmap(content, Rect(sx, sy, sx + sw, sy + sh), inner, paint)
        }
    }

    companion object {
        const val MOST_PICTURES = 12
        private const val ART_EDGE = 360
    }
}
