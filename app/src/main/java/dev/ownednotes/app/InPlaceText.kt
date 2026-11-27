package dev.ownednotes.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.text.LineBreaker
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.Layout
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.PathParser
import dev.ownednotes.core.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * P681 (B70, board 19, look A): a text box is typed in where it sits on the page. A field lies exactly over the box (same
 * face, size, line spacing and width as the page draws it, scaled with the page); the tablet's keyboard comes up and one glass
 * text bar sits on top of it: font, − size +, bold, italic, underline, alignment, colour, lists, Done. Every change shows on
 * the box at once. Done, Back, or a tap outside the box ends it with one edit (one undo); a plain box left empty is removed.
 * Sizes are the board's, in dp.
 */
internal class InPlaceText(private val activity: MainActivity, private val root: FrameLayout, private val desk: FrameLayout) {
    private val c: Context = activity
    private val night get() = c.night

    private class Session(val canvas: InkCanvas, val document: NotebookRepository, val pageId: String, val textId: String?,
        val sticky: StickyNote?, var placement: ImagePlacement, var style: TextStyle, val original: String, val newBox: Boolean)
    private var session: Session? = null
    val typing get() = session != null
    /** For tests: the field being typed in, and the text bar. */
    internal val fieldForTest get() = field
    internal val barForTest get() = barScroll
    internal val popupForTest get() = popup

    // ---- the field over the page -----------------------------------------------------------------------------------
    @SuppressLint("AppCompatCustomView") // P707: a plain EditText like the rest of the app (no AppCompat views)
    private val field = object : EditText(c) {
        override fun onSelectionChanged(selStart: Int, selEnd: Int) { super.onSelectionChanged(selStart, selEnd); post { slide() } }
    }.apply {
        contentDescription = "Text box content"
        background = null; setPadding(0, 0, 0, 0); includeFontPadding = false; setLineSpacing(0f, 1.2f)
        isFallbackLineSpacing = false; breakStrategy = LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE; textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        isSingleLine = false; maxLines = Int.MAX_VALUE; filters = arrayOf(InputFilter.LengthFilter(TextBox.MAX_TEXT_LENGTH))
        gravity = Gravity.TOP or Gravity.START
    }
    /** The blue frame, 12 dp outside the words at the sides and 10 dp above and below (board 19's box). */
    private val frame = FrameLayout(c)
    private val handles = List(2) { View(c) }
    private val PAD_X get() = c.dpi(12f)
    private val PAD_Y get() = c.dpi(10f)
    /** Taps outside the box end typing. */
    private val catcher = object : View(c) {
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked != MotionEvent.ACTION_DOWN) return true
            if (insideFrame(event.rawX, event.rawY, c.dpi(14f)) || handles.any { it.visibility == View.VISIBLE && inside(it, event.rawX, event.rawY, c.dpi(10f)) }) return false
            // Not while this touch is being handed out: it takes this view away.
            post { done() }; return true
        }
    }
    /** P682: the frame may be turned with its box, so the point is turned back into the frame's own square first. */
    private fun insideFrame(x: Float, y: Float, slack: Int): Boolean {
        if (frame.parent == null) return false
        val at = IntArray(2); desk.getLocationOnScreen(at)
        val local = floatArrayOf(x - at[0] - frame.left, y - at[1] - frame.top)
        android.graphics.Matrix().also { frame.matrix.invert(it) }.mapPoints(local)
        return local[0] >= -slack && local[0] <= frame.width + slack && local[1] >= -slack && local[1] <= frame.height + slack
    }
    private fun inside(view: View, x: Float, y: Float, slack: Int): Boolean {
        if (view.parent == null) return false
        val at = IntArray(2); view.getLocationOnScreen(at)
        return x >= at[0] - slack && x <= at[0] + view.width + slack && y >= at[1] - slack && y <= at[1] + view.height + slack
    }

    // ---- the text bar (board 19, look A) --------------------------------------------------------------------------
    private val barScroll = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; isFillViewport = false; overScrollMode = View.OVER_SCROLL_NEVER }
    private val bar = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val fontName = label("Sans", 15f, 400)
    private val sizeLabel = label("24", 15f, 500).apply { fontFeatureSettings = "tnum"; gravity = Gravity.CENTER }
    private val boldKey = key(label("B", 18f, 700), "Bold") { setStyle { it.copy(weight = if (it.weight >= 600) 400 else 700) } }
    private val italicKey = key(label("I", 18f, 400).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC) }, "Italic") { setStyle { it.copy(italic = !it.italic) } }
    private val underlineKey = key(label("U", 17f, 400).apply { paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG }, "Underline") { setStyle { it.copy(underline = !it.underline) } }
    private val alignIcon = View(c)
    private val dot = Dot()
    private val fontKey: View; private val alignKey: View; private val colourKey: View
    private val popup = FrameLayout(c)
    private var popupFor: View? = null
    /**
     * The window is told not to move for the keyboard (the page must not jump), and then it is not told the keyboard's
     * height either; a one-pixel window that does shrink for it is what measures it.
     */
    private val probeView = View(c)
    private val probe = android.widget.PopupWindow(probeView, 1, WindowManager.LayoutParams.MATCH_PARENT).apply {
        isTouchable = false; isFocusable = false; isOutsideTouchable = false; setBackgroundDrawable(null)
        inputMethodMode = android.widget.PopupWindow.INPUT_METHOD_NEEDED
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }
    /** The keyboard's top in this window's coordinates, or no limit when the probe can't tell. */
    private fun probeTop(): Int {
        if (!probe.isShowing || probeView.height <= 0) return Int.MAX_VALUE
        val p = IntArray(2); probeView.getLocationOnScreen(p); val w = IntArray(2); root.rootView.getLocationOnScreen(w)
        val rect = android.graphics.Rect().also { probeView.getWindowVisibleDisplayFrame(it) }
        val bottom = minOf(p[1] + probeView.height, if (rect.bottom > 0) rect.bottom else Int.MAX_VALUE)
        return bottom - w[1]
    }


    private fun label(text: String, size: Float, weight: Int) = TextView(c).apply {
        this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_DIP, size); typeface = Typeface.create(Typeface.SANS_SERIF, weight, false)
        setTextColor(Glass.text(night)); includeFontPadding = false; gravity = Gravity.CENTER
    }
    private fun icon(path: String, size: Float, stroke: Float, color: Int = Glass.text(night)) =
        View(c).apply { background = PathIcon(path, color, stroke) }.also { it.tag = size }
    /** A 40 dp tall key inside a well; [on] lights it in the accent. */
    private fun key(content: View, label: String, width: Float = 40f, tap: () -> Unit) = FrameLayout(c).apply {
        contentDescription = label; tooltipText = label; isClickable = true; isFocusable = false
        foreground = ripple(night, c.dp(20f))
        val size = content.tag as? Float
        addView(content, if (size != null) FrameLayout.LayoutParams(c.dpi(size), c.dpi(size), Gravity.CENTER) else FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setOnClickListener { tap() }
        layoutParams = LinearLayout.LayoutParams(c.dpi(width), c.dpi(40f))
    }
    private fun lit(view: View, on: Boolean) { view.background = if (on) rounded(if (night) 0x332f7cf6 else 0x1f0a66d9, c.dp(20f)) else null }
    private fun row(vararg views: View) = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; views.forEach { addView(it) } }
    private fun well(width: Float, content: View) = FrameLayout(c).apply {
        background = GradientDrawable().apply {
            cornerRadius = c.dp(20f); setColor(if (night) 0x0dffffff else 0x091d2433)
            setStroke(maxOf(1, c.dpi(.5f)), if (night) 0x29ffffff else 0x241d2433)
        }
        addView(content, FrameLayout.LayoutParams(-1, -1))
        layoutParams = LinearLayout.LayoutParams(c.dpi(width), c.dpi(40f)).apply { marginEnd = c.dpi(6f) }
    }

    init {
        val chevron = { icon(CHEV, 16f, 2f, Glass.secondary(night)) }
        fontKey = key(row(label("Aa", 17f, 500), View(c).apply { layoutParams = LinearLayout.LayoutParams(c.dpi(6f), 1) }, fontName, chevron().also { it.layoutParams = LinearLayout.LayoutParams(c.dpi(16f), c.dpi(16f)) }), "Font and weight", 112f) { toggle(fontKey) { fontCard() } }
        bar.addView(well(112f, fontKey.apply { layoutParams = FrameLayout.LayoutParams(-1, -1) }))
        val smaller = key(icon(MINUS, 20f, 2f), "Smaller text") { setStyle { it.copy(size = (it.size - 2f).coerceIn(4f, 512f)) } }
        val larger = key(icon(PLUS, 20f, 2f), "Larger text") { setStyle { it.copy(size = (it.size + 2f).coerceIn(4f, 512f)) } }
        bar.addView(well(128f, row(smaller, sizeLabel.apply { layoutParams = LinearLayout.LayoutParams(c.dpi(48f), c.dpi(40f)) }, larger)))
        bar.addView(well(124f, row(boldKey, italicKey, underlineKey).apply { setPadding(c.dpi(2f), 0, 0, 0) }))
        alignIcon.tag = 20f
        alignKey = key(row(alignIcon.apply { layoutParams = LinearLayout.LayoutParams(c.dpi(20f), c.dpi(20f)) }, chevron().also { it.layoutParams = LinearLayout.LayoutParams(c.dpi(14f), c.dpi(14f)) }), "Alignment", 60f) { toggle(alignKey) { alignCard() } }
        bar.addView(well(60f, alignKey.apply { layoutParams = FrameLayout.LayoutParams(-1, -1) }))
        val dotView = View(c).apply { background = dot; layoutParams = LinearLayout.LayoutParams(c.dpi(24f), c.dpi(24f)) }
        colourKey = key(row(dotView, View(c).apply { layoutParams = LinearLayout.LayoutParams(c.dpi(4f), 1) }, chevron().also { it.layoutParams = LinearLayout.LayoutParams(c.dpi(14f), c.dpi(14f)) }), "Text colour and opacity", 60f) { toggle(colourKey) { colourCard() } }
        bar.addView(well(60f, colourKey.apply { layoutParams = FrameLayout.LayoutParams(-1, -1) }))
        val bullets = key(icon(BULLETS, 20f, 2.2f), "Bulleted list") { list(TextListEditing.Kind.BULLET) }
        val numbers = key(icon(NUMBERS, 20f, 1.8f), "Numbered list") { list(TextListEditing.Kind.NUMBER) }
        val checks = key(icon(CHECKS, 20f, 1.8f), "Checklist (hold to check or uncheck)") { list(TextListEditing.Kind.CHECKLIST) }
            .apply { setOnLongClickListener { list(null); true } }
        bar.addView(well(124f, row(bullets, numbers, checks).apply { setPadding(c.dpi(2f), 0, 0, 0) }))
        val done = FrameLayout(c).apply {
            contentDescription = "Done"; isClickable = true; background = rounded(Glass.accent(night), c.dp(20f)); foreground = ripple(true, c.dp(20f))
            addView(row(icon(CHECK, 18f, 2.4f, 0xffffffff.toInt()).also { it.layoutParams = LinearLayout.LayoutParams(c.dpi(18f), c.dpi(18f)) },
                View(c).apply { layoutParams = LinearLayout.LayoutParams(c.dpi(4f), 1) },
                label("Done", 15f, 500).apply { setTextColor(0xffffffff.toInt()) }), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            setOnClickListener { done() }
            layoutParams = LinearLayout.LayoutParams(c.dpi(84f), c.dpi(40f)).apply { marginStart = c.dpi(4f) }
        }
        bar.addView(done)
        bar.setPadding(c.dpi(6f), 0, c.dpi(6f), 0)
        barScroll.addView(bar, FrameLayout.LayoutParams(-2, c.dpi(52f)))
        barScroll.background = GradientDrawable().apply {
            cornerRadius = c.dp(26f); setColor(if (night) 0xf02a2f3a.toInt() else 0xf0ffffff.toInt())
            setStroke(maxOf(1, c.dpi(.5f)), if (night) 0x29ffffff else 0x1f1d2433)
        }
        barScroll.clipToOutline = true; barScroll.elevation = c.dp(8f)
        popup.elevation = c.dp(10f)
        handles.forEach { h ->
            h.background = GradientDrawable().apply { cornerRadius = c.dp(5f); setColor(if (night) Glass.PANE_NIGHT else -1); setStroke(c.dpi(1.5f), Glass.accent(night)) }
            h.elevation = c.dp(1f)
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { field.post { slide() } }
        })
        probeView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeBar() }
        field.setOnFocusChangeListener { _, focused -> if (focused) closePopup() }
        field.setOnClickListener { closePopup() }
        frame.setOnTouchListener(Drag { dx, dy -> move(dx, dy) })
        handles[0].setOnTouchListener(Drag { dx, _ -> resize(dx, left = true) })
        handles[1].setOnTouchListener(Drag { dx, _ -> resize(dx, left = false) })
        catcher.setOnApplyWindowInsetsListener { v, insets -> v.post { placeBar() }; insets }
        catcher.setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onProgress(insets: WindowInsets, running: MutableList<WindowInsetsAnimation>): WindowInsets { placeBar(insets); return insets }
            override fun onEnd(animation: WindowInsetsAnimation) { placeBar() }
        })
    }

    private val back = object : OnBackPressedCallback(false) { override fun handleOnBackPressed() { done() } }
    private var softInput = 0

    // ---- opening ---------------------------------------------------------------------------------------------------
    /** Insert › Text box…: a new box at [at], the tap's page point; the tap is the start of its first line. */
    fun openNew(canvas: InkCanvas, document: NotebookRepository, pageId: String, at: Point) {
        val page = document.session?.notebook?.pages?.firstOrNull { it.id == pageId } ?: return
        val style = TextStyle()
        val line = style.size * 1.2f
        val w = minOf(page.width, maxOf(120f, page.width * .6f))
        val x = at.x.coerceIn(0f, maxOf(0f, page.width - w)); val y = (at.y - line / 2).coerceIn(0f, maxOf(0f, page.height - line))
        val placement = runCatching { ImagePlacement(x, y, w, line) }.getOrNull() ?: return
        open(Session(canvas, document, pageId, null, null, placement, style, "", newBox = true), null)
    }
    /** An existing box (or a sticky note's text). [cursor] is where it was tapped, a page point; null puts the cursor at the end. */
    fun openExisting(canvas: InkCanvas, document: NotebookRepository, textId: String, cursor: Point? = null): Boolean {
        if (session != null) return false
        val page = document.currentPage ?: return false
        val box = page.textBoxes.firstOrNull { it.id == textId } ?: return false
        if (document.readOnly || box.locked || !page.canEdit(box) || box.sticky?.collapsed == true) return false
        // P682: a turned box is typed in place too, the field turned with it. P684: a turned handwritten note as well.
        open(Session(canvas, document, page.id, box.id, box.sticky, box.placement, box.style, box.text, newBox = false), cursor)
        return true
    }
    private fun open(s: Session, cursor: Point?) {
        if (session != null) return
        val document = s.document
        if (document.readOnly) return
        if (!s.canvas.isEditing && s.canvas.selectedCount > 0) s.canvas.clearSelection()
        session = s
        s.canvas.typingTextId = s.textId
        field.setText(s.original)
        applyStyle()
        val ink = s.sticky?.ink == true
        frame.background = if (ink) null else GradientDrawable().apply {
            cornerRadius = c.dp(6f); setColor(if (night) 0x142f7cf6 else 0x0a0a66d9); setStroke(c.dpi(1.5f), Glass.accent(night))
        }
        handles.forEach { it.visibility = if (ink) View.GONE else View.VISIBLE }
        frame.setPadding(if (ink) 0 else PAD_X, if (ink) 0 else PAD_Y, if (ink) 0 else PAD_X, if (ink) 0 else PAD_Y)
        if (field.parent == null) frame.addView(field, FrameLayout.LayoutParams(-2, -2))
        desk.addView(frame, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT))
        handles.forEach { desk.addView(it, FrameLayout.LayoutParams(c.dpi(10f), c.dpi(28f), Gravity.TOP or Gravity.LEFT)) }
        root.addView(catcher, FrameLayout.LayoutParams(-1, -1))
        root.addView(barScroll, FrameLayout.LayoutParams(-2, c.dpi(52f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { leftMargin = c.dpi(16f); rightMargin = c.dpi(16f) })
        root.addView(popup, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT))
        barScroll.alpha = 0f
        place()
        softInput = activity.window.attributes.softInputMode
        activity.window.setSoftInputMode((softInput and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        activity.onBackPressedDispatcher.addCallback(activity, back.apply { isEnabled = true })
        field.requestFocus()
        root.post { if (session != null && !probe.isShowing && root.isAttachedToWindow) runCatching { probe.showAtLocation(root, Gravity.TOP or Gravity.START, 0, 0) } }
        field.post {
            val end = field.length()
            val offset = cursor?.let { unturned(s, it) }?.let { at -> deskPoint(at).let { (x, y) -> field.getOffsetForPosition(x - frame.x - field.left, y - frame.y - field.top) } } ?: end
            field.setSelection(offset.coerceIn(0, end))
            (c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            placeBar()
        }
        // Some keyboards arrive without an insets animation; look again while it comes up.
        listOf(150L, 350L, 700L).forEach { root.postDelayed({ placeBar() }, it) }
    }

    // ---- placing ---------------------------------------------------------------------------------------------------
    private val scale get() = session?.canvas?.pageScale ?: 1f
    /** A page point of the session's page in [desk] coordinates. */
    private fun deskPoint(p: Point): Pair<Float, Float> {
        val s = session!!; val v = s.canvas.pageToView(p.x, p.y)
        // The canvas may sit inside a pane, not straight in the desk.
        val a = IntArray(2); s.canvas.getLocationInWindow(a); val d = IntArray(2); desk.getLocationInWindow(d)
        return (a[0] - d[0] + v[0]) to (a[1] - d[1] + v[1])
    }
    /** Where the words start on the page: the box's corner, or under a handwritten note's header. */
    private fun textOrigin(s: Session) = if (s.sticky?.ink == true) Point(s.placement.x + InkNoteLook.TEXT_INSET, s.placement.y + InkNoteLook.HEADER + 6 * InkNoteLook.U) else Point(s.placement.x, s.placement.y)
    /** Lays the field over the box; called again whenever the page moves or zooms. */
    fun place() {
        val s = session ?: return
        val k = scale
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, s.style.size * k)
        val (x, y) = deskPoint(textOrigin(s))
        val inkNote = s.sticky?.ink == true
        val w = ceil(TextRendering.width(s.placement.width, inkNote) * k).toInt().coerceAtLeast(1)
        val minH = if (inkNote) 0 else if (s.newBox) (s.style.size * 1.2f * k).roundToInt() else (s.placement.height * k).roundToInt()
        field.layoutParams = (field.layoutParams as FrameLayout.LayoutParams).apply { width = w; height = -2 }
        field.minHeight = minH; field.minimumHeight = minH
        frame.translationX = x - frame.paddingLeft; frame.translationY = y - frame.paddingTop
        // P682: a turned box turns about its centre on the page, as TextRendering draws it.
        val degrees = turn(s)
        if (degrees != 0f) {
            val (cx, cy) = deskPoint(Point(s.placement.x + s.placement.width / 2, s.placement.y + s.placement.height / 2))
            frame.pivotX = cx - frame.left - frame.translationX; frame.pivotY = cy - frame.top - frame.translationY
        }
        frame.rotation = degrees
        frame.requestLayout()
        frame.post { placeHandles() }
    }
    /** The angle the field is turned by: the box's own (P684: handwritten notes too; their words start under the header). */
    private fun turn(s: Session) = s.placement.degrees
    /** A page point turned back by the box's angle about its centre, so a tap on turned words finds the right letter. */
    private fun unturned(s: Session, at: Point): Point {
        val degrees = turn(s); if (degrees == 0f) return at
        val cx = s.placement.x + s.placement.width / 2; val cy = s.placement.y + s.placement.height / 2
        val r = Math.toRadians(-degrees.toDouble()); val cos = kotlin.math.cos(r).toFloat(); val sin = kotlin.math.sin(r).toFloat()
        val dx = at.x - cx; val dy = at.y - cy
        return Point(cx + dx * cos - dy * sin, cy + dx * sin + dy * cos)
    }
    private fun placeHandles() {
        val s = session ?: return
        // Width handles on a turned box would sit off its sides; it keeps its width there.
        if (turn(s) != 0f) { handles.forEach { it.visibility = View.GONE }; return }
        val mid = frame.translationY + frame.height / 2f - c.dp(14f)
        handles[0].translationX = frame.translationX - c.dp(5f); handles[1].translationX = frame.translationX + frame.width - c.dp(5f)
        handles.forEach { it.translationY = mid }
    }
    private fun placeBar(insets: WindowInsets? = null) {
        if (session == null || barScroll.parent == null) return
        val all = insets ?: root.rootWindowInsets ?: return
        val ime = all.getInsets(WindowInsets.Type.ime()).bottom
        val nav = all.getInsets(WindowInsets.Type.navigationBars()).bottom
        val at = IntArray(2); root.getLocationInWindow(at)
        // The visible frame also shrinks for the keyboard on windows that are not handed its insets.
        val keyboardTop = minOf(root.rootView.height - maxOf(ime, nav), probeTop())
        val bottom = minOf(root.height, keyboardTop - at[1]) - c.dpi(10f)
        barScroll.translationY = (bottom - c.dpi(52f)).toFloat()
        barScroll.alpha = 1f
        placePopup()
        slide()
    }
    /** If the line being typed would sit under the bar, the page slides up so it stays just above it; it slides back when there is room. */
    private fun slide() {
        if (session == null || barScroll.parent == null || field.layout == null) return
        val line = field.layout.getLineForOffset(field.selectionEnd.coerceAtLeast(0))
        val at = IntArray(2); field.getLocationInWindow(at)
        val caret = at[1] + field.layout.getLineBottom(line) + field.paddingTop
        val barAt = IntArray(2); barScroll.getLocationInWindow(barAt)
        val limit = barAt[1] - c.dpi(12f)
        val now = desk.translationY
        val next = if (caret > limit) now - (caret - limit) else minOf(0f, now + (limit - caret))
        if (abs(next - now) > .5f) { desk.translationY = next; placeHandles() }
    }

    // ---- style -----------------------------------------------------------------------------------------------------
    private fun setStyle(change: (TextStyle) -> TextStyle) {
        val s = session ?: return
        s.style = runCatching { change(s.style) }.getOrDefault(s.style)
        applyStyle(); place(); placePopup()
    }
    private fun applyStyle() {
        val s = session ?: return; val st = s.style
        field.typeface = Typeface.create(Typeface.create(st.font.cssFamily, Typeface.NORMAL), st.weight, st.italic)
        field.paintFlags = if (st.underline) field.paintFlags or Paint.UNDERLINE_TEXT_FLAG else field.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        field.setTextColor(st.color.toInt())
        field.highlightColor = (Glass.accent(night) and 0xffffff) or 0x40000000
        field.textCursorDrawable = GradientDrawable().apply { setColor(Glass.accent(night)); setSize(c.dpi(2f), 1) }
        field.gravity = Gravity.TOP or when (st.alignment) { TextAlignment.START -> Gravity.START; TextAlignment.CENTER -> Gravity.CENTER_HORIZONTAL; TextAlignment.END -> Gravity.END }
        fontName.text = FONTS[st.font.ordinal].first
        sizeLabel.text = if (st.size == st.size.toInt().toFloat()) st.size.toInt().toString() else "%.1f".format(st.size)
        lit(boldKey, st.weight >= 600); lit(italicKey, st.italic); lit(underlineKey, st.underline)
        alignIcon.background = PathIcon(ALIGN[st.alignment.ordinal], Glass.text(night), 2f)
        dot.color = (st.color or 0xff000000L).toInt(); dot.invalidateSelf()
    }
    private fun list(kind: TextListEditing.Kind?) {
        val value = field.text.toString()
        val start = field.selectionStart.coerceIn(0, value.length); val end = field.selectionEnd.coerceIn(0, value.length)
        val result = if (kind == null) TextListEditing.toggleChecks(value, start, end) else TextListEditing.apply(value, start, end, kind)
        if (result.text.length > TextBox.MAX_TEXT_LENGTH) { activity.message("The list would make the text too long."); return }
        if (result.text != value) field.text.replace(0, field.length(), result.text)
        field.setSelection(result.selectionStart.coerceIn(0, field.length()), result.selectionEnd.coerceIn(0, field.length()))
    }

    // ---- pop-ups just above the bar --------------------------------------------------------------------------------
    private fun toggle(opener: View, build: () -> View) {
        if (popupFor === opener) { closePopup(); return }
        popup.removeAllViews(); popupFor = opener
        popup.addView(build(), FrameLayout.LayoutParams(-2, -2))
        popup.visibility = View.VISIBLE
        popup.post { placePopup() }
    }
    private fun closePopup() { popupFor = null; popup.removeAllViews(); popup.visibility = View.GONE }
    private fun placePopup() {
        val opener = popupFor ?: return
        if (popup.childCount == 0) return
        val at = IntArray(2); opener.getLocationInWindow(at); val base = IntArray(2); root.getLocationInWindow(base)
        val w = popup.getChildAt(0).width.takeIf { it > 0 } ?: popup.width
        val h = popup.getChildAt(0).height.takeIf { it > 0 } ?: popup.height
        popup.translationX = (at[0] - base[0]).toFloat().coerceIn(c.dp(8f), maxOf(c.dp(8f), root.width - w - c.dp(8f)))
        popup.translationY = barScroll.translationY - c.dp(8f) - h
    }
    private fun card() = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = c.dp(16f); setColor(if (night) 0xf82a2f3a.toInt() else 0xf8ffffff.toInt())
            setStroke(maxOf(1, c.dpi(.5f)), if (night) 0x29ffffff else 0x241d2433)
        }
        setPadding(c.dpi(8f), c.dpi(8f), c.dpi(8f), c.dpi(8f))
    }
    /** Aa: the three fonts, each in its own face, then the weight. */
    private fun fontCard(): View = card().apply {
        val st = session?.style ?: return@apply
        FONTS.forEachIndexed { i, (name, face) ->
            val on = st.font.ordinal == i
            addView(FrameLayout(c).apply {
                contentDescription = name; isClickable = true; foreground = ripple(night, c.dp(10f))
                background = if (on) rounded(if (night) 0x332f7cf6 else 0x1a0a66d9, c.dp(10f)) else null
                addView(label(name, 17f, 400).apply { typeface = face; gravity = Gravity.CENTER_VERTICAL }, FrameLayout.LayoutParams(-2, -1, Gravity.START).apply { leftMargin = c.dpi(14f) })
                if (on) addView(icon(CHECK, 20f, 2.4f, Glass.accent(night)), FrameLayout.LayoutParams(c.dpi(20f), c.dpi(20f), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = c.dpi(14f) })
                setOnClickListener { setStyle { it.copy(font = TextFont.entries[i]) }; closePopup() }
            }, LinearLayout.LayoutParams(c.dpi(184f), c.dpi(40f)).apply { if (i > 0) topMargin = c.dpi(4f) })
        }
        addView(View(c).apply { setBackgroundColor(if (night) 0x1fffffff else 0x1a1d2433) }, LinearLayout.LayoutParams(-1, maxOf(1, c.dpi(.5f))).apply { topMargin = c.dpi(6f); bottomMargin = c.dpi(6f) })
        addView(LinearLayout(c).apply {
            WEIGHTS.forEach { (name, weight) ->
                val on = st.weight == weight
                addView(label(name, 13f, weight).apply {
                    contentDescription = "$name weight"; isClickable = true; foreground = ripple(night, c.dp(10f))
                    background = if (on) rounded(if (night) 0x332f7cf6 else 0x1a0a66d9, c.dp(10f)) else null
                    setOnClickListener { setStyle { s -> s.copy(weight = weight) }; closePopup() }
                }, LinearLayout.LayoutParams(c.dpi(46f), c.dpi(32f)))
            }
        })
    }
    private fun alignCard(): View = card().apply {
        orientation = LinearLayout.HORIZONTAL
        val st = session?.style ?: return@apply
        ALIGN.forEachIndexed { i, path ->
            addView(FrameLayout(c).apply {
                contentDescription = listOf("Align start", "Centre", "Align end")[i]; isClickable = true; foreground = ripple(night, c.dp(10f))
                background = if (st.alignment.ordinal == i) rounded(if (night) 0x332f7cf6 else 0x1a0a66d9, c.dp(10f)) else null
                addView(icon(path, 20f, 2f), FrameLayout.LayoutParams(c.dpi(20f), c.dpi(20f), Gravity.CENTER))
                setOnClickListener { setStyle { it.copy(alignment = TextAlignment.entries[i]) }; closePopup() }
            }, LinearLayout.LayoutParams(c.dpi(44f), c.dpi(40f)).apply { if (i > 0) marginStart = c.dpi(4f) })
        }
    }
    /** The board's six colours, then the opacity. */
    private fun colourCard(): View = card().apply {
        setPadding(c.dpi(12f), c.dpi(12f), c.dpi(12f), c.dpi(10f))
        val st = session?.style ?: return@apply
        addView(LinearLayout(c).apply {
            COLOURS.forEachIndexed { i, colour ->
                val on = (st.color and 0xffffff) == (colour.toLong() and 0xffffff)
                addView(View(c).apply {
                    contentDescription = COLOUR_NAMES[i]; isClickable = true
                    background = Dot().apply { color = colour; ring = if (on) Glass.accent(night) else 0x2e1d2433; ringWidth = if (on) 3.5f else 3f; gap = if (night) Glass.PANE_NIGHT else -1 }
                    setOnClickListener { setStyle { s -> s.copy(color = (s.color and 0xff000000L) or (colour.toLong() and 0xffffff)) }; closePopup() }
                }, LinearLayout.LayoutParams(c.dpi(28f), c.dpi(28f)).apply { if (i > 0) marginStart = c.dpi(8f) })
            }
        })
        val opacity = label("", 12f, 400).apply { setTextColor(Glass.secondary(night)); gravity = Gravity.START }
        fun show(alpha: Int) { opacity.text = "Opacity ${(alpha * 100f / 255f).roundToInt()}%" }
        addView(SeekBar(c).apply {
            max = 255; progress = (st.color ushr 24).toInt(); contentDescription = "Text opacity"
            val fill = android.graphics.drawable.ClipDrawable(rounded(Glass.text(night), c.dp(2f)), Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
            progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(rounded(Kit.track(night), c.dp(2f)), fill)).apply {
                setId(0, android.R.id.background); setId(1, android.R.id.progress)
                for (i in 0..1) { setLayerGravity(i, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL); setLayerHeight(i, c.dpi(4f)) }
            }
            thumb = ToolControls.Knob(c.dpi(20f), c.dp(10f)); splitTrack = false; background = null; setPadding(c.dpi(10f), 0, c.dpi(10f), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) { show(value); if (fromUser) setStyle { s -> s.copy(color = (s.color and 0xffffff) or (value.toLong() shl 24)) } }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }, LinearLayout.LayoutParams(c.dpi(228f), c.dpi(28f)).apply { topMargin = c.dpi(6f); marginStart = -c.dpi(10f) })
        show((st.color ushr 24).toInt())
        addView(opacity)
    }

    // ---- moving and widening -------------------------------------------------------------------------------------
    private inner class Drag(val step: (Float, Float) -> Unit) : View.OnTouchListener {
        private var x = 0f; private var y = 0f; private var moving = false
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x = e.rawX; y = e.rawY; moving = false }
                MotionEvent.ACTION_MOVE -> {
                    if (!moving && abs(e.rawX - x) + abs(e.rawY - y) > ViewConfiguration.get(c).scaledTouchSlop) moving = true
                    if (moving) { step(e.rawX - x, e.rawY - y); x = e.rawX; y = e.rawY }
                }
            }
            return true
        }
    }
    private fun move(dx: Float, dy: Float) {
        val s = session ?: return; val k = scale
        runCatching { s.placement.translated(dx / k, dy / k) }.onSuccess { s.placement = it; place() }
    }
    private fun resize(dx: Float, left: Boolean) {
        val s = session ?: return; val k = scale; val p = s.placement
        val d = dx / k
        val width = (if (left) p.width - d else p.width + d).coerceAtLeast(MIN_WIDTH)
        val x = if (left) p.x + p.width - width else p.x
        runCatching { p.copy(x = x, width = width) }.onSuccess { s.placement = it; place() }
    }

    // ---- ending ----------------------------------------------------------------------------------------------------
    /** Done, Back, a tap outside, or the app going to the background: one edit, then the box is plain text on the page again. */
    fun done() {
        val s = session ?: return
        val text = field.text.toString()
        session = null
        (c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)
        field.clearFocus()
        listOf(catcher, barScroll, popup).forEach { root.removeView(it) }
        runCatching { probe.dismiss() }
        desk.removeView(frame); handles.forEach { desk.removeView(it) }
        closePopup()
        desk.translationY = 0f
        back.isEnabled = false; back.remove()
        activity.window.setSoftInputMode(softInput)
        s.canvas.typingTextId = null
        commit(s, text)
    }
    /** [tries]: a save or an import may hold the notebook a moment; the words wait for it rather than being lost. */
    private fun commit(s: Session, text: String, tries: Int = 0) {
        val document = s.document
        if (document.busy && !document.readOnly && tries < 50) { root.postDelayed({ commit(s, text, tries + 1) }, 100); return }
        val page = document.session?.notebook?.pages?.firstOrNull { it.id == s.pageId }
        if (page == null || document.readOnly || document.busy) {
            if (text != s.original) activity.message("The page changed while you typed, so the text was not saved.")
            return
        }
        val inkNote = s.sticky?.ink == true
        val width = TextRendering.width(s.placement.width, inkNote)
        val words = if (text.isEmpty()) s.style.size * 1.2f else TextRendering.build(TextRendering.Key(text, s.style, width)).height.toFloat()
        runCatching {
            if (s.textId == null) {
                if (text.isBlank()) return
                val placement = s.placement.copy(height = maxOf(1f, words))
                document.edit { it.insertTextBox(s.pageId, text, placement, s.style); true }
            } else {
                val box = page.textBoxes.firstOrNull { it.id == s.textId } ?: run {
                    if (text != s.original) activity.message("The text box was removed while you typed, so the text was not saved.")
                    return
                }
                if (text.isEmpty() && box.sticky == null) { document.edit { it.removeTextBoxes(s.pageId, setOf(box.id)) }; return }
                val needed = if (inkNote) InkNoteLook.HEADER + 6 * InkNoteLook.U + words + 8 * InkNoteLook.U else words
                val placement = s.placement.copy(height = maxOf(s.placement.height, needed))
                val changed = box.copy(text = text, style = s.style, placement = placement)
                if (changed != box) document.edit { it.updateTextBox(s.pageId, changed) }
            }
        }.onFailure { activity.message("The text could not be saved: ${it.message}") }
    }

    /** A colour dot: [color] inside a [gap] ring and a thin outer [ring], as on board 19. */
    private inner class Dot : Drawable() {
        var color = 0xff172a3a.toInt(); var ring = 0x401d2433; var ringWidth = 1f; var gap = if (night) Glass.PANE_NIGHT else -1
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val b = bounds; val r = minOf(b.width(), b.height()) / 2f; val cx = b.exactCenterX(); val cy = b.exactCenterY()
            val d = c.dp(1f)
            paint.style = Paint.Style.FILL
            paint.color = ring; canvas.drawCircle(cx, cy, r, paint)
            paint.color = gap; canvas.drawCircle(cx, cy, r - (ringWidth - 2f).coerceAtLeast(1f) * d, paint)
            paint.color = color; canvas.drawCircle(cx, cy, r - ringWidth * d, paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        const val MIN_WIDTH = 40f
        const val MINUS = "M5 12h14"; const val PLUS = "M12 5v14M5 12h14"; const val CHEV = "M7 10l5 5 5-5"; const val CHECK = "M5 12.5l4.5 4.5L19 7.5"
        const val BULLETS = "M10 6h10M10 12h10M10 18h10M4.5 6h.01M4.5 12h.01M4.5 18h.01"
        const val NUMBERS = "M11 6h9M11 12h9M12 18h8M4 16a2 2 0 1 1 4 0c0 .6 -.5 1 -1 1.5L4 20h4M6 10V4L4 6"
        const val CHECKS = "M3.5 5.5l1.5 1.5 2.5 -2.5M3.5 11.5l1.5 1.5 2.5 -2.5M3.5 17.5l1.5 1.5 2.5 -2.5M11 6h9M11 12h9M11 18h9"
        val ALIGN = listOf("M4 6h16M4 12h10M4 18h14", "M4 6h16M7 12h10M5 18h14", "M4 6h16M10 12h10M6 18h14")
        val FONTS = listOf("Sans" to Typeface.SANS_SERIF, "Serif" to Typeface.SERIF, "Mono" to Typeface.MONOSPACE)
        val WEIGHTS = listOf("Light" to 300, "Regular" to 400, "Bold" to 700, "Black" to 900)
        val COLOURS = listOf(0xff172a3a.toInt(), 0xff1d4ed8.toInt(), 0xffc62828.toInt(), 0xff2e7d32.toInt(), 0xff6a1b9a.toInt(), 0xffe65100.toInt())
        val COLOUR_NAMES = listOf("Ink", "Blue", "Red", "Green", "Purple", "Orange")
    }
}

/** A 24-unit SVG path, stroked; [stroke] is in path units. */
internal class PathIcon(data: String, private val color: Int, private val stroke: Float) : Drawable() {
    private val path: Path = PathParser.createPathFromPathData(data)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun draw(canvas: Canvas) {
        val b = bounds; val k = minOf(b.width(), b.height()) / 24f
        canvas.save(); canvas.translate(b.left.toFloat(), b.top.toFloat()); canvas.scale(k, k)
        paint.color = color; paint.strokeWidth = stroke; canvas.drawPath(path, paint)
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
