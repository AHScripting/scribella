package dev.ownednotes.app

import dev.ownednotes.core.*

/** Main-thread application of a library choice uses the same immutable target as photo insertion. */
fun NotebookRepository.insertSticker(target: ImageInsertTarget, record: StickerRecord, asset: ImageAsset): String? {
    if (!canInsertImage(target)) return null
    var inserted: String? = null
    edit { inserted = it.insertSticker(target.pageId, record, asset); true }
    return inserted
}

/** Capturing a copy is cheap; the library's original-byte IO belongs on NotesApplication.io. */
fun NotebookRepository.captureStickerPhoto(imageId: String): PhotoClipboard.Content? {
    if (readOnly || !canChangeNotebook) return null
    val page = currentPage ?: return null
    val image = page.images.firstOrNull { it.id == imageId } ?: return null
    val asset = session?.notebook?.imageAssets?.firstOrNull { it.id == image.assetId } ?: return null
    return PhotoClipboard.Content(asset, image)
}

/** Capture immutable, editable content only: no backgrounds, PDFs or attachments. */
fun NotebookRepository.captureStickerObjects(ids:ObjectIds):ObjectBundle? {
    if(readOnly || !canChangeNotebook || ids.isEmpty)return null
    val note=session?.notebook ?: return null
    val page=currentPage ?: return null
    return runCatching { note.captureObjects(page.id,ids) }.getOrNull()
}

internal fun StickerRecord.clipboardContent(assets:List<ImageAsset>):MixedClipboard.Content {
    val bundle=objects(assets)
    val bounds=android.graphics.RectF()
    var started=false
    fun add(box:android.graphics.RectF) {
        if(!started){bounds.set(box);started=true}else bounds.union(box)
    }
    bundle.images.forEach { add(ObjectMoveGuides.bounds(it.placement)) }
    bundle.texts.forEach { add(ObjectMoveGuides.bounds(it.placement)) }
    bundle.strokes.forEach { stroke ->
        val path=InkGeometry.visible(stroke)
        if(!path.isEmpty){val box=android.graphics.RectF();path.computeBounds(box,true);add(box)}
    }
    require(started) { "Sticker has no visible objects" }
    return MixedClipboard.Content(bundle,Point(bounds.centerX(),bounds.centerY()),bounds.width().coerceAtLeast(1f),bounds.height().coerceAtLeast(1f))
}
