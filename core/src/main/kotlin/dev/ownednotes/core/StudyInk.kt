package dev.ownednotes.core

/** Allowlist projection: never pass a source Page or Notebook to a remote reader.
 * Only visible native ink, its erasure masks, layer opacity and page dimensions survive.
 * No OCR/review text, layer names, paper, background or embedded objects are copied.
 */
object StudyInk {
    fun page(source: Page): Page {
        val visible = source.drawnStrokes
        val used = visible.map { it.layer }.toSet()
        return Page(id = source.id, width = source.width, height = source.height,
            strokes = visible.map {if(it.link==null && it.audio==null)it else it.copy(link=null,audio=null)},
            layers = source.layers.filter { it.id in used && !it.hidden }
                .map { Layer(id = it.id, name = "Ink", opacity = it.opacity) })
    }

    fun svg(source: Page): String = SvgRenderer.render(page(source))
}
