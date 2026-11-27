package dev.ownednotes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

/**
 * P699 (docs/SYNC-MERGE-PLAN.md): combines two devices' changes to one notebook.
 *
 * [Base] is a small record of the version both devices last agreed on: the title, and per page its id, a hash of its
 * settings (size, paper, background, bookmark, layers) and the ids and hashes of its strokes, pictures, text boxes, file
 * cards and reviewed handwriting. It stays on the device (sync keeps it); the notebook format does not change.
 *
 * [merge] keeps every change made on either side: a page or item added on either side is kept, one removed on one side
 * goes unless the other side changed it, one changed on one side takes that change. When the same page setting or item
 * was changed differently on both sides, this device's version stays and the other device's whole page follows it as a
 * copy, so nothing is lost ([Result.clashes]).
 */
object NotebookMerge {
    const val OTHER_DEVICE = "Other device's version"
    internal val json = Json { encodeDefaults = true; explicitNulls = false }

    @Serializable data class PageBase(val id: String, val whole: String, val settings: String, val items: Map<String, String>)
    @Serializable data class Base(val version: Int = 1, val title: String, val pages: List<PageBase>)

    /** What one side changed since the base, for the choice dialog. Page numbers are 1-based, in that side's notebook. */
    data class Changes(val added: Int, val removed: Int, val changedPages: List<Int>, val retitled: Boolean) {
        val none get() = added == 0 && removed == 0 && changedPages.isEmpty() && !retitled
    }
    data class Result(val notebook: Notebook, val clashes: Int)

    internal fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }

    internal fun settingsOf(page: Page) = page.copy(strokes = emptyList(), images = emptyList(), textBoxes = emptyList(), attachments = emptyList(), reviewedInk = emptyList())
    internal fun settingsHash(page: Page) = hash(json.encodeToString(settingsOf(page)))
    internal fun items(page: Page): Map<String, String> = LinkedHashMap<String, String>().apply {
        page.strokes.forEach { put("s:${it.id}", hash(json.encodeToString(it))) }
        page.images.forEach { put("i:${it.id}", hash(json.encodeToString(it))) }
        page.textBoxes.forEach { put("t:${it.id}", hash(json.encodeToString(it))) }
        page.attachments.forEach { put("a:${it.id}", hash(json.encodeToString(it))) }
        page.reviewedInk.forEach { put("r:${it.id}", hash(json.encodeToString(it))) }
    }
    internal fun pageBase(page: Page): PageBase {
        val settings = settingsHash(page); val items = items(page)
        return PageBase(page.id, hash(settings + items.entries.sortedBy { it.key }.joinToString("") { "${it.key}=${it.value};" }), settings, items)
    }

    fun base(notebook: Notebook) = Base(title = notebook.title, pages = notebook.pages.map(::pageBase))
    fun encode(base: Base): ByteArray = json.encodeToString(base).toByteArray(Charsets.UTF_8)
    fun decode(bytes: ByteArray): Base = json.decodeFromString<Base>(bytes.toString(Charsets.UTF_8)).also { require(it.version == 1) { "Unknown base version" } }

    fun changes(base: Base, notebook: Notebook): Changes {
        val before = base.pages.associateBy { it.id }
        val ids = notebook.pages.map { it.id }.toSet()
        val changed = notebook.pages.mapIndexedNotNull { index, page -> before[page.id]?.takeIf { it.whole != pageBase(page).whole }?.let { index + 1 } }
        return Changes(notebook.pages.count { it.id !in before }, base.pages.count { it.id !in ids }, changed, notebook.title != base.title)
    }

    /** P715: the same as [changes], from a side's page records alone, so the choice box need not hold that side's notebook. */
    fun changes(base: Base, other: Base): Changes {
        val before = base.pages.associateBy { it.id }
        val ids = other.pages.map { it.id }.toSet()
        val changed = other.pages.mapIndexedNotNull { index, page -> before[page.id]?.takeIf { it.whole != page.whole }?.let { index + 1 } }
        return Changes(other.pages.count { it.id !in before }, base.pages.count { it.id !in ids }, changed, other.title != base.title)
    }

    /**
     * Three-way list merge by id: [here]'s order, the other side's new entries after their nearest earlier neighbour.
     * [decide] gets (base hash or null, here's entry or null, there's entry or null) and returns the entry to keep or null.
     */
    private fun <T> mergeList(here: List<T>, there: List<T>, idOf: (T) -> String, decide: (String, T?, T?) -> T?): List<T> {
        val thereById = there.associateBy(idOf)
        val kept = ArrayList<T>()
        for (h in here) decide(idOf(h), h, thereById[idOf(h)])?.let(kept::add)
        val placed = kept.map(idOf).toHashSet()
        val hereIds = here.map(idOf).toHashSet()
        // The other side's new entries, grouped after the nearest earlier entry that is already placed (null: at the start).
        val after = HashMap<String?, MutableList<T>>()
        var anchor: String? = null
        for (t in there) {
            val id = idOf(t)
            if (id in placed) { anchor = id; continue }
            if (id in hereIds) continue // decided above
            decide(id, null, t)?.let { after.getOrPut(anchor) { ArrayList() }.add(it) }
        }
        if (after.isEmpty()) return kept
        val out = ArrayList<T>(kept.size + after.values.sumOf { it.size })
        after[null]?.let(out::addAll)
        for (k in kept) { out.add(k); after[idOf(k)]?.let(out::addAll) }
        return out
    }

    /** One item, three ways. [clash] is called when both sides changed it differently (here's version stays). */
    private fun <T> pick(base: String?, here: T?, there: T?, hashOf: (T) -> String, clash: () -> Unit): T? {
        if (here != null && here === there) return here // P715: one decoded item that both sides share is unchanged on both
        val h = here?.let(hashOf); val t = there?.let(hashOf)
        return when {
            here != null && there != null -> when {
                h == t || t == base -> here
                h == base -> there
                else -> { clash(); here }
            }
            here != null -> if (base != null && h == base) null else here   // removed there, unchanged here → goes
            there != null -> if (base != null && t == base) null else there // removed here, unchanged there → goes
            else -> null
        }
    }

    private fun mergePage(base: PageBase?, here: Page, there: Page): Pair<Page, Boolean> {
        var clashed = false
        val clash = { clashed = true }
        val settings = pick(base?.settings, here, there, ::settingsHash, clash)!!
        fun <T> objects(prefix: String, h: List<T>, t: List<T>, id: (T) -> String): List<T> =
            mergeList(h, t, id) { key, a, b -> pick(base?.items?.get("$prefix:$key"), a, b, { hash(json.encodeToString(serializerFor(prefix), it as Any)) }, clash) }
        var strokes = objects("s", here.strokes, there.strokes) { it.id }
        val images = objects("i", here.images, there.images) { it.id }
        val texts = objects("t", here.textBoxes, there.textBoxes) { it.id }
        val cards = objects("a", here.attachments, there.attachments) { it.id }
        val strokeIds = strokes.map { it.id }.toSet()
        val reviewed = objects("r", here.reviewedInk, there.reviewedInk) { it.id }.filter { r -> r.strokeIds.all { it in strokeIds } }
        // Strokes follow the page's layers, bottom first; a stroke never points at a layer or note that is gone.
        val layers = settings.layers.map { it.id }
        val noteIds = texts.map { it.id }.toSet()
        strokes = strokes.map { s ->
            val layer = if (layers.isEmpty()) null else s.layer?.takeIf { it in layers } ?: layers.first()
            val note = s.note?.takeIf { it in noteIds }
            if (layer == s.layer && note == s.note) s else s.copy(layer = layer, note = note)
        }
        if (layers.isNotEmpty()) strokes = strokes.sortedBy { layers.indexOf(it.layer) }
        // P715: a page that comes out exactly as one side has it stays that side's page (equal to the one built below), so
        // the combined notebook shares it instead of holding and encoding one more copy.
        for (side in listOf(here, there)) if (settings === side && same(strokes, side.strokes) && same(images, side.images) &&
            same(texts, side.textBoxes) && same(cards, side.attachments) && same(reviewed, side.reviewedInk)) return side to clashed
        return settingsOf(settings).copy(strokes = strokes, images = images, textBoxes = texts, attachments = cards, reviewedInk = reviewed) to clashed
    }

    private fun <T> same(a: List<T>, b: List<T>) = a.size == b.size && a.indices.all { a[it] === b[it] }

    @Suppress("UNCHECKED_CAST")
    internal fun serializerFor(prefix: String): kotlinx.serialization.KSerializer<Any> = when (prefix) {
        "s" -> InkStroke.serializer(); "i" -> ImageObject.serializer(); "t" -> TextBox.serializer()
        "a" -> FileAttachment.serializer(); else -> ReviewedInk.serializer()
    } as kotlinx.serialization.KSerializer<Any>

    /** A copy of [page] with fresh ids, marked as the other device's version. */
    private fun copyOf(page: Page): Page {
        val strokeIds = page.strokes.associate { it.id to UUID.randomUUID().toString() }
        val textIds = page.textBoxes.associate { it.id to UUID.randomUUID().toString() }
        return page.copy(id = UUID.randomUUID().toString(), bookmark = OTHER_DEVICE, outlineLevel = 1,
            strokes = page.strokes.map { it.copy(id = strokeIds.getValue(it.id), note = it.note?.let(textIds::get)) },
            images = page.images.map { it.copy(id = UUID.randomUUID().toString()) },
            textBoxes = page.textBoxes.map { it.copy(id = textIds.getValue(it.id)) },
            attachments = page.attachments.map { it.copy(id = UUID.randomUUID().toString()) }).withCopiedReviews(page)
    }

    /** Combines [here] and [there] (the same notebook on two devices) over [base]. The result is validated. */
    fun merge(base: Base, here: Notebook, there: Notebook): Result {
        require(here.id == there.id) { "Not the same notebook" }
        val before = base.pages.associateBy { it.id }
        val thereById = there.pages.associateBy { it.id }
        var clashes = 0
        val copies = HashMap<String, Page>() // merged page id → the other device's copy to follow it
        val pages = mergeList(here.pages, there.pages, { it.id }) { id, h, t ->
            val b = before[id]
            when {
                h != null && t != null -> mergePage(b, h, t).let { (page, clashed) -> if (clashed) { clashes++; copies[id] = copyOf(t) }; page }
                else -> pick(b?.whole, h, t, { pageBase(it).whole }) {}
            }
        }.flatMap { listOfNotNull(it, copies[it.id]) }
        require(pages.isNotEmpty()) { "Nothing left to merge" }
        val title = pick(base.title, here.title, there.title, { it }) {}!!
        // Files: this side's, plus the other side's that the merged pages use.
        val pdfs = pages.mapNotNull { it.pdfBackground?.assetId }.toSet()
        val pictures = pages.flatMap { p -> listOfNotNull(p.imageBackground?.assetId) + p.images.map { it.assetId } }.toSet()
        val files = pages.flatMap { p -> p.attachments.map { it.assetId } }.toSet()
        val merged = here.copy(title = title, pages = pages,
            assets = here.assets + there.assets.filter { a -> a.id in pdfs && here.assets.none { it.id == a.id } },
            imageAssets = here.imageAssets + there.imageAssets.filter { a -> a.id in pictures && here.imageAssets.none { it.id == a.id } },
            attachmentAssets = here.attachmentAssets + there.attachmentAssets.filter { a -> a.id in files && here.attachmentAssets.none { it.id == a.id } })
        merged.validate()
        return Result(merged, clashes)
    }
}
