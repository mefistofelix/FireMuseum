package it.firemuseum.tiles

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Decodifica a regioni di un JPEG/PNG sul disco (BitmapRegionDecoder): non carica mai
 * l'immagine intera in RAM. [file] può essere un'anteprima più piccola dell'immagine logica
 * (fileW < width): allora si usa solo per i livelli k in cui non servirebbe ingrandire.
 *
 * Limite noto: i JPEG "progressive" non sono decodificabili a regioni senza leggere tutto;
 * per quelli (e per i file > ~150 MB, lenti sulle chiavette) conviene la piramide preparata sul PC.
 */
class LocalRegionSource(
    private val file: File,
    override val width: Int,
    override val height: Int,
    private val config: Bitmap.Config,
    override val id: String = "loc:" + file.path.hashCode().toUInt().toString(36),
) : TileSource() {

    private val decoders = ArrayDeque<BitmapRegionDecoder>()
    private val permits = Semaphore(2) // 2 decoder paralleli: i core A55/A53 sono pochi
    val fileW: Int
    val fileH: Int
    val progressive: Boolean = isProgressiveJpeg(file)

    init {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        fileW = o.outWidth; fileH = o.outHeight
    }

    override val diskCacheable = true
    /** Ricodifica in cache solo i livelli "costosi" (vicini alla piena risoluzione di un file enorme). */
    override fun encodeToCache(k: Int) = fileW.toLong() * fileH > 60_000_000L && k <= 3

    /** Livello minimo servibile senza ingrandire il file. */
    val fileMinK: Int = run {
        var k = 0
        while (k < 20 && (width shr k) > fileW * 1.01) k++
        k
    }

    override fun minK() = fileMinK

    private fun obtain(): BitmapRegionDecoder = synchronized(decoders) { decoders.removeFirstOrNull() }
        ?: @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(file.path, false)!!

    private fun release(d: BitmapRegionDecoder) = synchronized(decoders) { decoders.addLast(d) }

    override suspend fun load(k: Int, x: Int, y: Int): TileData? = permits.withPermit {
        withContext(Dispatchers.IO) {
            val r = imageRect(k, x, y)
            val sx = fileW.toDouble() / width
            val sy = fileH.toDouble() / height
            val fr = Rect((r.left * sx).toInt(), (r.top * sy).toInt(),
                minOf(fileW, kotlin.math.ceil(r.right * sx).toInt()), minOf(fileH, kotlin.math.ceil(r.bottom * sy).toInt()))
            if (fr.width() <= 0 || fr.height() <= 0) return@withContext null
            val (cw, ch) = contentSize(k, x, y)
            var sample = 1
            while (sample * 2 <= fr.width() / cw.coerceAtLeast(1)) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = config }
            val dec = try { obtain() } catch (t: Throwable) { Log.e(TAG, "decoder", t); return@withContext null }
            var decoded: Bitmap? = null
            try {
                decoded = dec.decodeRegion(fr, opts)
            } catch (t: Throwable) {
                Log.w(TAG, "decodeRegion failed ${t.message}")
            } finally {
                release(dec)
            }
            val bmp = decoded ?: return@withContext null
            val out = if (bmp.width != cw || bmp.height != ch) {
                Bitmap.createScaledBitmap(bmp, cw.coerceAtLeast(1), ch.coerceAtLeast(1), true).also { if (it !== bmp) bmp.recycle() }
            } else bmp
            TileData.Bmp(out)
        }
    }

    override fun close() = synchronized(decoders) { decoders.forEach { it.recycle() }; decoders.clear() }

    override fun describe() = "File locale ${fileW}×$fileH" + if (progressive) " (progressive!)" else ""

    companion object {
        private const val TAG = "LocalRegion"

        /** Cerca SOF2 (0xFFC2) prima di SOS: JPEG progressivo. */
        fun isProgressiveJpeg(f: File): Boolean = runCatching {
            RandomAccessFile(f, "r").use { raf ->
                if (raf.readUnsignedShort() != 0xFFD8) return false
                while (raf.filePointer < raf.length()) {
                    var b = raf.readUnsignedByte()
                    if (b != 0xFF) continue
                    do { b = raf.readUnsignedByte() } while (b == 0xFF)
                    when (b) {
                        0xC2 -> return true
                        0xC0, 0xC1, 0xDA -> return false
                        0xD8, 0x01, in 0xD0..0xD7 -> Unit
                        else -> { val len = raf.readUnsignedShort(); raf.seek(raf.filePointer + len - 2) }
                    }
                }
                false
            }
        }.getOrDefault(false)
    }
}
