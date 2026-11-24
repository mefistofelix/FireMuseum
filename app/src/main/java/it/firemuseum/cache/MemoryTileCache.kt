package it.firemuseum.cache

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.LruCache

/** Tassello pronto da disegnare: [src] è la parte utile del bitmap (esclude l'overlap DZI). */
class CachedTile(val bitmap: Bitmap, val src: Rect)

/**
 * Cache RAM dei tasselli decodificati, dimensionata in byte.
 * Nessun riuso "inBitmap": con il rendering hardware un bitmap appena sfrattato può essere
 * ancora in upload sul RenderThread; con ART la GC dei bitmap è comunque economica.
 */
class MemoryTileCache(maxBytes: Int, val lowRam: Boolean) {

    /** RGB_565 dimezza la RAM ma fa "bande" nei cieli dipinti: solo su dispositivi poveri. */
    val config: Bitmap.Config = if (lowRam) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888

    private val lru = object : LruCache<String, CachedTile>(maxBytes) {
        override fun sizeOf(key: String, value: CachedTile) = value.bitmap.allocationByteCount
    }

    operator fun get(key: String): CachedTile? = lru.get(key)
    fun put(key: String, t: CachedTile) { lru.put(key, t) }
    fun contains(key: String) = lru.get(key) != null
    fun trimTo(fraction: Float) = lru.trimToSize((lru.maxSize() * fraction).toInt())
    fun clear() = lru.evictAll()
}
