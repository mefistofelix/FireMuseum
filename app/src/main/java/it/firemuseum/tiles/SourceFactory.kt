package it.firemuseum.tiles

import android.util.Log
import it.firemuseum.App
import it.firemuseum.model.Artwork
import it.firemuseum.model.Source
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Crea (e tiene aperte le ultime) sorgenti di tasselli. Priorità per ogni opera:
 *   1. piramide preparata sul PC e copiata sul disco esterno:  <volume>/pyramids/<id>.zip  o  <id>/<id>.dzi
 *   2. piramide servita in LAN dal PC:                          <server>/pyramids/<id>.dzi
 *   3. sorgente dichiarata nel catalogo (IIIF, Commons a strati, DZI remoto, file locale).
 */
class SourceFactory(private val app: App) {

    private val opened = object : LinkedHashMap<String, TileSource>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, TileSource>): Boolean {
            if (size > 8) { runCatching { e.value.close() }; return true }
            return false
        }
    }
    private val lanMisses = HashSet<String>()
    private val mutex = Mutex()

    suspend fun get(a: Artwork): TileSource? = mutex.withLock {
        opened[a.id]?.let { return it }
        val s = runCatching { create(a) }.onFailure { Log.w(TAG, "open ${a.id} failed", it) }.getOrNull()
        if (s != null) {
            opened[a.id] = s
            if (a.width <= 0) { a.width = s.width; a.height = s.height }
        }
        s
    }

    /** Dopo il download dell'originale o la copia di una piramide: forza la riapertura. */
    suspend fun invalidate(id: String) = mutex.withLock { opened.remove(id)?.close(); Unit }

    fun pyramidDir(): File? = app.storage.dir("pyramids")

    private suspend fun create(a: Artwork): TileSource? {
        pyramidDir()?.let { d ->
            File(d, "${a.id}.zip").takeIf { it.exists() }?.let { f -> DziSource.openZip(f)?.let { return it } }
            File(d, "${a.id}/${a.id}.dzi").takeIf { it.exists() }?.let { f -> DziSource.openDir(f)?.let { return it } }
            File(d, "${a.id}.dzi").takeIf { it.exists() }?.let { f -> DziSource.openDir(f)?.let { return it } }
        }
        val lan = app.settings.lanServer.trim()
        if (lan.isNotEmpty() && a.id !in lanMisses) {
            val url = lan.trimEnd('/') + "/pyramids/${a.id}.dzi"
            val s = runCatching { DziSource.openHttp(app.net, url) }.getOrNull()
            if (s != null) return s else lanMisses += a.id
        }
        return open(a.source)
    }

    suspend fun open(src: Source): TileSource? = when (src) {
        is Source.Iiif -> IiifSource.open(app.net, src.info)
        is Source.Dzi -> if (src.url.startsWith("http")) DziSource.openHttp(app.net, src.url) else {
            val f = File(app.storage.current?.root, src.url)
            if (f.name.endsWith(".zip")) DziSource.openZip(f) else DziSource.openDir(f)
        }
        is Source.Commons -> CommonsSource.open(app.net, app.blobs, app.memory.config, src.file)
        is Source.Local -> {
            val f = File(app.storage.current?.root, src.path)
            if (f.exists()) {
                val probe = LocalRegionSource(f, 1, 1, app.memory.config)
                LocalRegionSource(f, probe.fileW, probe.fileH, app.memory.config)
            } else null
        }
        is Source.Wikidata -> app.wikidata.imageFile(src.qid)?.let { CommonsSource.open(app.net, app.blobs, app.memory.config, it) }
    }

    companion object { private const val TAG = "SourceFactory" }
}
