package dev.ownednotes.app

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.ownednotes.core.Notebook
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * P670 (design canvas board 15, the user's item 7): Peek, a page inside a page. A small floating window shows another
 * page of the open notebook over the page being written on, for example grammar rules over a workbook page.
 * - Opens from Page actions, "Peek at a page…", or from a held page in the Pages panel ("Peek").
 * - Swipe or pinch inside scrolls and zooms the peeked page; double tap fits it again. Nothing writes in it.
 * - ‹ and › switch to the page before or after; the title opens the page chooser.
 * - Drag the header to move it (it stays on screen); drag the curved grip in the corner to resize, from 220 × 160 dp up
 *   to half the screen, with a blue outline and the size while held. – folds it to a bubble; × closes it.
 * - P677 (the user's 18 November report): the folded bubble moves wherever it is dragged (it stuck to the right edge), a
 *   dark and a light hairline with a deeper shadow keep the peek and the bubble visible on white paper, dark paper and at
 *   night, and the resize arrow became a curved grip that follows the rounded corner (board 15, redrawn).
 * - The page, place, size and folding are kept per notebook in the app's settings, never in the notebook file.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class PeekWindow(
    private val activity: MainActivity,
    private val document: () -> NotebookRepository,
    private val stage: FrameLayout,
    private val preferences: SharedPreferences,
    private val night: Boolean,
    /** The top of the free area under the floating chrome, in stage pixels. */
    private val top: () -> Int,
    /** The height taken by a toolbar docked at the bottom, in stage pixels. */
    private val bottom: () -> Int = { 0 },
) {
    private val c get() = activity
    private val repository get() = document()
    /** The peeked page, or null while closed (tests read it). */
    var pageId: String? = null; private set
    var notebookId: String? = null; private set
    var folded = false; private set
    val shown get() = pageId != null
    /** True while the corner is held (tests read it). */
    var resizing = false; private set

    internal val card = FrameLayout(c)
    internal val tab = LinearLayout(c)
    internal val page = PeekPage()
    internal val title = TextView(c)
    internal val previous = button(PREVIOUS, "Previous page in the peek") { step(-1) }
    internal val next = button(NEXT, "Next page in the peek") { step(1) }
    internal val fold = button(FOLD, "Fold the peek to the edge") { setFolded(true) }
    internal val close = button(CLOSE, "Close the peek") { close() }
    internal val corner = View(c)
    internal val sizeLabel = TextView(c)
    private val tabLabel = TextView(c)
    /** P677: a dark hairline outside a light one, so the peek stands out on white and on dark paper, as on board 15. */
    private val edge = ring(RADIUS)
    private val outline = GradientDrawable().apply { cornerRadius = c.dp(RADIUS); setStroke(c.dpi(2f), Glass.accent(night)); setColor(0x140a66d9) }

    // Place and size: left and top as fractions of the free room, so a turn of the tablet keeps the peek in its corner.
    private var fx = 1f
    private var fy = 0f
    private var wDp = DEFAULT_W
    private var hDp = DEFAULT_H
    private var tabOnLeft = false
    /** P677: where the folded bubble was dragged, as fractions of the free room; below 0 until it is moved. */
    private var tx = -1f
    private var ty = -1f

    init {
        card.background = Glass.float(c, night, RADIUS); card.elevation = c.dp(12f); deepShadow(card)
        card.clipToOutline = true; card.visibility = View.GONE; card.foreground = edge
        val column = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val header = Header().apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dpi(2f), c.dpi(4f), c.dpi(2f), 0)
            setBackgroundColor(if (night) 0x14ffffff else 0x0a1d2433)
        }
        title.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f); typeface = Tokens.SANS_SEMIBOLD; setTextColor(Glass.text(night))
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dpi(4f), 0, c.dpi(4f), 0); isClickable = true; isFocusable = true
            setOnClickListener { activity.peekDialog(pageId) }
        }
        header.addView(previous, LinearLayout.LayoutParams(c.dpi(BUTTON), c.dpi(BUTTON)))
        header.addView(title, LinearLayout.LayoutParams(0, c.dpi(BUTTON), 1f))
        header.addView(next, LinearLayout.LayoutParams(c.dpi(BUTTON), c.dpi(BUTTON)))
        header.addView(fold, LinearLayout.LayoutParams(c.dpi(BUTTON), c.dpi(BUTTON)))
        header.addView(close, LinearLayout.LayoutParams(c.dpi(BUTTON), c.dpi(BUTTON)))
        column.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        column.addView(page, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        card.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // The grip strip shows where to drag; the whole header moves the peek.
        card.addView(View(c).apply {
            background = GradientDrawable().apply { cornerRadius = c.dp(2f); setColor(Glass.secondary(night) and 0x00ffffff or 0x66000000) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(c.dpi(30f), c.dpi(4f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = c.dpi(3f) })
        corner.background = Grip(Glass.secondary(night))
        corner.contentDescription = "Resize the peek"
        corner.setOnTouchListener(::resize)
        card.addView(corner, FrameLayout.LayoutParams(c.dpi(CORNER_DP), c.dpi(CORNER_DP), Gravity.BOTTOM or Gravity.END))
        sizeLabel.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f); typeface = Tokens.SANS_SEMIBOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = c.dp(10f); setColor(0xe61d2433.toInt()) }
            setPadding(c.dpi(9f), c.dpi(3f), c.dpi(9f), c.dpi(3f)); visibility = View.GONE; elevation = c.dp(13f)
        }
        tab.apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE; elevation = c.dp(10f)
            background = Glass.float(c, night, TAB_RADIUS); foreground = ring(TAB_RADIUS); deepShadow(this)
            isClickable = true; isFocusable = true
            setOnClickListener { setFolded(false) }
            setOnTouchListener(BubbleDrag())
            addView(ImageView(c).apply { setImageDrawable(Kit.Icon(MenuIcons.PEEK, Glass.accent(night), c.dpi(20f))) }, LinearLayout.LayoutParams(c.dpi(20f), c.dpi(20f)))
            addView(tabLabel.apply { setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f); typeface = Tokens.SANS_SEMIBOLD; setTextColor(Glass.text(night)); gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = c.dpi(6f) })
        }
        stage.addView(card, FrameLayout.LayoutParams(c.dpi(DEFAULT_W), c.dpi(DEFAULT_H), Gravity.TOP or Gravity.LEFT))
        stage.addView(tab, FrameLayout.LayoutParams(c.dpi(TAB_W), c.dpi(TAB_H), Gravity.TOP or Gravity.LEFT))
        stage.addView(sizeLabel, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.LEFT))
        stage.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob -> if (shown && (r - l != or - ol || b - t != ob - ot)) stage.post { place() } }
    }

    /** Shows [id] of the open notebook in the peek, opening it where it was last time. */
    fun open(id: String) {
        val notebook = repository.session?.notebook ?: return
        if (notebook.pages.none { it.id == id }) return
        if (notebookId != notebook.id) restorePlace(notebook.id)
        notebookId = notebook.id; pageId = id; folded = false
        page.reset()
        card.visibility = View.VISIBLE; tab.visibility = View.GONE
        place(); bind(notebook); save()
    }

    fun close() {
        if (!shown) return
        pageId = null; folded = false
        hideViews(); save()
        page.clear()
    }

    fun setFolded(fold: Boolean) {
        if (!shown || folded == fold) return
        folded = fold
        if (fold) {
            val x = card.translationX + card.width / 2f
            tabOnLeft = x < stage.width / 2f
        }
        card.visibility = if (fold) View.GONE else View.VISIBLE
        tab.visibility = if (fold) View.VISIBLE else View.GONE
        place(); save()
    }

    /** Follows the open notebook: a stored peek comes back with its notebook; a deleted page closes it; edits redraw it. */
    fun refresh() {
        val notebook = repository.session?.notebook
        if (notebook == null) { if (shown) { pageId = null; hideViews(); page.clear() }; notebookId = null; return }
        if (notebook.id != notebookId) {
            pageId = null; hideViews(); page.clear()
            notebookId = notebook.id
            restorePlace(notebook.id)
            val stored = preferences.getString(key(notebook.id), null)?.split('|')
            val id = stored?.getOrNull(0)?.takeIf { it.isNotEmpty() && stored.getOrNull(1) == "1" && notebook.pages.any { p -> p.id == it } } ?: return
            pageId = id; folded = stored.getOrNull(2) == "1"
            card.visibility = if (folded) View.GONE else View.VISIBLE; tab.visibility = if (folded) View.VISIBLE else View.GONE
            stage.post { place(); bind(notebook) }
            return
        }
        val id = pageId ?: return
        if (notebook.pages.none { it.id == id }) { close(); return }
        bind(notebook)
    }

    private fun hideViews() { card.visibility = View.GONE; tab.visibility = View.GONE; sizeLabel.visibility = View.GONE }

    private fun step(by: Int) {
        val notebook = repository.session?.notebook ?: return
        val index = notebook.pages.indexOfFirst { it.id == pageId }
        val target = notebook.pages.getOrNull(index + by) ?: return
        pageId = target.id; page.reset(); bind(notebook); save()
    }

    private fun bind(notebook: Notebook) {
        val id = pageId ?: return
        val index = notebook.pages.indexOfFirst { it.id == id }
        val p = notebook.pages.getOrNull(index) ?: return
        val name = p.bookmark?.takeIf { it.isNotBlank() }
        title.text = "Page ${index + 1}" + (name?.let { " · $it" } ?: "") + " ▾"
        title.contentDescription = "Peeked page ${index + 1} of ${notebook.pages.size}" + (name?.let { ", $it" } ?: "") + ". Choose another page"
        tabLabel.text = "p. ${index + 1}"
        tab.contentDescription = "Open the peek, page ${index + 1}"
        previous.isEnabled = index > 0; previous.alpha = if (index > 0) 1f else 0.35f
        next.isEnabled = index < notebook.pages.lastIndex; next.alpha = if (next.isEnabled) 1f else 0.35f
        page.show(notebook, id)
    }

    /** Clamps size and place to the stage, from the remembered fractions. */
    private fun place() {
        if (stage.width == 0) return
        val free = top()
        val margin = c.dpi(MARGIN)
        val (minW, minH) = c.dpi(MIN_W) to c.dpi(MIN_H)
        val maxW = maxOf(minW, minOf(stage.width / 2, stage.width - 2 * margin))
        val maxH = maxOf(minH, minOf(stage.height / 2, stage.height - bottom() - free - 2 * margin))
        val w = c.dpi(wDp).coerceIn(minW, maxW)
        val h = c.dpi(hDp).coerceIn(minH, maxH)
        (card.layoutParams as FrameLayout.LayoutParams).let { if (it.width != w || it.height != h) { it.width = w; it.height = h; card.layoutParams = it } }
        val roomX = maxOf(0, stage.width - w - 2 * margin)
        val roomY = maxOf(0, stage.height - bottom() - free - h - 2 * margin)
        card.translationX = margin + fx * roomX
        card.translationY = free + margin + fy * roomY
        val tw = c.dpi(TAB_W); val th = c.dpi(TAB_H)
        if (tx < 0f || ty < 0f) {
            // Not moved yet: beside the nearest edge, level with the peek.
            tab.translationX = if (tabOnLeft) margin.toFloat() else (stage.width - tw - margin).toFloat()
            tab.translationY = (card.translationY + h / 2f - th / 2f).coerceIn((free + margin).toFloat(), maxOf(free + margin, stage.height - bottom() - th - margin).toFloat())
        } else {
            tab.translationX = margin + tx * maxOf(0, stage.width - tw - 2 * margin)
            tab.translationY = free + margin + ty * maxOf(0, stage.height - bottom() - free - th - 2 * margin)
        }
    }

    private fun moveBubbleBy(dx: Float, dy: Float) {
        val free = top(); val margin = c.dpi(MARGIN)
        val roomX = maxOf(1, stage.width - c.dpi(TAB_W) - 2 * margin)
        val roomY = maxOf(1, stage.height - bottom() - free - c.dpi(TAB_H) - 2 * margin)
        tx = ((tab.translationX + dx - margin) / roomX).coerceIn(0f, 1f)
        ty = ((tab.translationY + dy - free - margin) / roomY).coerceIn(0f, 1f)
        place()
    }

    /** P677: whether a lift-off lies within a short reach of the last move. */
    private fun near(e: MotionEvent, x: Float, y: Float) = abs(e.rawX - x) < c.dp(40f) && abs(e.rawY - y) < c.dp(40f)

    /** P677: the folded bubble follows a finger or the pen once it slides past the touch slop; a tap opens the peek. */
    private inner class BubbleDrag : View.OnTouchListener {
        private val slop = ViewConfiguration.get(c).scaledTouchSlop
        private var x0 = 0f
        private var y0 = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var dragging = false
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; lastX = x0; lastY = y0; dragging = false; v.isPressed = true; v.parent.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && (abs(e.rawX - x0) > slop || abs(e.rawY - y0) > slop)) { dragging = true; v.isPressed = false; lastX = x0; lastY = y0 }
                    if (dragging) { moveBubbleBy(e.rawX - lastX, e.rawY - lastY); lastX = e.rawX; lastY = e.rawY }
                }
                // The lift-off is where the finger really is (moves can be resampled a little past it), so the bubble settles
                // there; a lift-off far from the last move (off the screen) is not trusted.
                MotionEvent.ACTION_UP -> { v.isPressed = false; if (dragging) { if (near(e, lastX, lastY)) moveBubbleBy(e.rawX - lastX, e.rawY - lastY); save() } else v.performClick() }
                MotionEvent.ACTION_CANCEL -> { v.isPressed = false; if (dragging) save() }
            }
            return true
        }
    }

    /** P677: a hairline dark outside and light inside, visible on any paper. */
    private fun ring(radius: Float): android.graphics.drawable.Drawable {
        val outer = GradientDrawable().apply { cornerRadius = c.dp(radius); setStroke(c.dpi(1f), if (night) 0x4dffffff else 0x521d2433) }
        val inner = GradientDrawable().apply { cornerRadius = c.dp(radius - 1f); setStroke(c.dpi(1f), if (night) 0x66000000 else 0xbfffffff.toInt()) }
        return android.graphics.drawable.LayerDrawable(arrayOf(outer, inner)).apply { val i = c.dpi(1f); setLayerInset(1, i, i, i, i) }
    }
    private fun deepShadow(v: View) { v.outlineAmbientShadowColor = 0xff000000.toInt(); v.outlineSpotShadowColor = 0xff000000.toInt() }

    /** P677: the resize grip, a short curved line following the card's rounded corner (board 15). */
    private inner class Grip(private val color: Int) : android.graphics.drawable.Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = c.dp(3f); strokeCap = Paint.Cap.ROUND; this.color = color
        }
        override fun draw(canvas: Canvas) {
            val b = bounds
            // Concentric with the card's corner: its centre lies RADIUS in from the bottom right, the line 5 dp inside the edge.
            val cx = b.right - c.dp(RADIUS); val cy = b.bottom - c.dp(RADIUS); val r = c.dp(RADIUS - 5f)
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 8f, 74f, false, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun moveBy(dx: Float, dy: Float) {
        val free = top(); val margin = c.dpi(MARGIN)
        val roomX = maxOf(1, stage.width - card.width - 2 * margin)
        val roomY = maxOf(1, stage.height - bottom() - free - card.height - 2 * margin)
        fx = ((card.translationX + dx - margin) / roomX).coerceIn(0f, 1f)
        fy = ((card.translationY + dy - free - margin) / roomY).coerceIn(0f, 1f)
        place()
    }

    private var downX = 0f
    private var downY = 0f
    private var startW = 0f
    private var startH = 0f
    private var startLeft = 0f
    private var startTop = 0f
    private fun resize(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; startW = wDp; startH = hDp
                startLeft = card.translationX; startTop = card.translationY
                resizing = true; card.foreground = outline; sizeLabel.visibility = View.VISIBLE; label()
                v.parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                wDp = startW + (e.rawX - downX) / c.dp(1f); hDp = startH + (e.rawY - downY) / c.dp(1f)
                clampSize()
                // Keep the top-left corner where it was while the size changes.
                val free = top(); val margin = c.dpi(MARGIN)
                val w = c.dpi(wDp); val h = c.dpi(hDp)
                place()
                val roomX = maxOf(1, stage.width - w - 2 * margin); val roomY = maxOf(1, stage.height - bottom() - free - h - 2 * margin)
                fx = ((startLeft - margin) / roomX).coerceIn(0f, 1f); fy = ((startTop - free - margin) / roomY).coerceIn(0f, 1f)
                place(); label()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resizing = false; card.foreground = edge; sizeLabel.visibility = View.GONE
                clampSize(); place(); save()
                page.reset()
            }
        }
        return true
    }

    private fun clampSize() {
        val d = c.dp(1f)
        val maxW = maxOf(MIN_W, minOf(stage.width / 2f, stage.width - 2 * c.dp(MARGIN)) / d)
        val maxH = maxOf(MIN_H, minOf(stage.height / 2f, stage.height - bottom() - top() - 2 * c.dp(MARGIN)) / d)
        wDp = wDp.coerceIn(MIN_W, maxW); hDp = hDp.coerceIn(MIN_H, maxH)
    }

    private fun label() {
        sizeLabel.text = "${wDp.roundToInt()} × ${hDp.roundToInt()}"
        sizeLabel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val lp = card.layoutParams
        sizeLabel.translationX = card.translationX + lp.width / 2f - sizeLabel.measuredWidth / 2f
        sizeLabel.translationY = (card.translationY + lp.height + c.dp(10f)).coerceAtMost(stage.height - sizeLabel.measuredHeight - c.dp(4f))
    }

    private fun key(id: String) = "peek.$id"
    private fun save() {
        val id = notebookId ?: return
        preferences.edit().putString(key(id), listOf(pageId.orEmpty(), if (pageId != null) "1" else "0", if (folded) "1" else "0",
            fx, fy, wDp, hDp, if (tabOnLeft) "1" else "0", tx, ty).joinToString("|")).apply()
    }
    private fun restorePlace(id: String) {
        val stored = preferences.getString(key(id), null)?.split('|')
        fx = stored?.getOrNull(3)?.toFloatOrNull() ?: 1f
        fy = stored?.getOrNull(4)?.toFloatOrNull() ?: 0f
        wDp = stored?.getOrNull(5)?.toFloatOrNull() ?: DEFAULT_W
        hDp = stored?.getOrNull(6)?.toFloatOrNull() ?: DEFAULT_H
        tabOnLeft = stored?.getOrNull(7) == "1"
        tx = stored?.getOrNull(8)?.toFloatOrNull() ?: -1f
        ty = stored?.getOrNull(9)?.toFloatOrNull() ?: -1f
    }

    private fun button(path: String, name: String, action: () -> Unit) = ImageView(c).apply {
        setImageDrawable(Kit.Icon(path, Glass.text(night), c.dpi(18f)))
        scaleType = ImageView.ScaleType.CENTER
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(if (night) 0x30ffffff else 0x1f0a66d9), null,
            GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = c.dp(12f) })
        contentDescription = name; isClickable = true; isFocusable = true
        setOnClickListener { action() }
    }

    /** The header moves the peek once a finger slides past the touch slop; a tap still reaches its buttons and title. */
    private inner class Header : LinearLayout(c) {
        private val slop = ViewConfiguration.get(c).scaledTouchSlop
        private var x0 = 0f
        private var y0 = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var dragging = false
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; lastX = x0; lastY = y0; dragging = false }
                MotionEvent.ACTION_MOVE -> if (!dragging && (abs(e.rawX - x0) > slop || abs(e.rawY - y0) > slop)) { dragging = true; lastX = e.rawX; lastY = e.rawY }
            }
            return dragging
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; lastX = x0; lastY = y0; dragging = true; parent.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> { moveBy(e.rawX - lastX, e.rawY - lastY); lastX = e.rawX; lastY = e.rawY }
                MotionEvent.ACTION_UP -> { if (near(e, lastX, lastY)) moveBy(e.rawX - lastX, e.rawY - lastY); dragging = false; save() } // see BubbleDrag
                MotionEvent.ACTION_CANCEL -> { dragging = false; save() }
            }
            return true
        }
    }

    /** The peeked page: fitted to the width, scrolled and pinched by finger or pen; nothing draws on it. */
    internal inner class PeekPage : View(c) {
        private var bitmap: Bitmap? = null
        private var request: PageThumbnails.Request? = null
        private var requestKey: Any? = null
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val matrix = Matrix()
        /** Zoom over the fitted width, 1 to 4 (tests read it). */
        var zoom = 1f; private set
        var scrollYPx = 0f; private set
        val hasPicture get() = bitmap != null
        private var scrollXPx = 0f
        private val scaler = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val old = zoom
                zoom = (zoom * d.scaleFactor).coerceIn(1f, 4f)
                val k = zoom / old
                scrollXPx = (scrollXPx + d.focusX) * k - d.focusX
                scrollYPx = (scrollYPx + d.focusY) * k - d.focusY
                clamp(); invalidate(); return true
            }
        })
        private val gestures = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean { scrollBy(dx, dy); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { reset(); return true }
        })

        init { contentDescription = "Peeked page"; setBackgroundColor(if (night) 0xff1b1f26.toInt() else 0xfff4f5f7.toInt()) }

        fun scrollBy(dx: Float, dy: Float) { scrollXPx += dx; scrollYPx += dy; clamp(); invalidate() }
        fun reset() { zoom = 1f; scrollXPx = 0f; scrollYPx = 0f; invalidate() }
        fun clear() { request?.cancel(); request = null; requestKey = null; bitmap = null; invalidate() }

        fun show(notebook: Notebook, id: String) {
            if (width == 0) { post { if (pageId == id) repository.session?.notebook?.let { show(it, id) } }; return }
            val p = notebook.pages.firstOrNull { it.id == id } ?: return
            val want = ((width * 2 + 255) / 256 * 256).coerceIn(256, PageThumbnails.MAX_EDGE)
            val key = activity.thumbnails.key(notebook, p, want)
            if (key == requestKey) return
            request?.cancel(); request = null
            // A new size of the same page keeps the old picture until the sharper one arrives.
            if ((requestKey as? PageThumbnails.Key)?.pageId != id) bitmap = null
            requestKey = key
            var delivered = false
            val mine = activity.thumbnails.request(notebook, id, want) { result ->
                delivered = true
                if (requestKey == key) {
                    request = null
                    result.onSuccess { bitmap = it; clamp(); invalidate() }.onFailure { requestKey = null }
                }
            }
            if (!delivered) request = mine
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            if (w != ow) { val id = pageId; val nb = repository.session?.notebook; if (id != null && nb != null) post { show(nb, id) } }
            clamp()
        }

        private fun scale(b: Bitmap) = width.toFloat() / b.width * zoom
        private fun clamp() {
            val b = bitmap ?: return
            val s = scale(b)
            scrollXPx = scrollXPx.coerceIn(0f, maxOf(0f, b.width * s - width))
            scrollYPx = scrollYPx.coerceIn(0f, maxOf(0f, b.height * s - height))
        }

        override fun onDraw(canvas: Canvas) {
            val b = bitmap ?: return
            val s = scale(b)
            matrix.setScale(s, s); matrix.postTranslate(-scrollXPx, -scrollYPx)
            canvas.drawBitmap(b, matrix, paint)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent.requestDisallowInterceptTouchEvent(true)
            scaler.onTouchEvent(e)
            if (!scaler.isInProgress) gestures.onTouchEvent(e)
            return true
        }
    }

    companion object {
        const val MIN_W = 220f
        const val MIN_H = 160f
        const val DEFAULT_W = 400f
        const val DEFAULT_H = 300f
        const val RADIUS = 18f
        const val BUTTON = 40f
        const val MARGIN = 12f
        const val CORNER_DP = 32f
        const val TAB_W = 44f
        const val TAB_H = 84f
        const val TAB_RADIUS = 14f
        const val PREVIOUS = "M14.5 6l-6 6 6 6"
        const val NEXT = "M9.5 6l6 6-6 6"
        const val FOLD = "M6 12h12"
        const val CLOSE = "M6.5 6.5l11 11M17.5 6.5l-11 11"
        const val CORNER = "M19 11v8h-8M19 19l-9-9"
    }
}
