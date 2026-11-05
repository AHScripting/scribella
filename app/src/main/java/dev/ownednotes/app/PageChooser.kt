package dev.ownednotes.app

import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.ownednotes.core.Notebook

/**
 * P533: chooses where pages go by a picture of the notebook's pages, for Move page and for moving chosen pages. A bar on the page's
 * edge shows which side they land on. Choosing only reports the tile's position; each caller keeps its own stale guards.
 */
internal class PageChooser(private val activity: MainActivity, title: String, private val notebook: Notebook, internal val tiles: List<Tile>, private val startAt: Int = 0, private val choose: (Int) -> Unit) {
    enum class Mark { BEFORE, AFTER, HERE }
    data class Tile(val pageId: String, val label: String, val mark: Mark, val description: String = label)
    private val c get() = activity
    private val thumbnailWidth = minOf(PageThumbnails.MAX_EDGE, ((c.dpi(170f) + 31) / 32) * 32)
    internal val grid = RecyclerView(c)
    internal val sheet = Kit.Sheet(c, title, maxWidth = 1040f, centred = true)
    val isShowing get() = sheet.dialog.isShowing
    fun dismiss() = sheet.dismiss()
    /** Chooses tile [index], as a tap on it does. */
    internal fun pick(index: Int) { dismiss(); choose(index) }
    private inner class Holder(root: LinearLayout) : RecyclerView.ViewHolder(root) {
        val art = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val bar = View(c).apply { background = rounded(Glass.accent(c.night), c.dp(2f)) }
        val name = Kit.text(c, "", Kit.Type.CAPTION).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        var request: PageThumbnails.Request? = null
        init {
            (itemView as LinearLayout).apply {
                orientation = LinearLayout.VERTICAL; setPadding(c.dpi(8f), c.dpi(8f), c.dpi(8f), c.dpi(8f))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dpi(206f)).apply { setMargins(c.dpi(4f), c.dpi(4f), c.dpi(4f), c.dpi(4f)) }
                addView(FrameLayout(c).apply {
                    addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    addView(bar, FrameLayout.LayoutParams(c.dpi(4f), ViewGroup.LayoutParams.MATCH_PARENT))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(name)
                isClickable = true; isFocusable = true
                setOnClickListener { if (bindingAdapterPosition != RecyclerView.NO_POSITION) pick(bindingAdapterPosition) }
            }
        }
        fun release() { request?.cancel(); request = null; art.setImageBitmap(null) }
        fun bind(tile: Tile) {
            release()
            name.text = tile.label; itemView.contentDescription = tile.description
            itemView.background = GradientDrawable().apply {
                setColor(if (tile.mark == Mark.HERE) Kit.accentFill(c.night) else Kit.cardFill(c.night)); cornerRadius = c.dp(14f)
                if (tile.mark == Mark.HERE) setStroke(c.dpi(2f), Glass.accent(c.night))
            }
            bar.visibility = if (tile.mark == Mark.HERE) View.GONE else View.VISIBLE
            (bar.layoutParams as FrameLayout.LayoutParams).gravity = if (tile.mark == Mark.BEFORE) Gravity.START else Gravity.END
            bar.requestLayout()
            val hit = activity.thumbnails.cached(notebook, tile.pageId, thumbnailWidth)
            if (hit != null) show(hit)
            else request = activity.thumbnails.request(notebook, tile.pageId, thumbnailWidth) { result -> request = null; result.onSuccess { show(it) } }
        }
        /** Shows the page and brings the bar in to the edge of the page as drawn, so it reads as the place the pages land. */
        private fun show(bitmap: Bitmap) {
            art.setImageBitmap(bitmap)
            art.post {
                if (art.width == 0 || art.height == 0) return@post
                val drawn = bitmap.width * minOf(art.width.toFloat() / bitmap.width, art.height.toFloat() / bitmap.height)
                val inset = (((art.width - drawn) / 2).toInt() - c.dpi(8f)).coerceAtLeast(0)
                (bar.layoutParams as FrameLayout.LayoutParams).apply { leftMargin = inset; rightMargin = inset }
                bar.requestLayout()
            }
        }
    }
    private val adapter = object : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = tiles.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(LinearLayout(c))
        override fun onBindViewHolder(h: Holder, index: Int) = h.bind(tiles[index])
        override fun onViewRecycled(h: Holder) = h.release()
    }
    fun show(): PageChooser {
        val layout = GridLayoutManager(c, if (c.resources.configuration.screenWidthDp >= 700) 4 else 3)
        grid.layoutManager = layout
        grid.setOnTouchListener { v, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent.requestDisallowInterceptTouchEvent(true); false }
        grid.adapter = adapter; grid.isNestedScrollingEnabled = false; grid.itemAnimator = null; grid.setItemViewCacheSize(4)
        val rows = (tiles.size + 3) / 4
        sheet.body.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dpi(214f) * rows.coerceIn(1, 2) + c.dpi(8f)))
        layout.scrollToPositionWithOffset(startAt, 0)
        val observer = object : DefaultLifecycleObserver { override fun onDestroy(owner: LifecycleOwner) { dismiss() } }
        activity.lifecycle.addObserver(observer)
        sheet.dialog.setOnDismissListener {
            for (i in 0 until grid.childCount) (grid.getChildViewHolder(grid.getChildAt(i)) as Holder).release()
            grid.adapter = null
            activity.lifecycle.removeObserver(observer)
        }
        sheet.show()
        return this
    }
}
