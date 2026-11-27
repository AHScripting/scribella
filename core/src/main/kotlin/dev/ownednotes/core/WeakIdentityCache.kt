package dev.ownednotes.core

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/**
 * P716: a value remembered per object instance (by identity, as IdentityHashMap does) only while that object is still in
 * use elsewhere. A hash kept for a page or stroke must not keep that page or stroke itself in memory: the page previews'
 * caches held every replaced version of a page with its strokes until 256 pages or 50,000 strokes, which on a large
 * notebook is hundreds of MB. [limit] still bounds the number of entries. Not thread-safe; use it from one thread.
 */
class WeakIdentityCache<K : Any, V : Any>(private val limit: Int = Int.MAX_VALUE) {
    private class Entry(referent: Any, queue: ReferenceQueue<Any>) : WeakReference<Any>(referent, queue) {
        val hash = System.identityHashCode(referent)
        override fun hashCode() = hash
        override fun equals(other: Any?) = other === this
    }
    /** A lookup key for [referent]: equal to the entry that refers to the same instance. */
    private class Probe(val referent: Any) {
        override fun hashCode() = System.identityHashCode(referent)
        override fun equals(other: Any?) = other is Entry && other.get() === referent
    }
    private val queue = ReferenceQueue<Any>()
    private val map = HashMap<Any, V>()

    /** Entries whose object is gone are dropped first. */
    private fun purge() { while (true) { val gone = queue.poll() ?: break; map.remove(gone) } }

    val size: Int get() { purge(); return map.size }

    operator fun get(key: K): V? { purge(); return map[Probe(key)] }

    operator fun set(key: K, value: V) {
        purge()
        if (map.size >= limit) map.clear()
        map.remove(Probe(key))
        map[Entry(key, queue)] = value
    }

    fun getOrPut(key: K, compute: () -> V): V = get(key) ?: compute().also { set(key, it) }

    fun clear() { purge(); map.clear() }
}
