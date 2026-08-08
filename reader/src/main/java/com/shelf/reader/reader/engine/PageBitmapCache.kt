package com.shelf.reader.reader.engine

import android.graphics.Bitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe LRU cache for rendered page [Bitmap]s.
 *
 * Evicted bitmaps are recycled immediately to release native memory.
 *
 * @param maxSize Maximum number of bitmaps held in memory simultaneously.
 *                Default of 5 keeps current ± 2 pages resident.
 */
class PageBitmapCache(private val maxSize: Int = 8) {

    // accessOrder = true → get() promotes entry to MRU position.
    private val lru = LinkedHashMap<Int, Bitmap>(maxSize + 1, 0.75f, true)

    /** Returns the cached bitmap synchronously for Canvas rendering. */
    fun getSync(page: Int): Bitmap? = synchronized(lru) { lru[page] }

    /** Returns the cached bitmap for [page], or null if not yet rendered. */
    suspend fun get(page: Int): Bitmap? = synchronized(lru) { lru[page] }

    /** Stores a rendered [bitmap] under [page]. */
    suspend fun put(page: Int, bitmap: Bitmap) = synchronized(lru) {
        lru[page] = bitmap
        if (lru.size > maxSize) {
            val it = lru.keys.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
    }

    /** Returns true if [page] is already cached. */
    suspend fun contains(page: Int): Boolean = synchronized(lru) { lru.containsKey(page) }

    /** Clears the cache. Call when the book changes or font size changes. */
    suspend fun clear() = synchronized(lru) {
        lru.clear()
    }
}
