package dev.ownednotes.core

/** Process-local clipboard content. Background PDFs, full pages and attachments are deliberately absent. */
data class ObjectIds(val strokes: Set<String> = emptySet(), val images: Set<String> = emptySet(), val texts: Set<String> = emptySet()) {
    val isEmpty get() = strokes.isEmpty() && images.isEmpty() && texts.isEmpty()
}
data class ObjectBundle(val strokes: List<InkStroke>, val images: List<ImageObject>, val texts: List<TextBox>, val assets: List<ImageAsset>)
fun Notebook.captureObjects(pageId: String, ids: ObjectIds): ObjectBundle {
    val page=pages.first { it.id==pageId }
    val ink=page.strokes.filter { it.id in ids.strokes }
    val photos=page.images.filter { it.id in ids.images }
    val text=page.textBoxes.filter { it.id in ids.texts }
    require(ink.size==ids.strokes.size && photos.size==ids.images.size && text.size==ids.texts.size) { "Selection changed" }
    val lockedLayers=page.layers.filter { it.locked }.map { it.id }.toSet()
    require(ink.none { it.locked || it.layer in lockedLayers } && photos.all { page.canEdit(it) } && text.all { page.canEdit(it) }) { "Unlock selected objects first" }
    return ObjectBundle(ink,photos,text,imageAssets.filter { asset -> photos.any { it.assetId==asset.id } })
}

/** Typed expansion avoids cross-type ID collisions; each group belongs to one page. */
fun Page.expandedGroups(ids: ObjectIds): ObjectIds {
    val groups=(strokes.filter { it.id in ids.strokes }.mapNotNull { it.group } +
        images.filter { it.id in ids.images }.mapNotNull { it.group } + textBoxes.filter { it.id in ids.texts }.mapNotNull { it.group }).toSet()
    return ObjectIds(ids.strokes+strokes.filter { it.group in groups }.map { it.id },
        ids.images+images.filter { it.group in groups }.map { it.id },ids.texts+textBoxes.filter { it.group in groups }.map { it.id })
}
fun Page.selectionGroup(ids: ObjectIds): String? {
    val groups=strokes.filter { it.id in ids.strokes }.map { it.group } + images.filter { it.id in ids.images }.map { it.group } + textBoxes.filter { it.id in ids.texts }.map { it.group }
    return if(groups.size>=2 && groups.first()!=null && groups.all { it==groups.first() }) groups.first() else null
}
