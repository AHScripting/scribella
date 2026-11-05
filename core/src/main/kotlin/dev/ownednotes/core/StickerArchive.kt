package dev.ownednotes.core

import java.io.*
import java.util.zip.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** `.inkstickers`: a bounded, portable, original-byte library, separate from notebook schema versions. */
object StickerArchive {
    const val MAX_MANIFEST_BYTES=1024*1024
    @Serializable private data class ImageRef(val id:String,val file:String)
    @Serializable private data class Manifest(val format:String="owned-notes-stickers",val schemaVersion:Int=1,
        val stickers:List<StickerRecord>,val images:List<ImageRef>)
    private val json=Json { prettyPrint=true;encodeDefaults=true }
    fun write(pack:StickerPack,destination:OutputStream) {
        val snapshot=pack.copy(stickers=pack.stickers.toList(),imageAssets=pack.imageAssets.toList())
        snapshot.validate()
        val refs=snapshot.imageAssets.map { ImageRef(it.id,"images/${it.id}.${it.extension}") }
        val manifest=json.encodeToString(Manifest(schemaVersion=when {snapshot.stickers.any {r->r.strokes.any {it.link!=null} || r.texts.any {it.link!=null}}->4;snapshot.stickers.any { r -> r.texts.any { it.sticky!=null } }->3;snapshot.stickers.any { !it.photoOnly }->2;else->1},stickers=snapshot.stickers,images=refs)).toByteArray(Charsets.UTF_8)
        require(manifest.size<=MAX_MANIFEST_BYTES) { "Sticker manifest exceeds byte limit" }
        val nonClosing=object:FilterOutputStream(destination) {
            override fun write(bytes:ByteArray,offset:Int,length:Int) { out.write(bytes,offset,length) }
            override fun close() { flush() }
        }
        ZipOutputStream(nonClosing).use { zip ->
            fun start(name:String) { zip.putNextEntry(ZipEntry(name).apply { time=0L }) }
            start("manifest.json");zip.write(manifest);zip.closeEntry()
            snapshot.imageAssets.zip(refs).forEach { (asset,ref)->start(ref.file);asset.writeTo(zip);zip.closeEntry() }
        }
    }
    fun read(source:InputStream):StickerPack {
        val entries=linkedMapOf<String,ByteArray>();var total=0L
        ZipInputStream(source).use { zip ->
            var entry=zip.nextEntry
            while(entry!=null) {
                val name=entry.name
                require(!entry.isDirectory && (name=="manifest.json" || name.matches(Regex("images/[a-f0-9]{64}\\.(png|jpg)")))) { "Unsupported sticker archive path" }
                require(name !in entries && entries.size<StickerPack.MAX_STICKERS+1) { "Duplicate or too many sticker entries" }
                val limit=if(name=="manifest.json")MAX_MANIFEST_BYTES else ImageAsset.MAX_BYTES
                val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                var n=zip.read(buffer)
                while(n!=-1) {
                    total+=n
                    require(output.size().toLong()+n<=limit && total<=StickerPack.MAX_IMAGE_BYTES+MAX_MANIFEST_BYTES) { "Sticker archive exceeds byte limits" }
                    output.write(buffer,0,n);n=zip.read(buffer)
                }
                entries[name]=output.toByteArray();zip.closeEntry();entry=zip.nextEntry
            }
        }
        val raw=entries["manifest.json"] ?: error("Missing sticker manifest")
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
        val tree=json.parseToJsonElement(text)
        require(tree.jsonObject["format"]?.jsonPrimitive?.content=="owned-notes-stickers" &&
            (tree.jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 0) in 1..4) { "Unsupported sticker format/version" }
        val manifest=json.decodeFromJsonElement<Manifest>(tree)
        require(manifest.schemaVersion>=2 || manifest.stickers.all { it.photoOnly }) { "Mixed stickers require sticker schema 2" }
        require(manifest.schemaVersion>=4 || manifest.stickers.all {r->r.strokes.none {it.link!=null} && r.texts.none {it.link!=null}}) { "Notebook links require sticker schema 4" }
        require(manifest.schemaVersion>=3 || manifest.stickers.all { r -> r.texts.none { it.sticky!=null } }) { "Sticky notes require sticker schema 3" }
        require(manifest.stickers.size<=StickerPack.MAX_STICKERS && manifest.images.size<=StickerPack.MAX_STICKERS) { "Too many stickers or images" }
        require(manifest.images.map { it.file }.distinct().size==manifest.images.size &&
            entries.keys==setOf("manifest.json")+manifest.images.map { it.file }) { "Missing, duplicate or unreferenced sticker entries" }
        val assets=manifest.images.map { ref ->
            require(ref.id.matches(Regex("[a-f0-9]{64}")) && ref.file.matches(Regex("images/${ref.id}\\.(png|jpg)"))) { "Invalid sticker image path or hash" }
            val asset=ImageAsset.fromBytes(entries.remove(ref.file)!!)
            require(asset.id==ref.id && ref.file=="images/${asset.id}.${asset.extension}") { "Sticker image checksum or type differs" }
            asset
        }
        return StickerPack(manifest.stickers,assets).also { it.validate() }
    }
}
