package dev.ownednotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.ownednotes.core.Layer
import dev.ownednotes.core.Notebook
import dev.ownednotes.core.Page
import dev.ownednotes.core.PaperStyle
import java.util.concurrent.Executors

/**
 * P534: chooses among a few things by a small picture of each, for layers, saved papers and folders. Pictures are drawn off the
 * main thread and thrown away with the sheet. Choosing only reports the item's position; each caller keeps its own guards.
 */
internal class PictureChooser(private val context: Context, title: String, internal val items: List<Item>, private val onDismiss: () -> Unit = {}, subtitle: String? = null, private val choose: (Int) -> Unit) {
    data class Item(val label: String, val detail: String? = null, val description: String = listOfNotNull(label, detail).joinToString(", "), val here: Boolean = false, val draw: ((Int) -> Bitmap?)? = null, val art: (() -> View)? = null)
    private val c get() = context
    // P650: two to a row on a phone with large text, so "No folder" and "0 notebooks" are not cut short.
    private val columns = minOf(items.size.coerceAtLeast(1), c.resources.configuration.screenWidthDp.let { w -> if (w >= 700) 4 else if (w < 600 && c.largeText) 2 else 3 })
    private val edge = minOf(c.dpi(190f), PageThumbnails.MAX_EDGE)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "picture-chooser").apply { isDaemon = true } }
    private val drawn = mutableListOf<Bitmap>()
    private var closed = false
    internal val sheet = Kit.Sheet(c, title, maxWidth = (columns * 240f + 44f).coerceAtLeast(480f), centred = true, subtitle = subtitle)
    /** The tiles, in the order of [items], and their pictures. */
    internal val tiles = mutableListOf<View>()
    internal val pictures = mutableListOf<ImageView>()
    val isShowing get() = sheet.dialog.isShowing
    fun dismiss() = sheet.dismiss()
    /** Chooses item [index], as a tap on its tile does. */
    internal fun pick(index: Int) { dismiss(); choose(index) }
    private fun tile(index: Int, item: Item): View {
        val art = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        pictures += art
        val shown = item.art?.invoke()?.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO } ?: art
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setPadding(c.dpi(8f), c.dpi(8f), c.dpi(8f), c.dpi(8f))
            background = GradientDrawable().apply {
                setColor(if (item.here) Kit.accentFill(c.night) else Kit.cardFill(c.night)); cornerRadius = c.dp(14f)
                if (item.here) setStroke(c.dpi(2f), Glass.accent(c.night))
            }
            addView(shown, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            // P650: with large text a word may take a second line rather than lose its end.
            val lines = if (c.largeText) 2 else 1
            addView(Kit.text(c, item.label, Kit.Type.CAPTION).apply { gravity = Gravity.CENTER; maxLines = lines; ellipsize = TextUtils.TruncateAt.END })
            item.detail?.let { addView(Kit.text(c, it, Kit.Type.CAPTION, secondary = true).apply { gravity = Gravity.CENTER; maxLines = lines; ellipsize = TextUtils.TruncateAt.END }) }
            contentDescription = item.description; isSelected = item.here; isClickable = true; isFocusable = true
            setOnClickListener { pick(index) }
            item.draw?.let { draw ->
                worker.execute {
                    if (closed) return@execute
                    val bitmap = runCatching { draw(edge) }.getOrNull() ?: return@execute
                    main.post { if (closed) bitmap.recycle() else { drawn += bitmap; art.setImageBitmap(bitmap) } }
                }
            }
        }
    }
    fun show(): PictureChooser {
        items.withIndex().chunked(columns).forEachIndexed { rowIndex, chunk ->
            sheet.add(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                for ((index, item) in chunk) {
                    val view = tile(index, item); tiles += view
                    addView(view, LinearLayout.LayoutParams(0, c.dpi((if (item.draw == null) 128f else 206f) + if (c.largeText) 56f else 0f), 1f).apply { setMargins(c.dpi(4f), c.dpi(4f), c.dpi(4f), c.dpi(4f)) })
                }
                // A short last row keeps its tiles the width of the others.
                repeat(columns - chunk.size) { addView(View(c), LinearLayout.LayoutParams(0, 0, 1f).apply { setMargins(c.dpi(4f), 0, c.dpi(4f), 0) }) }
            }, if (rowIndex == 0) 4f else 0f)
        }
        val owner = c as? LifecycleOwner
        val observer = object : DefaultLifecycleObserver { override fun onDestroy(owner: LifecycleOwner) { dismiss() } }
        owner?.lifecycle?.addObserver(observer)
        sheet.dialog.setOnDismissListener {
            closed = true; worker.shutdownNow()
            pictures.forEach { it.setImageDrawable(null) }
            drawn.forEach { it.recycle() }; drawn.clear()
            owner?.lifecycle?.removeObserver(observer)
            onDismiss()
        }
        sheet.show()
        return this
    }
}

private fun sized(pageWidth: Float, pageHeight: Float, edge: Int, color: Int, draw: (Canvas) -> Unit): Bitmap {
    val scale = edge / maxOf(pageWidth, pageHeight)
    val bitmap = Bitmap.createBitmap((pageWidth * scale).toInt().coerceAtLeast(1), (pageHeight * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    try {
        bitmap.eraseColor(color)
        val canvas = Canvas(bitmap)
        canvas.scale(bitmap.width / pageWidth, bitmap.height / pageHeight)
        canvas.clipRect(0f, 0f, pageWidth, pageHeight)
        draw(canvas)
        return bitmap
    } catch (error: Throwable) { bitmap.recycle(); throw error }
}

/** What is on [layer] alone, on bare paper and at full strength, so a faint or crowded layer can still be recognised. */
internal fun layerSample(notebook: Notebook, page: Page, layer: Layer, edge: Int): Bitmap {
    val alone = page.copy(strokes = page.strokes.filter { it.layer == layer.id }, images = page.images.filter { it.layer == layer.id }, textBoxes = page.textBoxes.filter { it.layer == layer.id },
        attachments = emptyList(), layers = listOf(layer.copy(hidden = false, opacity = 1f)))
    return sized(page.width, page.height, edge, page.paper.color.toInt()) { ExportRendering.drawObjects(it, notebook, alone, imageEdge = edge) }
}

/** [paper] on a page shaped like [page]. */
internal fun paperSample(page: Page, paper: PaperStyle, edge: Int): Bitmap =
    sized(page.width, page.height, edge, paper.color.toInt()) { PaperRenderer.draw(it, page.copy(paper = paper), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)) }
