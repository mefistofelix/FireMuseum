package it.firemuseum.cache

import android.util.Log
import it.firemuseum.storage.StorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Pulizia incrementale dello spazio.
 *
 * Ordine di sacrificio (dal più economico da ricostruire al più costoso):
 *   1. segmenti di tasselli più vecchi (32 MB alla volta), lasciandone almeno MIN_SEGMENTS;
 *   2. originali/anteprime NON fissati, dal meno usato;
 *   3. mai: blob fissati (preferiti offline) e il segmento in scrittura.
 *
 * Gira in background ogni WRITE_CHECK byte scritti, all'avvio e in emergenza (ENOSPC).
 * Ogni passo cancella UNA cosa e ricontrolla: niente pause lunghe, niente svuotamenti totali.
 */
class SpaceGovernor(
    private val storage: StorageManager,
    private val tiles: SegmentCache,
    private val blobs: BlobStore,
    private val scope: CoroutineScope,
) {
    private val sinceCheck = AtomicLong(0)
    private val mutex = Mutex()

    fun onWrite(bytes: Long) {
        if (sinceCheck.addAndGet(bytes) >= WRITE_CHECK) { sinceCheck.set(0); trimAsync() }
    }

    fun emergency() = trimAsync(urgent = true)

    fun trimAsync(urgent: Boolean = false) {
        scope.launch(Dispatchers.IO) { trim(urgent) }
    }

    private fun usage() = tiles.totalBytes + blobs.totalBytes

    private fun overBudget(extra: Long = 0): Boolean {
        val v = storage.current ?: return false
        val used = usage()
        val budget = storage.budget(used)
        return used + extra > budget || v.free - extra < storage.reserve(v)
    }

    suspend fun trim(urgent: Boolean = false): Unit = mutex.withLock {
        var steps = 0
        while (overBudget()) {
            val freed = if (tiles.segmentCount > MIN_SEGMENTS) tiles.dropOldestSegment() else 0L
            val got = if (freed > 0) freed else blobs.evictOne()
            if (got <= 0) {
                // ultima spiaggia: anche i segmenti minimi
                if (tiles.segmentCount > 1 && tiles.dropOldestSegment() > 0) continue
                Log.w(TAG, "nothing left to evict; volume still over budget")
                break
            }
            steps++
            if (!urgent && steps % 4 == 0) delay(50) // lascia respirare il disco mentre si naviga
        }
        blobs.flush()
        if (steps > 0) Log.i(TAG, "trim done: $steps steps, usage=${usage() shr 20} MB")
        Unit
    }

    /** Prova a fare spazio per un download di [bytes]. false se proprio non ci sta. */
    suspend fun makeRoom(bytes: Long): Boolean {
        mutex.withLock {
            var guard = 0
            while (overBudget(bytes) && guard++ < 10_000) {
                val freed = if (tiles.segmentCount > MIN_SEGMENTS) tiles.dropOldestSegment() else blobs.evictOne()
                if (freed <= 0) break
            }
        }
        return !overBudget(bytes)
    }

    companion object {
        private const val TAG = "SpaceGovernor"
        private const val WRITE_CHECK = 16L shl 20
        private const val MIN_SEGMENTS = 3
    }
}
