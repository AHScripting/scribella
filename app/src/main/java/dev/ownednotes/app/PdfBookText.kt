package dev.ownednotes.app

import dev.ownednotes.core.Notebook
import dev.ownednotes.core.Page

/** Local embedded-text search cache; geometric selection/OCR caches keep their existing meaning. */
internal object PdfBookText {
    private val memory = RecognitionCache<Pair<String, Int>, String>(4 * 1024 * 1024) { 80L + it.length * 2L }
    private fun key(page: Page) = page.pdfBackground?.let { it.assetId to it.pageIndex }
    private fun disk(key: Pair<String, Int>) = "pdf-book-text|${key.first}|${key.second}"
    fun cached(page: Page): String? = key(page)?.let { key ->
        synchronized(memory) { memory[key] } ?: PageText.index?.get(disk(key))?.optString("text")?.also {
            synchronized(memory) { memory[key] = it }
        }
    }
    fun clearMemory() = synchronized(memory) { memory.clear() }
    fun index(app: NotesApplication, notebook: Notebook, cancelled: () -> Boolean, progress: () -> Unit = {}) {
        val sources = notebook.pages.mapNotNull(::key).distinct().groupBy { it.first }
        for ((id, keys) in sources) {
            if (cancelled()) return
            val unread = keys.filter { key ->
                if (synchronized(memory) { memory[key] } != null) false
                else {
                    val saved = PageText.index?.get(disk(key))?.optString("text")
                    if (saved != null) synchronized(memory) { memory[key] = saved }
                    saved == null
                }
            }
            if (unread.isEmpty()) continue
            val asset = notebook.assets.first { it.id == id }
            var batch = 0
            try {
                PdfTextContent.readBook(app, asset, unread.map { it.second }, cancelled) { page, value ->
                    val key = id to page
                    synchronized(memory) { memory[key] = value }
                    PageText.index?.put(disk(key), org.json.JSONObject().put("text", value))
                    if (++batch % 16 == 0 && !cancelled()) progress()
                }
            } catch (_: Exception) {
                // The normal page reader below still handles scans or a refused text page.
            }
            if (!cancelled()) progress()
        }
    }
}
