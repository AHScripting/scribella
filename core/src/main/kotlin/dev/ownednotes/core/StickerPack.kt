package dev.ownednotes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import java.util.UUID

/** Reusable objects; labels are never filesystem paths. Insertion creates fresh identities. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class StickerRecord(
    val name:String,
    val image:ImageObject? = null,
    val collection:String="",
    val id:String=UUID.randomUUID().toString(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val strokes:List<InkStroke> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val texts:List<TextBox> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val additionalImages:List<ImageObject> = emptyList(),
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Invalid sticker ID" }
        require(photos.isNotEmpty() || strokes.isNotEmpty() || texts.isNotEmpty()) { "Sticker is empty" }
        photos.forEach { image -> require(image.id.matches(Regex("[a-zA-Z0-9-]{1,80}")) && image.assetId.matches(Regex("[a-f0-9]{64}")) && image.orientation in 1..8) { "Invalid sticker image reference" } }
        label(name,100,false);label(collection,60,true)
    }
    val photos get() = listOfNotNull(image) + additionalImages
    val photoOnly get() = image != null && strokes.isEmpty() && texts.isEmpty() && additionalImages.isEmpty()
    fun objects(assets:List<ImageAsset>) = ObjectBundle(strokes,photos,texts,assets.filter { a -> photos.any { it.assetId==a.id } })
    companion object {
        private fun label(value:String,max:Int,empty:Boolean) {
            require(value==value.trim() && value.length<=max && (empty || value.isNotEmpty()) &&
                value.codePoints().allMatch { it>=0x20 && it !in 0xd800..0xdfff && it!=0xfffe && it!=0xffff }) { "Invalid sticker name or collection" }
        }
        fun fromObjects(name:String,bundle:ObjectBundle,collection:String=""):StickerRecord {
            if(bundle.strokes.isEmpty() && bundle.texts.isEmpty() && bundle.images.size==1) return fromPhoto(name,bundle.images.single(),collection)
            val count=bundle.strokes.size+bundle.images.size+bundle.texts.size
            val group=if(count>1) UUID.randomUUID().toString() else null
            val photos=bundle.images.map { it.copy(id=UUID.randomUUID().toString(),locked=false,layer=null,group=group) }
            return StickerRecord(name,photos.firstOrNull(),collection,strokes=bundle.strokes.map { it.copy(id=UUID.randomUUID().toString(),locked=false,layer=null,group=group,audio=null) },
                texts=bundle.texts.map { it.copy(id=UUID.randomUUID().toString(),locked=false,layer=null,group=group) },additionalImages=photos.drop(1))
        }
        fun fromPhoto(name:String,image:ImageObject,collection:String="") = StickerRecord(name,
            image.copy(id=UUID.randomUUID().toString(),locked=false,layer=null,group=null,placement=image.placement.copy(x=0f,y=0f)),collection)
    }
}

/** A portable library snapshot. Shared originals are stored exactly once, independently of notebook budgets. */
data class StickerPack(val stickers:List<StickerRecord> = emptyList(),val imageAssets:List<ImageAsset> = emptyList()) {
    fun validate() {
        require(stickers.size<=MAX_STICKERS && stickers.map { it.id }.distinct().size==stickers.size) { "Too many or duplicate stickers" }
        require(imageAssets.size<=MAX_STICKERS && imageAssets.map { it.id }.distinct().size==imageAssets.size) { "Too many or duplicate sticker images" }
        require(imageAssets.sumOf { it.size.toLong() }<=MAX_IMAGE_BYTES) { "Sticker library exceeds image byte limit" }
        require(stickers.flatMap { it.photos }.map { it.assetId }.toSet()==imageAssets.map { it.id }.toSet()) { "Sticker images are missing or unreferenced" }
        stickers.forEach { record ->
            val objects=record.objects(imageAssets)
            Notebook(pages=listOf(Page(strokes=objects.strokes.map { it.copy(layer=null,group=null) },images=objects.images.map { it.copy(layer=null,group=null) },textBoxes=objects.texts.map { it.copy(layer=null,group=null) })),imageAssets=objects.assets).validate()
        }
    }
    companion object {
        const val MAX_STICKERS=256
        const val MAX_IMAGE_BYTES=64L*1024*1024
    }
}
