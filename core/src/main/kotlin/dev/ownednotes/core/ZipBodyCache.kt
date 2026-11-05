package dev.ownednotes.core

/** Reconstructible compressed originals. Enforce the budget after every insertion, including replacement. */
internal class ZipBodyCache(private val budget: Long, private val entryLimit: Int = 4096) {
    init { require(budget >= 0 && entryLimit > 0) }
    private val entries = LinkedHashMap<String, ZipBody>(16, .75f, true)
    private var bytes = 0L
    @Synchronized operator fun get(key: String): ZipBody? = entries[key]
    @Synchronized operator fun set(key: String, body: ZipBody) {
        entries.remove(key)?.let { bytes -= it.compressed.size }
        val size = body.compressed.size.toLong()
        if (size > budget) return
        val oldest = entries.entries.iterator()
        while ((bytes + size > budget || entries.size >= entryLimit) && oldest.hasNext()) {
            bytes -= oldest.next().value.compressed.size; oldest.remove()
        }
        entries[key] = body; bytes += size
    }
    @Synchronized fun clear() { entries.clear(); bytes = 0 }
    @Synchronized internal fun retainedBytes() = bytes
}
