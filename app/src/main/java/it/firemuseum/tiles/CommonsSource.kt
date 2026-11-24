package it.firemuseum.tiles

import android.graphics.Bitmap
import it.firemuseum.cache.BlobStore
import it.firemuseum.net.Net
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * Wikimedia Commons non offre tasselli IIIF. Strategia a strati, dal più leggero:
 *   1. anteprima 1280 px  (≈0,3–0,8 MB)  -> vista d'insieme immediata;
 *   2. anteprima 3840 px  (≈3–8 MB)      -> zoom medio;  (1280 e 3840 sono larghezze "standard"
 *      Wikimedia: le altre larghezze dal 2026 sono limitate in velocità)
 *   3. originale scaricato sul disco esterno su richiesta (50–420 MB) -> dettaglio vero;
 *   4. (meglio ancora) piramide .zip preparata sul PC: gestita da SourceFactory prima di arrivare qui.
 */
class CommonsSource private constructor(
    private val net: Net,
    private val blobs: BlobStore,
    private val config: Bitmap.Config,
    val fileName: String,
    override val width: Int,
    override val height: Int,
    val originalUrl: String,
    val originalBytes: Long,
    val mime: String,
    private val thumbUrl3840: String?,
) : TileSource() {

    override val id = "com:" + fileName.hashCode().toUInt().toString(36)
    private val baseName = "c_" + fileName.hashCode().toUInt().toString(36)
    private val mutex = Mutex()

    private var small: LocalRegionSource? = null
    private var large: LocalRegionSource? = null
    @Volatile private var original: LocalRegionSource? = null
    @Volatile var originalProblem: String? = null; private set

    private val kLarge = kFor(3840)
    private val kSmall = kFor(1280)

    private fun kFor(fileW: Int): Int {
        if (thumbUrl3840 == null) return 0
        var k = 0
        while ((width shr k) > fileW * 1.01) k++
        return k
    }

    init { tryAttachOriginal() }

    val originalOnDisk: Boolean get() = original != null
    val decodableOriginal: Boolean get() = mime == "image/jpeg" || mime == "image/png"

    override val diskCacheable = false
    override fun minK(): Int = if (original != null || thumbUrl3840 == null) 0 else kLarge
    override fun encodeToCache(k: Int) = original != null && k < kLarge && original!!.encodeToCache(k)

    private fun tryAttachOriginal() {
        if (thumbUrl3840 == null) return
        val f = blobs.file("${baseName}_orig.${ext()}") ?: return
        if (!decodableOriginal) { originalProblem = "Formato $mime non decodificabile a regioni: usa la piramide dal PC"; return }
        if (LocalRegionSource.isProgressiveJpeg(f)) { originalProblem = "JPEG progressivo: usa la piramide preparata sul PC"; return }
        original = LocalRegionSource(f, width, height, config, id)
    }

    private fun ext() = when (mime) { "image/png" -> "png"; "image/tiff" -> "tif"; else -> "jpg" }

    private fun thumb(w: Int): String? = thumbUrl3840?.replace("/3840px-", "/${w}px-")

    private suspend fun layer(which: Int): LocalRegionSource? = mutex.withLock {
        when (which) {
            1280 -> small ?: fetchLayer(thumb(1280) ?: originalUrl, "${baseName}_1280.jpg")?.also { small = it }
            else -> large ?: fetchLayer(thumb(3840) ?: originalUrl, "${baseName}_3840.jpg")?.also { large = it }
        }
    }

    private suspend fun fetchLayer(url: String, name: String): LocalRegionSource? {
        val f: File = blobs.file(name) ?: run {
            val bytes = net.bytes(url) ?: return null
            blobs.putSmall(name, bytes)
            blobs.file(name)
        } ?: return null
        return LocalRegionSource(f, width, height, config, id)
    }

    override suspend fun load(k: Int, x: Int, y: Int): TileData? {
        val o = original
        val src = when {
            o != null && k < kLarge -> o
            thumbUrl3840 == null -> layer(3840)          // immagine già piccola: un solo file
            k >= kSmall -> layer(1280)
            k >= kLarge -> layer(3840)
            else -> null
        } ?: return null
        return src.load(k, x, y)
    }

    /** Scarica l'originale a piena risoluzione sul disco esterno (con ripresa). */
    suspend fun downloadOriginal(progress: (Long, Long) -> Unit): Boolean {
        if (original != null) return true
        if (!decodableOriginal) { originalProblem = "Formato $mime: serve la piramide dal PC"; return false }
        val f = blobs.download(originalUrl, "${baseName}_orig.${ext()}", originalBytes, progress) ?: return false
        tryAttachOriginal()
        return original != null
    }

    fun pinOriginal(pin: Boolean) = blobs.pin("${baseName}_orig.${ext()}", pin)

    override fun close() { small?.close(); large?.close(); original?.close() }

    override fun describe(): String = when {
        original != null -> "Commons · originale su disco"
        thumbUrl3840 == null -> "Commons · file intero"
        else -> "Commons · anteprima 3840 px (scarica l'originale per il dettaglio massimo)"
    }

    companion object {
        /** Metadati del file via API (memorizzati nei blob per l'uso offline). */
        suspend fun open(net: Net, blobs: BlobStore, config: Bitmap.Config, file: String): CommonsSource? {
            val metaName = "c_" + file.hashCode().toUInt().toString(36) + "_info.json"
            val cached = blobs.file(metaName)?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            val info = cached ?: run {
                val t = URLEncoder.encode("File:" + file.replace('_', ' '), "UTF-8")
                val url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo" +
                    "&iiprop=size%7Curl%7Cmime&iiurlwidth=3840&titles=$t"
                val o = net.json(url) ?: return null
                val pages = o.optJSONObject("query")?.optJSONObject("pages") ?: return null
                val page = pages.optJSONObject(pages.keys().next()) ?: return null
                val ii = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: return null
                blobs.putSmall(metaName, ii.toString().toByteArray())
                ii
            }
            val w = info.optInt("width"); val h = info.optInt("height")
            if (w <= 0 || h <= 0) return null
            val orig = info.optString("url")
            val thumb = info.optString("thumburl").substringBefore("?").takeIf { w > 3840 && it.contains("/3840px-") }
            return CommonsSource(net, blobs, config, file, w, h, orig, info.optLong("size"), info.optString("mime"), thumb)
        }
    }
}
