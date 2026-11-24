package it.firemuseum.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import it.firemuseum.App
import it.firemuseum.model.Artwork
import it.firemuseum.model.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/** Miniature della griglia: 330 px (larghezza standard Wikimedia), cache su disco esterno + RAM. */
class ThumbLoader(private val scope: CoroutineScope) {
    private val app = App.instance
    private val mem = object : LruCache<String, Bitmap>(24 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val permits = Semaphore(4)

    suspend fun url(a: Artwork): String? = when (val s = a.source) {
        is Source.Commons -> commons(s.file)
        is Source.Wikidata -> app.wikidata.imageFile(s.qid)?.let { commons(it) }
        is Source.Iiif -> s.info.removeSuffix("/info.json") + "/full/!400,260/0/default.jpg"
        is Source.Dzi -> if (s.url.startsWith("http")) s.url.substringBeforeLast(".dzi") + "_files/8/0_0.jpg" else null
        is Source.Local -> null
    }

    private fun commons(file: String) =
        "https://commons.wikimedia.org/wiki/Special:FilePath/" + URLEncoder.encode(file, "UTF-8").replace("+", "%20") + "?width=330"

    fun load(a: Artwork, iv: ImageView): Job? {
        val key = "thumb/" + a.id
        iv.tag = key
        mem.get(key)?.let { iv.setImageBitmap(it); return null }
        iv.setImageDrawable(null)
        return scope.launch {
            val bmp = permits.withPermit {
                withContext(Dispatchers.IO) {
                    val bytes = app.tiles.get(key) ?: runCatching { app.mega.thumb(a) }.getOrNull() ?: runCatching {
                        url(a)?.let { app.net.bytes(it) }?.also { app.tiles.put(key, it) }
                    }.getOrNull()
                    bytes?.let {
                        BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
                    }
                }
            } ?: return@launch
            mem.put(key, bmp)
            if (iv.tag == key) iv.setImageBitmap(bmp)
        }
    }
}
