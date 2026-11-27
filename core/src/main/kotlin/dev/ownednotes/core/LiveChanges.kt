package dev.ownednotes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import java.util.IdentityHashMap

/**
 * Live sync (docs/LIVE-SYNC-PLAN.md): what changed in one notebook since the state this device last agreed on, as a small
 * [ChangeSet] of pages and items by id, and how to apply one from another device. Hashes are NotebookMerge's, so a page
 * unchanged since the last diff (the same object: edits copy only the pages they touch) is not hashed again.
 */
object LiveChanges {
    @Serializable data class ItemPut(val page: String, val key: String, val value: JsonElement)
    @Serializable data class ItemRef(val page: String, val key: String)
    /** P719: a photo sent through the sync server: its checksum ([id], as ImageAsset.id), and its sealed pieces there. */
    @Serializable data class PhotoRef(val id: String, val blob: String, val size: Int, val parts: Int)

    /**
     * One device's changes to one notebook. [pages] are page settings (a page with no items) for new or changed pages;
     * [itemOrders] the whole item order of a page whose order changed other than by adding at the end. [folder] means
     * "fetch this notebook through folder sync" (new PDFs, photos or files, which are not sent live).
     *
     * P719: [photos] are the new photos of a set sent live (folder false); its pictures are fetched from the sync server
     * first. [covers] is the checksum of the folder set sent for the same change (for apps before P719 and as the
     * fallback): a device that took in the photos skips that fetch. Both are null otherwise and then not written at all,
     * so apps before P719, which refuse unknown fields, still read every other set.
     */
    @Serializable data class ChangeSet(
        val version: Int = 1, val notebook: String, val device: String,
        val title: String? = null, val order: List<String>? = null,
        val pages: List<JsonElement> = emptyList(), val removedPages: List<String> = emptyList(),
        val items: List<ItemPut> = emptyList(), val removedItems: List<ItemRef> = emptyList(),
        val itemOrders: Map<String, List<String>> = emptyMap(), val folder: Boolean = false,
        val photos: List<PhotoRef>? = null, val covers: String? = null,
    ) {
        val empty get() = title == null && order == null && pages.isEmpty() && removedPages.isEmpty() && items.isEmpty() &&
            removedItems.isEmpty() && itemOrders.isEmpty() && !folder
    }

    /** The state of one notebook this device last agreed on: ids and hashes only, kept on the device. */
    @Serializable data class Base(val version: Int = 1, val notebook: String, val title: String, val pages: List<NotebookMerge.PageBase>, val assets: Set<String>)

    private val json get() = NotebookMerge.json
    fun encode(set: ChangeSet): ByteArray = json.encodeToString(set).toByteArray(Charsets.UTF_8)
    fun decode(bytes: ByteArray): ChangeSet = json.decodeFromString<ChangeSet>(bytes.toString(Charsets.UTF_8)).also { require(it.version == 1) { "Unknown change version" } }
    fun encodeBase(base: Base): ByteArray = json.encodeToString(base).toByteArray(Charsets.UTF_8)
    fun decodeBase(bytes: ByteArray): Base = json.decodeFromString<Base>(bytes.toString(Charsets.UTF_8)).also { require(it.version == 1) { "Unknown live base version" } }

    fun assetIds(notebook: Notebook): Set<String> =
        (notebook.assets.map { "p:" + it.id } + notebook.imageAssets.map { "i:" + it.id } + notebook.attachmentAssets.map { "a:" + it.id }).toSet()

    private fun whole(settings: String, items: Map<String, String>) =
        NotebookMerge.hash(settings + items.entries.sortedBy { it.key }.joinToString("") { "${it.key}=${it.value};" })

    private fun itemsOf(page: Page): Map<String, Any> = LinkedHashMap<String, Any>().apply {
        page.strokes.forEach { put("s:${it.id}", it) }
        page.images.forEach { put("i:${it.id}", it) }
        page.textBoxes.forEach { put("t:${it.id}", it) }
        page.attachments.forEach { put("a:${it.id}", it) }
        page.reviewedInk.forEach { put("r:${it.id}", it) }
    }
    private fun prefix(key: String) = key.substringBefore(':')

    /**
     * Follows one notebook: [diff] turns each saved snapshot into a change set and makes it the new base; [takeIn] adds
     * another device's change set to the base so it is not sent back. Not thread-safe: the app calls it from one queue.
     */
    class Tracker(base: Base) {
        var base: Base = base; private set
        private var cache = IdentityHashMap<Page, NotebookMerge.PageBase>()

        fun diff(notebook: Notebook, device: String): ChangeSet {
            require(notebook.id == base.notebook) { "Another notebook" }
            val now = notebook.pages.map { page -> page to (cache[page] ?: NotebookMerge.pageBase(page)) }
            val before = base.pages.associateBy { it.id }
            val ids = notebook.pages.map { it.id }
            val assets = assetIds(notebook)
            val pages = ArrayList<JsonElement>(); val items = ArrayList<ItemPut>(); val removed = ArrayList<ItemRef>()
            val orders = LinkedHashMap<String, List<String>>()
            for ((page, pb) in now) {
                val old = before[page.id]
                if (old != null && old.whole == pb.whole) {
                    if (old.items.keys.toList() != pb.items.keys.toList()) orders[page.id] = pb.items.keys.toList()
                    continue
                }
                if (old == null || old.settings != pb.settings) pages += json.encodeToJsonElement(Page.serializer(), NotebookMerge.settingsOf(page))
                val objects by lazy { itemsOf(page) }
                for ((key, hash) in pb.items) if (old?.items?.get(key) != hash)
                    items += ItemPut(page.id, key, json.encodeToJsonElement(NotebookMerge.serializerFor(prefix(key)), objects.getValue(key)))
                old?.items?.keys?.forEach { if (it !in pb.items) removed += ItemRef(page.id, it) }
                val kept = old?.items?.keys?.filter { it in pb.items }.orEmpty()
                val expected = kept + pb.items.keys.filter { old?.items?.containsKey(it) != true }
                if (old != null && pb.items.keys.toList() != expected) orders[page.id] = pb.items.keys.toList()
            }
            val set = ChangeSet(notebook = notebook.id, device = device,
                title = notebook.title.takeIf { it != base.title },
                order = ids.takeIf { it != base.pages.map { p -> p.id } },
                pages = pages, removedPages = base.pages.map { it.id }.filter { it !in ids.toSet() },
                items = items, removedItems = removed, itemOrders = orders,
                folder = !base.assets.containsAll(assets))
            base = Base(notebook = notebook.id, title = notebook.title, pages = now.map { it.second }, assets = assets)
            cache = IdentityHashMap<Page, NotebookMerge.PageBase>().apply { now.forEach { (p, pb) -> put(p, pb) } }
            return set
        }

        /** P719: photos written into this notebook's file from another device are not new files for the next [diff]. */
        fun knowPhotos(ids: Collection<String>) { base = base.copy(assets = base.assets + ids.map { "i:$it" }) }

        /** Adds another device's [set] to the base, at the level of hashes, so the next [diff] does not send it back. */
        fun takeIn(set: ChangeSet) {
            if (set.folder) return
            val pages = LinkedHashMap<String, Pair<String, LinkedHashMap<String, String>>>()
            base.pages.filter { it.id !in set.removedPages }.forEach { pages[it.id] = it.settings to LinkedHashMap(it.items) }
            for (element in set.pages) {
                val page = json.decodeFromJsonElement(Page.serializer(), element)
                val settings = NotebookMerge.settingsHash(page)
                pages[page.id] = settings to (pages[page.id]?.second ?: LinkedHashMap())
            }
            set.removedItems.forEach { pages[it.page]?.second?.remove(it.key) }
            for (put in set.items) {
                val items = pages[put.page]?.second ?: continue
                val value = json.decodeFromJsonElement(NotebookMerge.serializerFor(prefix(put.key)), put.value)
                items[put.key] = NotebookMerge.hash(json.encodeToString(NotebookMerge.serializerFor(prefix(put.key)), value))
            }
            for ((id, order) in set.itemOrders) pages[id]?.let { (s, items) -> pages[id] = s to reorder(items, order) }
            val ids = arrange(pages.keys.toList(), set.order)
            // P719: photos taken in live are known here too, so the next save does not send them back through folder sync.
            base = base.copy(title = set.title ?: base.title, pages = ids.map { id -> pages.getValue(id).let { (s, items) -> NotebookMerge.PageBase(id, whole(s, items), s, items) } },
                assets = base.assets + set.photos.orEmpty().map { "i:" + it.id })
            cache = IdentityHashMap()
        }
    }

    /** A new base from a notebook as it is (when a device starts following it). */
    fun baseOf(notebook: Notebook) = Base(notebook = notebook.id, title = notebook.title, pages = notebook.pages.map(NotebookMerge::pageBase), assets = assetIds(notebook))

    private fun <V> reorder(items: LinkedHashMap<String, V>, order: List<String>): LinkedHashMap<String, V> =
        LinkedHashMap<String, V>().apply { order.forEach { k -> items[k]?.let { put(k, it) } }; items.forEach { (k, v) -> if (k !in this) put(k, v) } }

    /** [current] in [order] where it names them; the others keep their place after the page before them. */
    private fun arrange(current: List<String>, order: List<String>?): List<String> {
        if (order == null) return current
        val known = order.filter { it in current.toSet() }.toMutableList()
        current.forEachIndexed { i, id ->
            if (id in known) return@forEachIndexed
            val after = current.take(i).lastOrNull { it in known }
            known.add(if (after == null) 0 else known.indexOf(after) + 1, id)
        }
        return known
    }

    /**
     * Applies another device's [set] to [notebook]. Pages and items this device does not have are skipped; strokes keep
     * pointing at layers and notes that exist. The result is validated (throws when it is not a valid notebook, for example
     * a page needing a PDF this device does not have: the caller then fetches the notebook through folder sync).
     * P719: [photos] are the set's new pictures, fetched from the sync server (one already here is kept as it is).
     */
    fun apply(notebook: Notebook, set: ChangeSet, photos: List<ImageAsset> = emptyList()): Notebook = applyRun(notebook, listOf(set), photos)

    /**
     * P728: [sets] in order, each taken in as [apply] does, and the result validated once at the end instead of after
     * every set (on the Pixel a backlog of the tablet's changes to the big German notebook was checked set by set).
     * Pages that are still the notebook's own (no set changed them) were valid already, so their strokes are not checked again.
     */
    fun applyRun(notebook: Notebook, sets: List<ChangeSet>, photos: List<ImageAsset> = emptyList()): Notebook {
        require(sets.isNotEmpty()) { "No changes" }
        val next = sets.fold(notebook) { taken, set -> change(taken, set, photos) }
        val kept = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Page, Boolean>()).apply { addAll(notebook.pages) }
        next.validate({ false }) { it in kept }
        return next
    }

    /** One set, not yet validated: [applyRun] validates the result. */
    private fun change(notebook: Notebook, set: ChangeSet, photos: List<ImageAsset>): Notebook {
        require(notebook.id == set.notebook) { "Another notebook" }
        require(!set.folder) { "This change comes through folder sync" }
        val pages = LinkedHashMap<String, Page>()
        notebook.pages.filter { it.id !in set.removedPages }.forEach { pages[it.id] = it }
        val touched = HashSet<String>()
        for (element in set.pages) {
            val settings = json.decodeFromJsonElement(Page.serializer(), element)
            val old = pages[settings.id]
            pages[settings.id] = old?.let { settings.copy(strokes = it.strokes, images = it.images, textBoxes = it.textBoxes, attachments = it.attachments, reviewedInk = it.reviewedInk) } ?: settings
            touched += settings.id
        }
        val byPage = (set.items.map { it.page } + set.removedItems.map { it.page } + set.itemOrders.keys).toSet()
        for (id in byPage) {
            val page = pages[id] ?: continue
            var items = LinkedHashMap(itemsOf(page))
            set.removedItems.filter { it.page == id }.forEach { items.remove(it.key) }
            set.items.filter { it.page == id }.forEach { items[it.key] = json.decodeFromJsonElement(NotebookMerge.serializerFor(prefix(it.key)), it.value) }
            set.itemOrders[id]?.let { items = reorder(items, it) }
            @Suppress("UNCHECKED_CAST") fun <T> of(p: String) = items.filterKeys { prefix(it) == p }.values.toList() as List<T>
            pages[id] = page.copy(strokes = of("s"), images = of("i"), textBoxes = of("t"), attachments = of("a"), reviewedInk = of("r"))
            touched += id
        }
        for (id in touched) pages[id] = tidy(pages.getValue(id))
        val ids = arrange(pages.keys.toList(), set.order)
        require(ids.isNotEmpty()) { "A notebook keeps at least one page" }
        val list = ids.map { pages.getValue(it) }
        // Files that no page uses any more go, as an edit on this device would drop them.
        val pdfs = list.mapNotNull { it.pdfBackground?.assetId }.toSet()
        val pictures = list.flatMap { it.imageAssetIds }.toSet()
        val files = list.flatMap { it.attachmentAssetIds }.toSet()
        val here = notebook.imageAssets.map { it.id }.toSet()
        val images = notebook.imageAssets + photos.filter { it.id !in here }.distinctBy { it.id }
        val next = notebook.copy(title = set.title ?: notebook.title, pages = list, assets = notebook.assets.filter { it.id in pdfs },
            imageAssets = images.filter { it.id in pictures }, attachmentAssets = notebook.attachmentAssets.filter { it.id in files })
        return next
    }

    /** As NotebookMerge does: strokes on layers that exist, bottom first; notes and reviews that still have their ink. */
    private fun tidy(page: Page): Page {
        val layers = page.layers.map { it.id }
        val notes = page.textBoxes.map { it.id }.toSet()
        var strokes = page.strokes.map { s ->
            val layer = if (layers.isEmpty()) null else s.layer?.takeIf { it in layers } ?: layers.first()
            val note = s.note?.takeIf { it in notes }
            if (layer == s.layer && note == s.note) s else s.copy(layer = layer, note = note)
        }
        if (layers.isNotEmpty()) strokes = strokes.sortedBy { layers.indexOf(it.layer) }
        val strokeIds = strokes.map { it.id }.toSet()
        return page.copy(strokes = strokes, reviewedInk = page.reviewedInk.filter { r -> r.strokeIds.all { it in strokeIds } })
    }
}
