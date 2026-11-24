package it.firemuseum.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.core.content.ContextCompat
import it.firemuseum.ui.Settings
import java.io.File

/**
 * Sceglie DOVE salvare: sempre il disco esterno (USB / SD) se c'è, mai la memoria interna
 * se non come piccolo ripiego temporaneo.
 *
 * Su Fire OS un disco USB formattato come "memoria portatile" compare in
 * getExternalFilesDirs() come volume rimovibile (es. /storage/988E-E016/Android/data/it.firemuseum/files).
 * Nessun permesso serve per la cartella privata dell'app su quel volume.
 */
class StorageManager(private val ctx: Context, private val settings: Settings) {

    data class Volume(val root: File, val removable: Boolean, val internalFallback: Boolean) {
        val free: Long get() = runCatching { StatFs(root.path).availableBytes }.getOrDefault(0L)
        val total: Long get() = runCatching { StatFs(root.path).totalBytes }.getOrDefault(0L)
    }

    @Volatile var current: Volume? = null; private set
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners += l } }

    init { current = pick() }

    fun start() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(ctx, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                Log.i(TAG, "media event ${i.action} ${i.data}")
                refresh()
            }
        }, f, ContextCompat.RECEIVER_EXPORTED)
    }

    /** Rivaluta i volumi; se cambia il volume attivo avvisa cache/blob che devono riaprirsi. */
    fun refresh() {
        val n = pick()
        if (n?.root != current?.root) {
            current = n
            Log.i(TAG, "storage switched to ${n?.root}")
            synchronized(listeners) { listeners.toList() }.forEach { runCatching { it() } }
        }
    }

    private fun pick(): Volume? {
        val dirs = ctx.getExternalFilesDirs(null).filterNotNull()
        val external = dirs.mapNotNull { d ->
            val mounted = runCatching { Environment.getExternalStorageState(d) == Environment.MEDIA_MOUNTED }.getOrDefault(false)
            val removable = runCatching { Environment.isExternalStorageRemovable(d) }.getOrDefault(false)
            if (mounted && removable && (d.exists() || d.mkdirs())) Volume(d, true, false) else null
        }.maxByOrNull { it.free }
        if (external != null) return external
        if (!settings.allowInternalFallback) return null
        val d = File(ctx.cacheDir, "fallback").apply { mkdirs() }
        return Volume(d, removable = false, internalFallback = true)
    }

    fun dir(name: String): File? = current?.root?.let { File(it, name).apply { mkdirs() } }

    /** Spazio da lasciare sempre libero sul volume. */
    fun reserve(v: Volume): Long {
        if (v.internalFallback) return 400L shl 20 // memoria interna: tieni sempre ≥ 400 MB al sistema
        val s = settings.reserveBytes
        return if (s > 0) s else maxOf(1L shl 30, v.total / 20)
    }

    /** Budget massimo per la cache (tasselli + originali) sul volume attivo. */
    fun budget(currentUsage: Long): Long {
        val v = current ?: return 0
        if (v.internalFallback) return settings.internalFallbackBytes
        val user = settings.tileCacheMaxBytes
        val auto = ((v.free + currentUsage - reserve(v)) * 0.9).toLong()
        return if (user > 0) minOf(user, auto) else auto
    }

    fun describe(): String {
        val v = current ?: return "Nessun archivio: solo memoria RAM"
        val gb = { b: Long -> "%.1f GB".format(b / 1e9) }
        return if (v.internalFallback) "⚠ Disco esterno assente: cache interna ridotta"
        else "Disco esterno: ${gb(v.free)} liberi su ${gb(v.total)}"
    }

    companion object { private const val TAG = "Storage" }
}
