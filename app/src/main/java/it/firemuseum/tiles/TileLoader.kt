package it.firemuseum.tiles

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import it.firemuseum.App
import it.firemuseum.cache.CachedTile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Richiesta di un tassello con priorità (numero basso = prima). */
class TileReq(val src: TileSource, val k: Int, val x: Int, val y: Int, val priority: Float) {
    val key = src.key(k, x, y)
}

/**
 * Coda a priorità rinnovata a ogni fotogramma in cui la vista si muove:
 *  - ciò che non è più visibile esce dalla coda prima ancora di partire;
 *  - prima i livelli grossolani (mai "buchi neri"), poi i tasselli fini dal centro verso i bordi,
 *    poi l'anello di pre-caricamento e il livello successivo nella direzione dello zoom.
 * Pipeline: RAM -> cache segmentata su disco esterno -> sorgente (rete o decodifica locale).
 */
class TileLoader(private val app: App, private val scope: CoroutineScope) {

    private val lock = Any()
    private var pending: List<TileReq> = emptyList()
    private var cursor = 0
    private val inflight = HashSet<String>()
    private val failedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val main = Handler(Looper.getMainLooper())
    private val posted = AtomicBoolean(false)
    private val listeners = mutableListOf<() -> Unit>()

    init {
        repeat(WORKERS) { scope.launch(Dispatchers.IO) { worker() } }
    }

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }

    /** Sostituisce l'insieme dei tasselli desiderati. */
    fun submit(reqs: List<TileReq>) {
        val now = System.currentTimeMillis()
        val list = reqs.asSequence()
            .filter { app.memory[it.key] == null }
            .filter { r -> failedAt[r.key]?.let { now - it > RETRY_MS } ?: true }
            .distinctBy { it.key }
            .sortedBy { it.priority }
            .take(MAX_QUEUE)
            .toList()
        synchronized(lock) { pending = list; cursor = 0 }
        if (list.isNotEmpty()) signal.trySend(Unit)
    }

    private fun take(): TileReq? = synchronized(lock) {
        while (cursor < pending.size) {
            val r = pending[cursor++]
            if (r.key !in inflight && app.memory[r.key] == null) { inflight += r.key; return r }
        }
        null
    }

    private suspend fun worker() {
        while (true) {
            val r = take()
            if (r == null) { signal.receive(); continue }
            try {
                load(r)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(TAG, "tile ${r.key}: ${t.message}")
                synchronized(lock) { failedAt[r.key] = System.currentTimeMillis() }
            } finally {
                synchronized(lock) { inflight -= r.key }
                // ci sono altri lavori: sveglia gli altri worker
                signal.trySend(Unit)
            }
        }
    }

    private suspend fun load(r: TileReq) {
        val cfg = app.memory.config
        val src = r.src
        var bmp: Bitmap? = null
        if (src.diskCacheable || src.encodeToCache(r.k)) {
            app.tiles.get(r.key)?.let { bmp = decode(it, cfg) }
        }
        if (bmp == null) {
            when (val d = src.load(r.k, r.x, r.y)) {
                null -> { synchronized(lock) { failedAt[r.key] = System.currentTimeMillis() }; return }
                is TileData.Bytes -> {
                    bmp = decode(d.bytes, cfg) ?: return
                    if (src.diskCacheable) app.tiles.put(r.key, d.bytes)
                }
                is TileData.Bmp -> {
                    bmp = d.bitmap
                    if (src.encodeToCache(r.k)) {
                        val out = ByteArrayOutputStream(96 * 1024)
                        d.bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
                        app.tiles.put(r.key, out.toByteArray())
                    }
                }
            }
        }
        val b = bmp ?: return
        app.memory.put(r.key, CachedTile(b, src.crop(r.k, r.x, r.y, b.width, b.height)))
        notifyReady()
    }

    private fun decode(bytes: ByteArray, cfg: Bitmap.Config): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = cfg })

    private fun notifyReady() {
        if (posted.compareAndSet(false, true)) {
            main.postDelayed({
                posted.set(false)
                synchronized(listeners) { listeners.toList() }.forEach { it() }
            }, 16)
        }
    }

    companion object {
        private const val TAG = "TileLoader"
        private const val WORKERS = 6
        private const val MAX_QUEUE = 160
        private const val RETRY_MS = 15_000L
    }
}
