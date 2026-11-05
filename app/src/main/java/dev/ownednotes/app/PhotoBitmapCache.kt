package dev.ownednotes.app

import android.graphics.Bitmap
import dev.ownednotes.core.Notebook
import dev.ownednotes.core.Page

/** Main-thread live-page cache: at most four raw 2048-edge images, decoded on the serial IO executor. */
internal class PhotoBitmapCache(private val app: NotesApplication, private val changed: () -> Unit) {
    private var attached = false
    private var wanted = emptySet<String>()
    private val bitmaps = mutableMapOf<String, Bitmap>()
    private val pending = mutableSetOf<String>()
    private val failures = mutableMapOf<String, String>()

    val ready get() = wanted.all { it in bitmaps }
    fun isReady(page: Page?) = page?.images.orEmpty().all { it.assetId in bitmaps }
    val status: String? get() = failures.entries.firstOrNull { it.key in wanted }?.let { "Image unavailable: ${it.value}" }
        ?: if (ready) null else "Rendering images…"
    fun bitmap(assetId: String): Bitmap? = bitmaps[assetId]

    fun attach() { attached = true }
    fun detach() {
        attached = false; wanted = emptySet(); bitmaps.clear(); failures.clear()
        // Hardware display lists may still reference these live bitmaps: let GC reclaim them, do not recycle.
    }
    fun request(note: Notebook?, page: Page?) {
        wanted = page?.images?.map { it.assetId }?.toSet().orEmpty()
        bitmaps.keys.retainAll(wanted); failures.keys.retainAll(wanted)
        if (!attached || note == null) return
        wanted.filter { it !in bitmaps && it !in pending && it !in failures }.forEach { id ->
            val asset = note.imageAssets.firstOrNull { it.id == id }
            if (asset == null) { failures[id] = "Image object asset missing"; return@forEach }
            pending.add(id)
            app.io.execute {
                val result = runCatching { ImageSupport.decodeRaw(asset) }
                app.main.post {
                    pending.remove(id)
                    if (attached && id in wanted) {
                        result.onSuccess { bitmaps[id] = it }.onFailure { failures[id] = it.message ?: "Cannot decode image" }
                        changed()
                    } else result.getOrNull()?.recycle()
                }
            }
        }
    }
}
