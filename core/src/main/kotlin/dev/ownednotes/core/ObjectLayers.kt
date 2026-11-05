package dev.ownednotes.core

val Page.hasObjectLayers get() = images.any { it.layer != null } || textBoxes.any { it.layer != null }
fun Page.canSee(layer: String?): Boolean = layer == null || layers.any { it.id == layer && !it.hidden }
fun Page.layerEditable(layer: String?): Boolean = layer == null || layers.any { it.id == layer && !it.hidden && !it.locked }
fun Page.canEdit(image: ImageObject) = !image.locked && layerEditable(image.layer)
fun Page.canEdit(text: TextBox) = !text.locked && layerEditable(text.layer)
fun Page.objectOpacity(layer: String?): Float = layers.firstOrNull { it.id == layer }?.opacity ?: 1f
/** Cached immutable render views. Legacy photos/text/cards remain below all ink, unchanged. */
internal fun objectLayerSlices(page: Page): List<Page> {
    if (!page.hasObjectLayers) return listOf(page)
    val legacy = page.copy(strokes = emptyList(), images = page.images.filter { it.layer == null },
        textBoxes = page.textBoxes.filter { it.layer == null })
    return listOf(legacy) + page.layers.filterNot { it.hidden }.map { layer -> page.copy(
        strokes = page.strokes.filter { it.layer == layer.id }, images = page.images.filter { it.layer == layer.id },
        textBoxes = page.textBoxes.filter { it.layer == layer.id }, attachments = emptyList()) }
}
