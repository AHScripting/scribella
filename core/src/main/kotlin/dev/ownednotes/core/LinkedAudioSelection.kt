package dev.ownednotes.core

import java.util.UUID

/** Process-local copy payload; original bytes remain in the caller-owned asset workspace. */
data class LinkedAudioSelection(val clips:List<Clip>) {
    data class Clip(val card:FileAttachment,val asset:AttachmentAsset)
    internal data class Plan(val notebook:Notebook,val strokes:List<InkStroke>)
    internal fun prepare(target:Notebook,pageId:String,strokes:List<InkStroke>):Plan {
        val page=target.pages.first {it.id==pageId}
        val cards=(target.pages.flatMap {it.attachments}+page.attachments).filter {it.mimeType.startsWith("audio/")}.associateBy {it.assetId}.toMutableMap()
        val assets=target.attachmentAssets.associateBy {it.id}.toMutableMap()
        val additions=mutableListOf<FileAttachment>();val remap=mutableMapOf<String,String>()
        val needed=strokes.mapNotNull {it.audio?.attachmentId}.toSet()
        clips.filter {it.card.id in needed}.forEach {clip->
            require(clip.card.assetId==clip.asset.id && clip.card.mimeType.startsWith("audio/")) {"Invalid linked recording"}
            val existing=assets[clip.asset.id]
            require(existing==null || existing==clip.asset) {"Conflicting linked recording"}
            assets[clip.asset.id]=clip.asset
            val card=cards[clip.asset.id] ?: clip.card.copy(id=UUID.randomUUID().toString(),locked=false,
                placement=FileAttachment.cardPlacement(page.width,page.height,page.attachments.size+additions.size)).also {additions+=it;cards[it.assetId]=it}
            remap[clip.card.id]=card.id
        }
        // Never let a missing source recording accidentally bind an unrelated destination card ID.
        val rewritten=strokes.map {s->s.copy(audio=s.audio?.let {a->remap[a.attachmentId]?.let {a.copy(attachmentId=it)}})}
        return Plan(target.copy(pages=target.pages.map {if(it.id==pageId)it.copy(attachments=it.attachments+additions)else it},attachmentAssets=assets.values.toList()),rewritten)
    }
    companion object {
        fun capture(note:Notebook,strokes:List<InkStroke>):LinkedAudioSelection {
            val ids=strokes.mapNotNull {it.audio?.attachmentId}.toSet()
            val assets=note.attachmentAssets.associateBy {it.id}
            return LinkedAudioSelection(note.pages.flatMap {it.attachments}.filter {it.id in ids && it.mimeType.startsWith("audio/")}.mapNotNull {card->assets[card.assetId]?.let {Clip(card,it)}})
        }
    }
}
