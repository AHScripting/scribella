package dev.ownednotes.app

import android.util.LruCache

/** Reconstructible recognition results only. Estimated bytes include a floor that also bounds entry count. */
internal class RecognitionCache<K : Any, V : Any>(private val budget: Int, private val weight: (V) -> Long) {
    init { require(budget > 0) }
    private val entries = object : LruCache<K, V>(budget) {
        override fun sizeOf(key: K, value: V): Int = cost(value)
    }
    private fun cost(value: V): Int = maxOf((budget.toLong() + 511) / 512,
        weight(value).coerceIn(1, Int.MAX_VALUE.toLong())).toInt()
    @Synchronized operator fun get(key: K): V? = entries.get(key)
    @Synchronized operator fun set(key: K, value: V) {
        // A single oversized result must not flush every useful small result.
        if (cost(value) > budget) { entries.remove(key); return }
        entries.put(key, value)
    }
    @Synchronized fun clear() = entries.evictAll()
    @Synchronized internal fun estimatedBytes(): Int = entries.size()
}
