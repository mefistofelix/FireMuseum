package it.firemuseum.cache

import android.util.Log
import it.firemuseum.net.Net
import it.firemuseum.storage.StorageManager
import it.firemuseum.ui.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * File grandi e interi: anteprime 3840 px, originali da 50–400 MB, piramidi .zip, metadati JSON.
 * Ogni file ha "ultimo accesso" e flag "fissato" (preferiti/offline) in un piccolo manifest JSON.
 * Gli originali NON fissati possono essere eliminati (dal meno recente) se lo spazio finisce
 * anche dopo aver svuotato i segmenti di tasselli più vecchi.
 */
class BlobStore(
    private val storage: StorageManager,
    private val settings: Settings,
    private val net: Net,
    private val scope: CoroutineScope,
) {
    var governor: SpaceGovernor? = null
    private var dir: File? = null
    private val meta = HashMap<String, Meta>()
    private var dirty = false

    data class Meta(var lastAccess: Long, var pinned: Boolean, var bytes: Long)

    init { reopen() }

    @Synchronized
    fun reopen() {
        meta.clear()
        dir = storage.dir("blobs")
        val d = dir ?: return
        runCatching {
            val f = File(d, "manifest.json")
            if (f.exists()) {
                val o = JSONObject(f.readText())
                o.keys().forEach { k ->
                    val m = o.getJSONObject(k)
                    meta[k] = Meta(m.optLong("a"), m.optBoolean("p"), m.optLong("b"))
                }
            }
        }
        // riconcilia con i file reali (estrazioni brusche, copie manuali dal PC)
        d.listFiles()?.forEach { f ->
            if (f.isFile && f.name != "manifest.json" && !f.name.endsWith(".part")) {
                val m = meta.getOrPut(f.name) { Meta(f.lastModified(), false, f.length()) }
                m.bytes = f.length()
            }
        }
        meta.keys.retainAll { File(d, it).exists() }
        dirty = true
        flush()
    }

    val totalBytes: Long @Synchronized get() = meta.values.sumOf { it.bytes }

    /** Cartella dei blob (per piramidi copiate a mano dal PC: blobs/<id>.zip). */
    fun root(): File? = dir

    @Synchronized
    fun file(name: String): File? {
        val d = dir ?: return null
        val f = File(d, safe(name))
        if (!f.exists()) return null
        meta.getOrPut(f.name) { Meta(0, false, f.length()) }.lastAccess = System.currentTimeMillis()
        dirty = true
        return f
    }

    @Synchronized
    fun pin(name: String, pinned: Boolean) {
        meta[safe(name)]?.pinned = pinned; dirty = true; flush()
    }

    fun isPinned(name: String): Boolean = synchronized(this) { meta[safe(name)]?.pinned == true }

    fun putSmall(name: String, bytes: ByteArray) {
        val d = dir ?: return
        try {
            val f = File(d, safe(name))
            val tmp = File(d, safe(name) + ".part")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            synchronized(this) { meta[f.name] = Meta(System.currentTimeMillis(), false, f.length()); dirty = true }
            governor?.onWrite(bytes.size.toLong())
        } catch (e: IOException) {
            governor?.emergency()
        }
    }

    /**
     * Scarica un file grande con ripresa (HTTP Range) in <nome>.part, poi rinomina.
     * Prima di iniziare verifica lo spazio: se expectedBytes non ci sta nemmeno dopo la pulizia, rinuncia.
     */
    suspend fun download(url: String, name: String, expectedBytes: Long, progress: (Long, Long) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val d = dir ?: return@withContext null
            val target = File(d, safe(name))
            if (target.exists()) return@withContext target
            if (expectedBytes > 0 && governor?.makeRoom(expectedBytes) == false) {
                Log.w(TAG, "not enough space for $name ($expectedBytes)")
                return@withContext null
            }
            val part = File(d, safe(name) + ".part")
            val ctx = coroutineContext
            var attempt = 0
            while (attempt < 6) {
                coroutineContext.ensureActive()
                try {
                    val have = if (part.exists()) part.length() else 0L
                    net.stream(url, have) { body, total, resumed ->
                        FileOutputStream(part, resumed).use { out ->
                            val buf = ByteArray(256 * 1024)
                            var done = if (resumed) have else 0L
                            val full = if (resumed && total > 0) total + have else total
                            while (true) {
                                ctx.ensureActive()
                                val r = body.read(buf)
                                if (r < 0) break
                                out.write(buf, 0, r)
                                done += r
                                if (done % (4L shl 20) < r) progress(done, full)
                            }
                        }
                    }
                    if (!part.renameTo(target)) throw IOException("rename failed")
                    synchronized(this@BlobStore) {
                        meta[target.name] = Meta(System.currentTimeMillis(), false, target.length()); dirty = true
                    }
                    flush()
                    governor?.onWrite(0)
                    return@withContext target
                } catch (e: IOException) {
                    attempt++
                    Log.w(TAG, "download $name attempt $attempt: ${e.message}")
                    if (storage.current?.let { it.free < 64L shl 20 } == true) governor?.emergency()
                    kotlinx.coroutines.delay(2000L * attempt)
                }
            }
            null
        }

    /** Elimina il blob non fissato meno usato di recente. Byte liberati (0 se nulla da eliminare). */
    @Synchronized
    fun evictOne(): Long {
        val d = dir ?: return 0
        val victim = meta.entries.filter { !it.value.pinned }.minByOrNull { it.value.lastAccess } ?: return 0
        val f = File(d, victim.key)
        val b = victim.value.bytes
        f.delete()
        meta.remove(victim.key)
        dirty = true
        Log.i(TAG, "evicted blob ${victim.key} (${b shr 20} MB)")
        return b
    }

    @Synchronized
    fun flush() {
        if (!dirty) return
        val d = dir ?: return
        runCatching {
            val o = JSONObject()
            meta.forEach { (k, m) -> o.put(k, JSONObject().put("a", m.lastAccess).put("p", m.pinned).put("b", m.bytes)) }
            val tmp = File(d, "manifest.json.part")
            tmp.writeText(o.toString())
            val f = File(d, "manifest.json")
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            dirty = false
        }
    }

    private fun safe(n: String) = n.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180)

    companion object { private const val TAG = "BlobStore" }
}
