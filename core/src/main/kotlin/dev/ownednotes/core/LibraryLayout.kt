package dev.ownednotes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * P698: the Library's organisation (folders, folder colours, filing, covers, tags, pinned, archived) shared through the
 * sync folder as one small file, so every device shows the same arrangement.
 *
 * Each item is a [Entry] keyed by its set and key: a value (absent = removed) and the time it last changed. Merging keeps
 * the newer change per item; a device's untouched arrangement has time 0, so it never wins against a real change. An item
 * missing from one side is not a removal: removals are written as entries without a value.
 */
object LibraryLayout {
    const val FILE_NAME = ".notes-lab-library.json"
    const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_ENTRIES = 50_000
    private const val MAX_TEXT = 1_000

    @Serializable
    data class Entry(val set: String, val key: String, val value: String? = null, val time: Long = 0L)
    @Serializable
    private data class Document(val version: Int = 1, val entries: List<Entry> = emptyList())

    /** Set name and key of an item. */
    data class Item(val set: String, val key: String)
    private fun Entry.item() = Item(set, key)

    class Invalid(message: String) : IOException(message)

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun decode(bytes: ByteArray): Map<Item, Entry> {
        if (bytes.size > MAX_BYTES) throw Invalid("The shared library layout is too large")
        val doc = runCatching { json.decodeFromString(Document.serializer(), bytes.decodeToString()) }.getOrElse { throw Invalid("The shared library layout is damaged") }
        if (doc.version != 1) throw Invalid("The shared library layout is from a newer version")
        if (doc.entries.size > MAX_ENTRIES) throw Invalid("The shared library layout has too many items")
        val out = HashMap<Item, Entry>()
        for (e in doc.entries) {
            if (e.set.isEmpty() || e.set.length > 40 || e.key.isEmpty() || e.key.length > MAX_TEXT || (e.value?.length ?: 0) > MAX_TEXT || e.time < 0) continue
            val old = out[e.item()]
            out[e.item()] = if (old == null) e else newer(old, e)
        }
        return out
    }

    fun encode(entries: Map<Item, Entry>): ByteArray {
        val sorted = entries.values.sortedWith(compareBy<Entry>({ it.set }, { it.key }))
        return json.encodeToString(Document.serializer(), Document(1, sorted)).encodeToByteArray()
            .also { if (it.size > MAX_BYTES) throw Invalid("The library layout is too large to share") }
    }

    /** The current values as entries, all with time 0: a device's arrangement before it was ever shared. */
    fun baseline(current: Map<Item, String>): Map<Item, Entry> = current.map { (i, v) -> i to Entry(i.set, i.key, v, 0L) }.toMap()

    /**
     * [known] brought up to [current]: items whose value differs (added, changed or removed) get [now]; the rest keep
     * their entries.
     */
    fun stamp(known: Map<Item, Entry>, current: Map<Item, String>, now: Long): Map<Item, Entry> {
        val out = HashMap(known)
        for (item in known.keys + current.keys) {
            val value = current[item]
            val old = known[item]
            if (old?.value == value) continue
            if (old == null && value == null) continue
            out[item] = Entry(item.set, item.key, value, now)
        }
        return out
    }

    /** Per item, the newer entry; on equal times the same answer on every device. */
    fun merge(local: Map<Item, Entry>, remote: Map<Item, Entry>): Map<Item, Entry> {
        val out = HashMap(local)
        for ((item, r) in remote) out[item] = local[item]?.let { newer(it, r) } ?: r
        return out
    }

    private fun newer(a: Entry, b: Entry): Entry = when {
        a.time != b.time -> if (a.time > b.time) a else b
        a.value == b.value -> a
        a.value == null -> b
        b.value == null -> a
        else -> if (a.value >= b.value) a else b
    }

    /** The values that are present. */
    fun values(entries: Map<Item, Entry>): Map<Item, String> = entries.mapNotNull { (i, e) -> e.value?.let { i to it } }.toMap()

    /** True when the two agree on every item's value and time. */
    fun same(a: Map<Item, Entry>, b: Map<Item, Entry>): Boolean = a == b
}
