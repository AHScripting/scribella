package dev.ownednotes.core

val Page.hasObjectLayers get() = images.any { it.layer != null } || textBoxes.any { it.layer != null }
fun Page.canSee(layer: String?): Boolean = layer == null || layers.any { it.id == layer && !it.hidden }
fun Page.layerEditable(layer: String?): Boolean = layer == null || layers.any { it.id == layer && !it.hidden && !it.locked }
fun Page.canEdit(image: ImageObject) = !image.locked && layerEditable(image.layer)
fun Page.canEdit(text: TextBox) = !text.locked && layerEditable(text.layer)
fun Page.objectOpacity(layer: String?): Float = layers.firstOrNull { it.id == layer }?.opacity ?: 1f
/** Cached immutable render views. Legacy photos/text/cards remain below all ink, unchanged. */
internal fun objectLayerSlices(page: Page): List<Page> {
    // P680: handwritten sticky notes, each with its own ink, lie over everything else on the page.
    val notes = StickyInk.notes(page)
    val base = if (notes.isEmpty() && page.strokes.none { it.note != null }) page
        else page.copy(strokes = page.strokes.filter { it.note == null }, textBoxes = page.textBoxes.filterNot { it.sticky?.ink == true })
    val inked = notes.filter { page.canSee(it.layer) }.map { note -> page.copy(
        strokes = if (note.sticky!!.collapsed) emptyList() else page.strokes.filter { it.note == note.id },
        images = emptyList(), textBoxes = listOf(note), attachments = emptyList()) }
    if (!base.hasObjectLayers) return listOf(base) + inked
    val legacy = base.copy(strokes = emptyList(), images = base.images.filter { it.layer == null },
        textBoxes = base.textBoxes.filter { it.layer == null })
    return listOf(legacy) + base.layers.filterNot { it.hidden }.map { layer -> base.copy(
        strokes = base.strokes.filter { it.layer == layer.id }, images = base.images.filter { it.layer == layer.id },
        textBoxes = base.textBoxes.filter { it.layer == layer.id }, attachments = emptyList()) } + inked
}
