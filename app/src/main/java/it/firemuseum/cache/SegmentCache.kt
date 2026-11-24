package it.firemuseum.cache

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import it.firemuseum.storage.StorageManager
import it.firemuseum.ui.Settings
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache dei tasselli "log-structured", pensata per chiavette/dischi USB su Fire TV:
 *
 *  - I tasselli (20–200 KB l'uno) NON sono file singoli: su exFAT/FAT32 con cluster da 128 KB
 *    centinaia di migliaia di file piccoli sprecherebbero fino al 50% dello spazio e
 *    renderebbero lentissime cancellazione e scansione. Vengono invece accodati in
 *    "segmenti" da 32 MB (scrittura sequenziale = veloce e gentile con la flash).
 *  - Indice in SQLite (WAL) sullo stesso volume: chiave -> (segmento, offset, lunghezza).
 *  - Eliminazione INCREMENTALE: quando manca spazio si cancella il segmento più vecchio
 *    (una sola operazione di file system libera 32 MB), poi si ricontrolla.
 *  - LRU approssimato: un tassello letto da un segmento "vecchio" viene ricopiato nel
 *    segmento corrente (promozione), così i tasselli usati spesso sopravvivono alla pulizia.
 *  - Robusto a crash/estrazione del disco: un record oltre la fine del file è un semplice miss.
 */
class SegmentCache(
    private val storage: StorageManager,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    var governor: SpaceGovernor? = null

    private var dir: File? = null
    private var db: SQLiteDatabase? = null
    private val channels = ConcurrentHashMap<Int, FileChannel>()
    private val segSizes = ConcurrentHashMap<Int, Long>()
    private val writeLock = Any()
    private var curSeg = 0
    private var curFile: RandomAccessFile? = null
    @Volatile private var enabled = false

    val totalBytes: Long get() = segSizes.values.sum()
    val segmentCount: Int get() = segSizes.size

    init { reopen() }

    @Synchronized
    fun reopen() {
        close()
        val d = storage.dir("tiles") ?: return
        try {
            dir = d
            val database = SQLiteDatabase.openOrCreateDatabase(File(d, "index.db"), null)
            database.enableWriteAheadLogging()
            database.execSQL("PRAGMA synchronous=NORMAL")
            database.execSQL("CREATE TABLE IF NOT EXISTS t(k TEXT PRIMARY KEY, seg INTEGER, off INTEGER, len INTEGER)")
            database.execSQL("CREATE INDEX IF NOT EXISTS t_seg ON t(seg)")
            db = database
            d.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".bin") }?.forEach { f ->
                f.name.removePrefix("seg_").removeSuffix(".bin").toIntOrNull()?.let { segSizes[it] = f.length() }
            }
            // segmenti presenti nell'indice ma spariti dal disco -> pulisci l'indice
            val known = segSizes.keys
            database.rawQuery("SELECT DISTINCT seg FROM t", null).use { c ->
                val stale = mutableListOf<Int>()
                while (c.moveToNext()) { val s = c.getInt(0); if (s !in known) stale += s }
                stale.forEach { database.delete("t", "seg=?", arrayOf(it.toString())) }
            }
            curSeg = (segSizes.keys.maxOrNull() ?: 0)
            openWriter(if ((segSizes[curSeg] ?: 0) >= SEG_SIZE) curSeg + 1 else curSeg)
            enabled = true
            Log.i(TAG, "tile cache at $d: ${segSizes.size} segments, ${totalBytes shr 20} MB")
        } catch (t: Throwable) {
            Log.e(TAG, "cannot open cache", t)
            close()
        }
    }

    private fun segFile(n: Int) = File(dir, "seg_%06d.bin".format(n))

    private fun openWriter(n: Int) {
        curFile?.close()
        curSeg = n
        val raf = RandomAccessFile(segFile(n), "rw")
        raf.seek(raf.length())
        curFile = raf
        segSizes[n] = raf.length()
    }

    fun get(key: String): ByteArray? {
        if (!enabled) return null
        val database = db ?: return null
        var seg = 0; var off = 0L; var len = 0
        try {
            database.rawQuery("SELECT seg, off, len FROM t WHERE k=?", arrayOf(key)).use { c ->
                if (!c.moveToFirst()) return null
                seg = c.getInt(0); off = c.getLong(1); len = c.getInt(2)
            }
            val ch = channel(seg) ?: return null
            if (off + len > ch.size()) return null
            val buf = ByteBuffer.allocate(len)
            var pos = off
            while (buf.hasRemaining()) {
                val r = ch.read(buf, pos)
                if (r <= 0) return null
                pos += r
            }
            val bytes = buf.array()
            // Promozione LRU: se il tassello sta in un segmento della metà più vecchia, ricopialo.
            val oldest = segSizes.keys.minOrNull() ?: seg
            if (segSizes.size >= 4 && seg < oldest + segSizes.size / 2) put(key, bytes)
            return bytes
        } catch (t: Throwable) {
            Log.w(TAG, "read failed $key: ${t.message}")
            return null
        }
    }

    fun contains(key: String): Boolean {
        val database = db ?: return false
        return runCatching {
            database.rawQuery("SELECT 1 FROM t WHERE k=?", arrayOf(key)).use { it.moveToFirst() }
        }.getOrDefault(false)
    }

    fun put(key: String, data: ByteArray) {
        if (!enabled || data.isEmpty()) return
        val database = db ?: return
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        synchronized(writeLock) {
            try {
                var f = curFile ?: return
                val recLen = 4 + 2 + keyBytes.size + 4 + data.size
                if (f.length() + recLen > SEG_SIZE && f.length() > 0) {
                    openWriter(curSeg + 1)
                    f = curFile!!
                }
                val start = f.length()
                val header = ByteBuffer.allocate(4 + 2 + keyBytes.size + 4)
                header.putInt(MAGIC).putShort(keyBytes.size.toShort()).put(keyBytes).putInt(data.size)
                f.seek(start)
                f.write(header.array())
                val dataOff = f.filePointer
                f.write(data)
                segSizes[curSeg] = f.length()
                val cv = ContentValues(4).apply {
                    put("k", key); put("seg", curSeg); put("off", dataOff); put("len", data.size)
                }
                database.insertWithOnConflict("t", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            } catch (t: Throwable) {
                Log.w(TAG, "write failed: ${t.message}")
                // Disco pieno o rimosso: chiedi subito una pulizia e rivaluta il volume.
                governor?.emergency()
                storage.refresh()
                return
            }
        }
        governor?.onWrite(data.size.toLong())
    }

    /** Cancella il segmento più vecchio (mai quello in scrittura). Restituisce i byte liberati. */
    fun dropOldestSegment(): Long {
        synchronized(writeLock) {
            val oldest = segSizes.keys.filter { it != curSeg }.minOrNull() ?: return 0
            val size = segSizes[oldest] ?: 0
            runCatching { db?.delete("t", "seg=?", arrayOf(oldest.toString())) }
            channels.remove(oldest)?.let { runCatching { it.close() } }
            segFile(oldest).delete()
            segSizes.remove(oldest)
            Log.i(TAG, "evicted segment $oldest (${size shr 20} MB)")
            return size
        }
    }

    /** Elimina tutti i tasselli di una sorgente (es. quando un'opera viene rimossa). Lazy: solo indice. */
    fun forgetPrefix(prefix: String) {
        runCatching { db?.delete("t", "k LIKE ?", arrayOf("$prefix%")) }
    }

    private fun channel(seg: Int): FileChannel? {
        channels[seg]?.let { return it }
        if (channels.size > 24) channels.entries.firstOrNull()?.let { e ->
            if (e.key != seg) { channels.remove(e.key); runCatching { e.value.close() } }
        }
        val f = segFile(seg)
        if (!f.exists()) return null
        return RandomAccessFile(f, "r").channel.also { channels[seg] = it }
    }

    @Synchronized
    fun close() {
        enabled = false
        synchronized(writeLock) {
            runCatching { curFile?.close() }; curFile = null
            channels.values.forEach { runCatching { it.close() } }; channels.clear()
            runCatching { db?.close() }; db = null
            segSizes.clear()
        }
    }

    fun clear() {
        synchronized(writeLock) { while (segSizes.size > 1 && dropOldestSegment() > 0) Unit }
    }

    companion object {
        private const val TAG = "SegmentCache"
        const val SEG_SIZE = 32L shl 20
        private const val MAGIC = 0x464D5431 // "FMT1"
    }
}
