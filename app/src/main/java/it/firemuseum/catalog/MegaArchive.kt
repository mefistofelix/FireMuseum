package it.firemuseum.catalog

import android.util.Log
import it.firemuseum.App
import it.firemuseum.model.Artwork
import it.firemuseum.net.MegaFolder
import it.firemuseum.tiles.DziSource
import it.firemuseum.tiles.RangeZip
import it.firemuseum.tiles.TileSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Archivio FireMuseum su MEGA (preparato sul PC con tools/mega_pack.py): indice completo del
 * catalogo, anteprime e piramidi Deep Zoom già pronte, così l'app non dipende da Wikimedia
 * (che limita i download degli originali). Tutto quello che si scarica (elenco, indice,
 * directory degli zip, anteprime) resta sul disco esterno e l'app funziona anche senza rete
 * per le zone già viste.
 */
class MegaArchive(private val app: App) {

    private val mutex = Mutex()
    private var folder: MegaFolder? = null
    private var folderLink: String? = null
    private var online = false
    private var thumbs: ZipFile? = null
    private var thumbsTried = false

    private suspend fun ready(): MegaFolder? = mutex.withLock {
        val link = app.settings.megaLink.trim()
        if (link.isEmpty()) return null
        if (folderLink != link) {
            folder = MegaFolder.open(app.net, link); folderLink = link; online = false
            thumbs?.close(); thumbs = null; thumbsTried = false
        }
        val f = folder ?: return null
        val cacheName = "mega_list_${f.folderId}.json"
        if (!online) {
            val fresh = withTimeoutOrNull(12_000) { runCatching { f.refresh() }.onFailure { Log.w(TAG, "elenco: ${it.message}") }.getOrNull() }
            if (fresh != null) {
                online = true
                app.blobs.putSmall(cacheName, fresh.toString().toByteArray())
            } else if (f.files.isEmpty()) {
                app.blobs.file(cacheName)?.let { runCatching { f.restore(JSONObject(it.readText())) } }
            }
        }
        f.takeIf { it.files.isNotEmpty() }
    }

    /** Testo di index.json (scaricato una volta per versione, poi dal disco). */
    suspend fun indexText(): String? {
        val f = ready() ?: return null
        val e = f.files["index.json"] ?: return null
        val name = "mega_index_${e.handle}.json"
        app.blobs.file(name)?.let { return it.readText() }
        val bytes = runCatching { f.readAll(e) }.onFailure { Log.w(TAG, "index: ${it.message}") }.getOrNull() ?: return null
        app.blobs.putSmall(name, bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /** Anteprima 400 px da thumbs.zip (un solo download di pochi MB). */
    suspend fun thumb(a: Artwork): ByteArray? {
        if (a.mirror == null) return null
        val z = thumbsZip() ?: return null
        return withContext(Dispatchers.IO) {
            synchronized(z) { z.getEntry("${a.id}.jpg")?.let { en -> z.getInputStream(en).use { it.readBytes() } } }
        }
    }

    private suspend fun thumbsZip(): ZipFile? {
        thumbs?.let { return it }
        val f = ready() ?: return null
        return mutex.withLock { thumbs ?: openThumbs(f).also { thumbs = it } }
    }

    private suspend fun openThumbs(f: MegaFolder): ZipFile? {
        if (thumbsTried) return null
        val e = f.files["thumbs.zip"] ?: return null
        val name = "mega_thumbs_${e.handle}.zip"
        var file: File? = app.blobs.file(name)
        if (file == null) {
            val bytes = runCatching { f.readAll(e) }.getOrNull() ?: return null
            app.blobs.putSmall(name, bytes)
            app.blobs.pin(name, true)
            file = app.blobs.file(name)
        }
        thumbsTried = true
        return file?.let { runCatching { ZipFile(it) }.getOrNull() }
    }

    /** Piramide dell'opera letta a intervalli dal link pubblico. */
    suspend fun source(a: Artwork): TileSource? {
        val name = a.mirror ?: return null
        val f = ready() ?: return null
        val e = f.files[name] ?: return null
        val cdName = "mega_cd_${e.handle}.bin"
        val cached = app.blobs.file(cdName)?.readBytes()
        val zip = RangeZip.open(e.size, { s, end -> f.read(e, s, end) }, cached) { app.blobs.putSmall(cdName, it) }
        return DziSource.openRangeZip("mz:${e.handle}", zip, "Archivio MEGA · Deep Zoom")
    }

    fun describe(): String = folder?.let { "${it.files.size} file${if (online) "" else " (senza rete: elenco salvato)"}" } ?: "non configurato"

    companion object { private const val TAG = "MegaArchive" }
}
