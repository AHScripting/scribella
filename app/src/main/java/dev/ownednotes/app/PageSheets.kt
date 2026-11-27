package dev.ownednotes.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.ownednotes.app.SyncPanels.P

/**
 * P733 (design canvas board 26): the editor's older dialogs as Glass sheets in board 21's Look A, every button of the
 * old dialogs kept and their explanations behind (i): Layers, Bookmarks, Navigation history and Delete page
 * ([MainActivity]) and Earlier versions ([LibraryScreen]). This file holds their parts; the sheets themselves stay with
 * the code that changes the notebook.
 */
internal object PageSheets {
    /** Bookmarks as shown: the sheet, a row per bookmark (tap to go there) and each row's outline level. */
    class Bookmarks(val sheet: Kit.Sheet, val rows: List<SyncPanels.RowCard>, val levels: List<Int>)
    /** Navigation history as shown: the sheet and its Back and Forward tiles. */
    class History(val sheet: Kit.Sheet, val back: SyncPanels.PlaceTile, val forward: SyncPanels.PlaceTile)
    /** Delete page as shown: the sheet and its red button. */
    class DeletePage(val sheet: Kit.Sheet, val delete: TextView)

    /** Board 21's card: a hint of [color] at the top fading to the card colour, with a hairline edge. */
    class TintCard(private val night: Boolean, private val color: Int, private val radius: Float, private val hair: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val b = bounds
            val fill = Kit.cardFill(night)
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.top + b.height() * .7f, blend(fill, color, if (night) .10f else .06f), fill, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), radius, radius, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE; paint.strokeWidth = hair; paint.color = if (night) Glass.RULE_NIGHT else Glass.RULE
            canvas.drawRoundRect(b.left + hair / 2, b.top + hair / 2, b.right - hair / 2, b.bottom - hair / 2, radius, radius, paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    /**
     * "None yet": a dashed picture and a few [words] in a tinted card, with a [detail] line or a [hint] under them and an
     * (i) in the corner when given.
     */
    class Empty(context: Context, path: String, color: Int, words: String, detail: String? = null, info: Explain.Button? = null, hint: View? = null) : FrameLayout(context) {
        init {
            val night = context.night
            background = TintCard(night, color, context.dp(Glass.CARD), maxOf(1f, context.dp(.5f)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dpi(20f), dpi(26f), dpi(20f), dpi(if (hint != null || detail != null) 20f else 26f))
                addView(View(context).apply { background = Explain.Disc(color, path, 34f / 72f, dashed = true); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                    LinearLayout.LayoutParams(dpi(72f), dpi(72f)))
                addView(SyncPanels.label(context, words, 18f, true, Glass.text(night)).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(14f) })
                detail?.let { addView(SyncPanels.label(context, it, 14f, false, Glass.secondary(night)).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(6f) }) }
                hint?.let { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10f) }) }
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            info?.let { addView(it, LayoutParams(dpi(44f), dpi(44f), Gravity.TOP or Gravity.END).apply { topMargin = dpi(8f); marginEnd = dpi(8f) }) }
        }
    }

    /** Where to find something, as the menu path the board draws: "≡ Page menu › [bookmark] Bookmark page". */
    fun menuPath(context: Context, menu: String, item: String, itemPath: String) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        val night = context.night
        fun icon(path: String, color: Int, size: Float) = View(context).apply {
            background = Kit.Icon(path, color, dpi(size)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(icon(P.MENU, Glass.secondary(night), 20f), LinearLayout.LayoutParams(dpi(20f), dpi(20f)))
        addView(SyncPanels.label(context, menu, 14f, true, Glass.secondary(night)), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dpi(8f) })
        addView(SyncPanels.chevron(context), LinearLayout.LayoutParams(dpi(16f), dpi(16f)).apply { marginStart = dpi(6f) })
        addView(icon(itemPath, Glass.accent(night), 20f), LinearLayout.LayoutParams(dpi(20f), dpi(20f)).apply { marginStart = dpi(6f) })
        addView(SyncPanels.label(context, item, 14f, true, Kit.accentText(night)), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dpi(6f) })
        contentDescription = "$menu, $item"
    }

    /**
     * A 36 dp round control in a grey well (red for [danger]) with an icon [path], or [text] in a wider well; disabled
     * ones fade. [description] is what a screen reader says and what tests find.
     */
    fun well(context: Context, path: String?, text: String?, description: String, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit): View {
        val night = context.night
        val ink = if (danger) Kit.dangerText(night) else Glass.text(night)
        val fill = if (danger) Kit.dangerFill(night) else Kit.well(night)
        val radius = context.dp(18f)
        val view: View = if (path != null) View(context).apply {
            background = android.graphics.drawable.LayerDrawable(arrayOf(rounded(fill, radius), Kit.Icon(path, ink, dpi(20f)))).apply {
                val inset = dpi(8f); setLayerInset(1, inset, inset, inset, inset)
            }
            layoutParams = LinearLayout.LayoutParams(dpi(36f), dpi(36f))
        } else TextView(context).apply {
            this.text = text; textSize = 13f; typeface = Tokens.SANS_MEDIUM; includeFontPadding = false; gravity = Gravity.CENTER
            setTextColor(ink); background = rounded(fill, radius); maxLines = 1
            layoutParams = LinearLayout.LayoutParams(dpi(54f), dpi(36f))
        }
        return view.apply {
            contentDescription = description; isClickable = true; isFocusable = true; isEnabled = enabled
            alpha = if (enabled) 1f else .35f
            foreground = ripple(night, radius)
            setOnClickListener { runCatching(onClick) }
        }
    }

    /**
     * One layer on the Layers sheet: its picture (ticked when it is the one being drawn on), name and state, which
     * together are tapped to draw on it, then [controls] as round buttons. The controls sit beside the name when there
     * is room and under it on a narrow screen.
     */
    class LayerRow(context: Context, name: String, state: String, active: Boolean, hidden: Boolean, private val controls: List<View>, onPick: (() -> Unit)?) : ViewGroup(context) {
        private val head = LinearLayout(context)
        private val tools = LinearLayout(context)
        init {
            val night = context.night
            background = if (active) rounded(blend(Kit.cardFill(night), Glass.accent(night), .04f), context.dp(Glass.CARD), Explain.alpha(Glass.accent(night), .55f), context.dpi(1.5f))
                else SyncPanels.cardBackground(context)
            setPadding(dpi(14f), dpi(14f), dpi(14f), dpi(14f))
            head.apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(FrameLayout(context).apply {
                    addView(View(context).apply { background = Explain.Disc(if (active) Glass.accent(night) else SyncPanels.slate(night), P.LAYERS, 20f / 36f) },
                        FrameLayout.LayoutParams(dpi(36f), dpi(36f)))
                    if (active) addView(View(context).apply { background = Explain.Badge(Glass.accent(night), P.CHECK, dpi(20f)) },
                        FrameLayout.LayoutParams(dpi(20f), dpi(20f)).apply { leftMargin = dpi(24f); topMargin = dpi(24f) })
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dpi(44f), dpi(44f)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(SyncPanels.label(context, name, 15f, true, if (hidden) Glass.secondary(night) else Glass.text(night)).apply {
                        maxLines = if (context.largeText) 3 else 1; ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(SyncPanels.label(context, state, 12.5f, false, Glass.secondary(night)).apply { maxLines = if (context.largeText) 3 else 1; ellipsize = TextUtils.TruncateAt.END },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(3f) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpi(10f) })
                contentDescription = "Layer $name"
                if (onPick != null) {
                    isClickable = true; isFocusable = true; foreground = ripple(night, context.dp(14f))
                    setOnClickListener { runCatching(onPick) }
                }
            }
            tools.apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                controls.forEachIndexed { i, control ->
                    addView(control, (control.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(dpi(36f), dpi(36f))).apply { if (i > 0) marginStart = dpi(6f) })
                }
            }
            addView(head); addView(tools)
        }
        private var stacked = false
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val width = MeasureSpec.getSize(widthSpec)
            val inner = width - paddingLeft - paddingRight
            tools.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val gap = dpi(12f)
            val toolsWidth = if (controls.isEmpty()) 0 else tools.measuredWidth + gap
            stacked = controls.isNotEmpty() && inner - toolsWidth < dpi(150f)
            val headWidth = if (stacked) inner else inner - toolsWidth
            head.measure(MeasureSpec.makeMeasureSpec(maxOf(0, headWidth), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val content = if (stacked) head.measuredHeight + dpi(10f) + tools.measuredHeight else maxOf(head.measuredHeight, tools.measuredHeight)
            setMeasuredDimension(width, content + paddingTop + paddingBottom)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val inner = r - l - paddingLeft - paddingRight
            if (stacked) {
                head.layout(paddingLeft, paddingTop, paddingLeft + head.measuredWidth, paddingTop + head.measuredHeight)
                val y = paddingTop + head.measuredHeight + dpi(10f)
                val x = paddingLeft + inner - tools.measuredWidth
                tools.layout(x, y, x + tools.measuredWidth, y + tools.measuredHeight)
            } else {
                val h = maxOf(head.measuredHeight, tools.measuredHeight)
                val hy = paddingTop + (h - head.measuredHeight) / 2
                head.layout(paddingLeft, hy, paddingLeft + head.measuredWidth, hy + head.measuredHeight)
                val ty = paddingTop + (h - tools.measuredHeight) / 2
                val x = paddingLeft + inner - tools.measuredWidth
                tools.layout(x, ty, x + tools.measuredWidth, ty + tools.measuredHeight)
            }
        }
    }

    /** What an action will do, in a card: a disc, a [title] and a short [line] (Delete page). */
    fun consequence(context: Context, path: String, color: Int, title: String, line: String) = LinearLayout(context).apply {
        val night = context.night
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = SyncPanels.cardBackground(context)
        setPadding(dpi(18f), dpi(20f), dpi(18f), dpi(20f))
        addView(SyncPanels.disc(context, path, color, 48f, 26f))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(SyncPanels.label(context, title, 16f, true, Glass.text(night)).apply { maxLines = 3 })
            addView(SyncPanels.label(context, line, 13.5f, false, Glass.secondary(night)).apply { setLineSpacing(0f, 1.15f) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(6f) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpi(16f) })
    }

    /** An (i) that opens [title]'s card of [lines]. */
    fun info(context: Context, label: String, title: String, lines: List<Explain.Line>) = Explain.Button(context, label).apply {
        setOnClickListener { Explain.show(this, title, lines) }
    }
}
