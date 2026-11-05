package dev.ownednotes.app

import dev.ownednotes.core.*

/** Internal selection keys keep per-type ID namespaces separate. Never persisted or sent to the system clipboard. */
internal fun imageKey(id: String)="photo:$id"
internal fun textKey(id: String)="text:$id"
internal fun ObjectIds.selectionKeys()=strokes+images.map(::imageKey)+texts.map(::textKey)
internal fun Page.objectIds(keys: Set<String>)=ObjectIds(strokes.filter { it.id in keys }.map { it.id }.toSet(),
    images.filter { imageKey(it.id) in keys }.map { it.id }.toSet(),textBoxes.filter { textKey(it.id) in keys }.map { it.id }.toSet())
class MixedClipboard {
    data class Content(val bundle: ObjectBundle,val centre: Point,val width: Float,val height: Float,val audio:LinkedAudioSelection?=null)
    var content: Content?=null;private set
    fun capture(value: Content) { content=value }
    fun clear() { content=null }
}
