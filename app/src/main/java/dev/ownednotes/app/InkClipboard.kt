package dev.ownednotes.app

import dev.ownednotes.core.InkStroke
import dev.ownednotes.core.Point
import dev.ownednotes.core.LinkedAudioSelection

/** Application-owned, process-lifetime clipboard. No disk or Android system clipboard payload. */
class InkClipboard {
    data class Content(val strokes: List<InkStroke>, val centre: Point,val audio:LinkedAudioSelection?=null)
    var content: Content? = null; private set
    val hasInk get() = content != null
    fun capture(strokes: List<InkStroke>, centre: Point, audio:LinkedAudioSelection?=null) {
        require(strokes.isNotEmpty() && centre.x.isFinite() && centre.y.isFinite())
        content = Content(strokes.toList(), centre,audio)
    }
    fun clear() { content = null }
}

/** B35 (P198): top-left of a [w]×[h] item pasted at [at], kept on the page; centred on the page when [at] is null. */
internal fun pasteOrigin(at: Point?, w: Float, h: Float, pageWidth: Float, pageHeight: Float): Point =
    if (at == null) Point((pageWidth - w) / 2, (pageHeight - h) / 2)
    else Point((at.x - w / 2).coerceIn(0f, maxOf(0f, pageWidth - w)), (at.y - h / 2).coerceIn(0f, maxOf(0f, pageHeight - h)))
