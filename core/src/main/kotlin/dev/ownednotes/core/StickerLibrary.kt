package dev.ownednotes.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** One application-owned instance serializes access. Corrupt storage is never treated as empty. */
class StickerLibrary(private val directory: File) {
    private val destination get() = File(directory, "library.inkstickers")

    @Synchronized fun read(): StickerPack {
        val file = destination
        if (!file.exists()) return StickerPack()
        return file.inputStream().use { StickerArchive.read(it) }
    }

    /** Save a reusable copy without modifying the notebook object or its original bytes. */
    @Synchronized fun addPhoto(name: String, image: ImageObject, asset: ImageAsset, collection: String = ""): StickerRecord {
        require(image.assetId == asset.id) { "Sticker image and original differ" }
        val record = StickerRecord.fromPhoto(name, image, collection)
        val current = read()
        commit(StickerPack(current.stickers + record, (current.imageAssets + asset).distinctBy { it.id }))
        return record
    }

    @Synchronized fun addObjects(name:String,bundle:ObjectBundle,collection:String=""):StickerRecord {
        val record=StickerRecord.fromObjects(name,bundle,collection)
        val current=read()
        commit(StickerPack(current.stickers+record,(current.imageAssets+bundle.assets).distinctBy { it.id }))
        return record
    }

    @Synchronized fun rename(id: String, name: String, collection: String): Boolean {
        val current = read()
        val old = current.stickers.firstOrNull { it.id == id } ?: return false
        val replacement = old.copy(name = name, collection = collection)
        if (replacement != old) commit(current.copy(stickers = current.stickers.map { if (it.id == id) replacement else it }))
        return true
    }

    @Synchronized fun remove(id: String): Boolean {
        val current = read()
        val remaining = current.stickers.filterNot { it.id == id }
        if (remaining.size == current.stickers.size) return false
        val used = remaining.flatMap { it.photos }.map { it.assetId }.toSet()
        commit(StickerPack(remaining, current.imageAssets.filter { it.id in used }))
        return true
    }

    /** Merge preserves every existing entry. Imported entries always receive independent identities. */
    @Synchronized fun merge(incoming: StickerPack): Int {
        val snapshot = incoming.copy(stickers = incoming.stickers.toList(), imageAssets = incoming.imageAssets.toList())
        snapshot.validate()
        val current = read()
        if (snapshot.stickers.isEmpty()) return 0
        val copies = snapshot.stickers.map { it.copy(id = UUID.randomUUID().toString(), image = it.image?.copy(id = UUID.randomUUID().toString())) }
        commit(StickerPack(current.stickers + copies, (current.imageAssets + snapshot.imageAssets).distinctBy { it.id }))
        return copies.size
    }

    private fun commit(pack: StickerPack) {
        pack.validate()
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create sticker library directory")
        val pending = File(directory, ".stickers-${UUID.randomUUID()}.pending")
        try {
            FileOutputStream(pending).use { stream -> StickerArchive.write(pack, stream); stream.fd.sync() }
            // Match notebook storage: never replace with a non-atomic fallback.
            Files.move(pending.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(pending.toPath())
        }
    }
}
