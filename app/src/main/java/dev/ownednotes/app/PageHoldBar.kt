package dev.ownednotes.app

import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * P612: what a held page offers, as on the design canvas's Pages-Panel board. A glass bar (Duplicate, Settings,
 * Delete) with a "Drag to move" note under it, shown beside the page the moment it lifts, while the finger is still
 * down. It replaces the old dialog that only opened after letting go. The popup never takes focus, so the held finger
 * keeps driving the page: dragging hides the bar ([hide]) and moves the page as before. Let go without moving and the bar
 * stays to be tapped; a touch anywhere else closes it.
 */
internal class PageHoldBar(private val activity: MainActivity, private val night: Boolean) {
    private var popup: PopupWindow? = null
    /** The page the bar is showing actions for, or null when hidden (tests read it). */
    var pageId: String? = null; private set
    val showing get() = popup?.isShowing == true

    enum class Action(val label: String, val icon: String) {
        DUPLICATE("Duplicate", MenuIcons.DUPLICATE), SETTINGS("Settings", MenuIcons.SLIDERS), DELETE("Delete", MenuIcons.TRASH)
    }

    /**
     * Shows the bar beside [bounds], given relative to [anchor]: to its left when there is room, else right, else below.
     * With [besideAnchor] (the Pages card) it clears the whole card, not just the page, as on the board.
     */
    fun show(anchor: View, pageId: String, bounds: RectF, besideAnchor: Boolean = false) {
        hide()
        val c = anchor.context
        val column = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        val bar = LinearLayout(c).apply {
            background = Glass.float(c, night, 22f); elevation = c.dp(10f)
            setPadding(c.dpi(6f), c.dpi(6f), c.dpi(6f), c.dpi(6f))
        }
        for (action in Action.values()) bar.addView(button(c, action), LinearLayout.LayoutParams(c.dpi(62f), c.dpi(56f)).apply {
            if (action != Action.DUPLICATE) marginStart = c.dpi(2f)
        })
        column.addView(bar, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(c.dpi(8f), c.dpi(8f), c.dpi(8f), 0) })
        column.addView(TextView(c).apply {
            text = "Drag to move"; setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f); typeface = Tokens.SANS; setTextColor(Glass.secondary(night))
            background = rounded(if (night) 0xeb2a2f38.toInt() else 0xebffffff.toInt(), c.dp(10f))
            setPadding(c.dpi(9f), c.dpi(3f), c.dpi(9f), c.dpi(3f))
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(0, c.dpi(8f), c.dpi(8f), c.dpi(8f)) })
        column.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = column.measuredWidth; val h = column.measuredHeight

        val at = IntArray(2); anchor.getLocationInWindow(at)
        val root = anchor.rootView
        val gap = c.dpi(4f)
        val pageLeft = at[0] + if (besideAnchor) 0f else bounds.left
        val pageRight = at[0] + if (besideAnchor) anchor.width.toFloat() else bounds.right
        var x: Int; var y: Int
        when {
            pageLeft - w - gap >= 0 -> { x = (pageLeft - w - gap).toInt(); y = (at[1] + bounds.centerY() - h / 2f).toInt() }
            pageRight + w + gap <= root.width -> { x = (pageRight + gap).toInt(); y = (at[1] + bounds.centerY() - h / 2f).toInt() }
            else -> { x = (at[0] + bounds.centerX() - w / 2f).toInt(); y = (at[1] + bounds.bottom).toInt() }
        }
        x = x.coerceIn(0, maxOf(0, root.width - w)); y = y.coerceIn(0, maxOf(0, root.height - h))
        this.pageId = pageId
        popup = PopupWindow(column, WRAP_CONTENT, WRAP_CONTENT, false).apply {
            isTouchable = true; isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(0)); elevation = 0f
            setOnDismissListener { if (popup === this) { popup = null; this@PageHoldBar.pageId = null } }
            showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        }
    }

    fun hide() { popup?.let { popup = null; pageId = null; it.dismiss() } }

    /** Runs [action] for the page the bar is showing (tests call it as the button does). */
    fun perform(action: Action) {
        val id = pageId ?: return
        hide()
        when (action) {
            Action.DUPLICATE -> activity.duplicatePage(id)
            Action.SETTINGS -> activity.pageSettingsDialog(id)
            Action.DELETE -> activity.deletePageDialog(id)
        }
    }

    private fun button(c: android.content.Context, action: Action) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        val danger = action == Action.DELETE
        val tint = if (danger) Kit.dangerText(night) else Glass.accent(night)
        addView(android.widget.ImageView(c).apply { setImageDrawable(Kit.Icon(action.icon, tint, c.dpi(22f))) },
            LinearLayout.LayoutParams(c.dpi(22f), c.dpi(22f)))
        addView(TextView(c).apply {
            text = action.label; setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f); typeface = Tokens.SANS_MEDIUM; maxLines = 1
            setTextColor(if (danger) Kit.dangerText(night) else Glass.text(night))
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = c.dpi(3f) })
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(if (night) 0x30ffffff else 0x1f0a66d9),
            null, rounded(android.graphics.Color.WHITE, c.dp(16f)))
        contentDescription = action.label; isClickable = true; isFocusable = true
        setOnClickListener { perform(action) }
    }

    private fun rounded(color: Int, radius: Float) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = radius }
}
