package dev.ownednotes.app

import android.text.StaticLayout
import dev.ownednotes.core.Page
import dev.ownednotes.core.TextBox

/** Main-thread owner, serial-IO shaping. Only one task is queued: fast resize previews coalesce. */
internal class TextLayoutCache(private val app: NotesApplication, private val changed: () -> Unit) {
    private var attached = false
    private var wanted = emptySet<TextRendering.Key>()
    private var pending: TextRendering.Key? = null
    private val layouts = mutableMapOf<TextRendering.Key,StaticLayout>()
    private val failures = mutableMapOf<TextRendering.Key,String>()
    val ready get() = wanted.all { it in layouts }
    fun isReady(page: Page?) = page?.textBoxes.orEmpty().all { TextRendering.key(it) in layouts }
    fun layout(box: TextBox): StaticLayout? = layouts[TextRendering.key(box)]
    val status: String? get() = failures.entries.firstOrNull { it.key in wanted }?.let { "Text unavailable: ${it.value}" }
        ?: if (ready) null else "Laying out text…"
    fun attach() { attached = true; pump() }
    fun detach() { attached = false; wanted = emptySet(); layouts.clear(); failures.clear() }
    /** Keep native keys ready while an optional live-resize preview is shaped independently. */
    fun request(page: Page?, preview: TextBox? = null) {
        wanted = (page?.textBoxes.orEmpty().map { TextRendering.key(it) } + listOfNotNull(preview?.let { TextRendering.key(it) })).toSet()
        layouts.keys.retainAll(wanted); failures.keys.retainAll(wanted)
        pump()
    }
    private fun pump() {
        if (!attached || pending != null) return
        val key = wanted.firstOrNull { it !in layouts && it !in failures } ?: return
        pending = key
        app.io.execute {
            val result = runCatching { TextRendering.build(key) }
            app.main.post {
                pending = null
                if (attached && key in wanted) {
                    result.onSuccess { layouts[key] = it }.onFailure { failures[key] = it.message ?: "Cannot shape text" }
                    changed()
                }
                pump()
            }
        }
    }
}
