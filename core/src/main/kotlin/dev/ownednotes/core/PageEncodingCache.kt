package dev.ownednotes.core

/** Reconstructible encoded pages. Notebook and page recency share one bounded retained-byte policy. */
internal class PageEncodingCache<T : Any>(private val budget: Long, private val maxEntries: Int = 4000,
    private val maxNotebooks: Int = 2, private val weight: (T) -> Long) {
    init { require(budget >= 0 && maxEntries > 0 && maxNotebooks > 0) }
    private class Entry<T>(val value: T, val bytes: Long)
    private val books = LinkedHashMap<String, LinkedHashMap<String, Entry<T>>>(4, .75f, true)
    private var bytes = 0L
    private var count = 0
    @Synchronized operator fun get(book: String, page: String): T? = books[book]?.get(page)?.value
    @Synchronized operator fun set(book: String, page: String, value: T) {
        val cost = weight(value); require(cost >= 0)
        books[book]?.remove(page)?.let { bytes -= it.bytes; count-- }
        if (books[book]?.isEmpty() == true) books.remove(book)
        if (cost > budget) return
        books.getOrPut(book) { LinkedHashMap(16, .75f, true) }[page] = Entry(value, cost)
        bytes += cost; count++
        while (books.size > maxNotebooks) {
            val oldest = books.entries.iterator(); val removed = oldest.next().value
            bytes -= removed.values.sumOf { it.bytes }; count -= removed.size; oldest.remove()
        }
        while (bytes > budget || count > maxEntries) {
            val oldestBook = books.entries.iterator(); val old = oldestBook.next().value
            val oldestPage = old.entries.iterator(); bytes -= oldestPage.next().value.bytes
            oldestPage.remove(); count--
            if (old.isEmpty()) oldestBook.remove()
        }
    }
    @Synchronized fun retainPages(book: String, ids: Set<String>) {
        val pages = books[book] ?: return
        val iterator = pages.entries.iterator()
        while (iterator.hasNext()) { val page = iterator.next()
            if (page.key !in ids) { bytes -= page.value.bytes; count--; iterator.remove() }
        }
        if (pages.isEmpty()) books.remove(book)
    }
    @Synchronized fun clear() { books.clear(); bytes = 0; count = 0 }
    @Synchronized internal fun retainedBytes() = bytes
    @Synchronized internal fun entryCount() = count
}
