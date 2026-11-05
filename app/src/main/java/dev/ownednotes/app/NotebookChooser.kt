package dev.ownednotes.app

import android.graphics.Bitmap
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.ownednotes.core.LibraryEntry
import dev.ownednotes.core.Notebook
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * P532: chooses a notebook by its first page, for Open alongside, Copy or Move page to notebook, and Merge. Covers are read and
 * drawn on demand as tiles scroll into view. Choosing only reports the entry; each caller keeps its own stale and read-only guards.
 */
internal class NotebookChooser(private val activity: MainActivity, title: String, private val entries: List<LibraryEntry>, private val choose: (LibraryEntry) -> Unit) {
    private val c get() = activity
    private val readCover = LibraryScreen.defaultCoverLoader(c)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "notebook-chooser-covers").apply { isDaemon = true } }
    private val covers = android.util.LruCache<String, Notebook>(24)
    private val thumbnailWidth = minOf(PageThumbnails.MAX_EDGE, ((c.dpi(170f) + 31) / 32) * 32)
    private var closed = false
    internal var shown = entries; private set
    internal val search = Kit.field(c, "Find a notebook", glyph = Glyph.SEARCH).apply { contentDescription = "Find a notebook" }
    private val nothing = Kit.text(c, "No notebook has that name", Kit.Type.CAPTION, secondary = true).apply { gravity = Gravity.CENTER; visibility = View.GONE }
    internal val grid = RecyclerView(c)
    internal val sheet = Kit.Sheet(c, title, maxWidth = 1040f, centred = true)
    val isShowing get() = sheet.dialog.isShowing
    fun dismiss() = sheet.dismiss()
    /** Chooses [entry], as a tap on its tile does. */
    internal fun pick(entry: LibraryEntry) { dismiss(); choose(entry) }
    private val adapter = CoverAdapter()
    private inner class CoverAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = shown.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(LinearLayout(c))
        override fun onBindViewHolder(h: Holder, index: Int) {
            h.release()
            val entry = shown[index]
            h.entry = entry; h.name.text = entry.title; h.pages.text = pagesText(entry)
            h.itemView.contentDescription = "${entry.title}, ${pagesText(entry)}"
            covers.get(entry.id)?.let { h.showCover(it); return }
            if (closed) return
            h.task = worker.submit {
                if (closed || h.entry !== entry) return@submit
                val cover = runCatching { readCover(entry.id) }.getOrNull() ?: return@submit
                activity.runOnUiThread {
                    if (closed) return@runOnUiThread
                    covers.put(entry.id, cover)
                    if (h.entry === entry) h.showCover(cover)
                }
            }
        }
        override fun onViewRecycled(h: Holder) { h.release(); h.entry = null }
    }
    private inner class Holder(root: LinearLayout) : RecyclerView.ViewHolder(root) {
        val art = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val name = Kit.text(c, "", Kit.Type.CAPTION).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val pages = Kit.text(c, "", Kit.Type.CAPTION, secondary = true).apply { gravity = Gravity.CENTER; maxLines = 1 }
        var entry: LibraryEntry? = null
        var task: Future<*>? = null
        var request: PageThumbnails.Request? = null
        init {
            (itemView as LinearLayout).apply {
                orientation = LinearLayout.VERTICAL; setPadding(c.dpi(8f), c.dpi(8f), c.dpi(8f), c.dpi(8f))
                background = rounded(Kit.cardFill(c.night), c.dp(14f))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dpi(206f)).apply { setMargins(c.dpi(4f), c.dpi(4f), c.dpi(4f), c.dpi(4f)) }
                addView(art, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)); addView(name); addView(pages)
                isClickable = true; isFocusable = true
                setOnClickListener { entry?.let { pick(it) } }
            }
        }
        fun release() { task?.cancel(false); task = null; request?.cancel(); request = null; art.setImageBitmap(null) }
        fun showCover(cover: Notebook) {
            val page = cover.pages.firstOrNull()?.id ?: return
            activity.thumbnails.cached(cover, page, thumbnailWidth)?.let { show(it); return }
            request = activity.thumbnails.request(cover, page, thumbnailWidth) { result -> request = null; result.onSuccess { show(it) } }
        }
        private fun show(bitmap: Bitmap) { art.setImageBitmap(bitmap) }
    }
    private fun pagesText(entry: LibraryEntry) = "${entry.pageCount} ${if (entry.pageCount == 1) "page" else "pages"}"
    private fun filter(text: String) {
        val wanted = text.trim()
        shown = if (wanted.isEmpty()) entries else entries.filter { it.title.contains(wanted, ignoreCase = true) }
        nothing.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }
    fun show(): NotebookChooser {
        if (entries.size > SEARCH_FROM) {
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { filter(s?.toString().orEmpty()) }
            })
            sheet.add(search)
        }
        sheet.add(nothing, 8f)
        grid.layoutManager = GridLayoutManager(c, if (c.resources.configuration.screenWidthDp >= 700) 4 else 3)
        grid.setOnTouchListener { v, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent.requestDisallowInterceptTouchEvent(true); false }
        grid.adapter = adapter; grid.isNestedScrollingEnabled = false; grid.itemAnimator = null; grid.setItemViewCacheSize(4)
        val rows = (entries.size + 3) / 4
        sheet.body.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dpi(214f) * rows.coerceIn(1, 2) + c.dpi(8f)).apply { topMargin = c.dpi(10f) })
        val observer = object : DefaultLifecycleObserver { override fun onDestroy(owner: LifecycleOwner) { dismiss() } }
        activity.lifecycle.addObserver(observer)
        sheet.dialog.setOnDismissListener { closed = true; worker.shutdownNow(); covers.evictAll(); activity.lifecycle.removeObserver(observer) }
        sheet.show()
        return this
    }
    private companion object { const val SEARCH_FROM = 8 }
}
