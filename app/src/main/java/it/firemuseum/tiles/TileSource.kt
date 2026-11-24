package it.firemuseum.tiles

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.max

/** Dati grezzi di un tassello: byte già codificati (rete/zip) o bitmap appena decodificato. */
sealed class TileData {
    class Bytes(val bytes: ByteArray) : TileData()
    class Bmp(val bitmap: Bitmap) : TileData()
}

/**
 * Piramide di tasselli con griglia uniforme:
 *  - livello k = sotto-campionamento 2^k (k=0 piena risoluzione);
 *  - al livello k l'immagine misura ceil(W/2^k) × ceil(H/2^k) e il tassello (x,y) copre
 *    [x*ts, (x+1)*ts) in pixel di quel livello.
 * La stessa griglia vale per IIIF, Deep Zoom (DZI) e decodifica locale, quindi il visualizzatore
 * non sa né gli interessa da dove arrivano i pixel.
 */
abstract class TileSource {
    abstract val id: String
    abstract val width: Int
    abstract val height: Int
    open val tileSize: Int = 512

    /** Livello più grossolano: l'intera immagine sta in un tassello. */
    val maxK: Int by lazy {
        var k = 0
        while (ceil(max(width, height) / (1 shl k).toDouble()) > tileSize) k++
        k
    }

    /** Livello più fine disponibile ADESSO (può scendere quando si scarica l'originale). */
    open fun minK(): Int = 0

    /** Salvare i byte nella cache segmentata? (no per sorgenti già locali e veloci) */
    open val diskCacheable: Boolean = true

    /** Se il tassello arriva come bitmap, ricodificarlo in JPEG nella cache (decodifica costosa)? */
    open fun encodeToCache(k: Int): Boolean = false

    fun levelW(k: Int) = ceil(width / (1 shl k).toDouble()).toInt()
    fun levelH(k: Int) = ceil(height / (1 shl k).toDouble()).toInt()
    fun cols(k: Int) = ceil(levelW(k) / tileSize.toDouble()).toInt()
    fun rows(k: Int) = ceil(levelH(k) / tileSize.toDouble()).toInt()

    fun key(k: Int, x: Int, y: Int) = "$id/$k/$x/$y"

    /** Rettangolo del tassello in pixel dell'immagine a piena risoluzione. */
    fun imageRect(k: Int, x: Int, y: Int): Rect {
        val d = 1 shl k
        val x0 = x * tileSize * d
        val y0 = y * tileSize * d
        return Rect(x0, y0, minOf(width, x0 + tileSize * d), minOf(height, y0 + tileSize * d))
    }

    /** Larghezza/altezza attese del contenuto utile del tassello (pixel del livello). */
    fun contentSize(k: Int, x: Int, y: Int): Pair<Int, Int> =
        minOf(tileSize, levelW(k) - x * tileSize) to minOf(tileSize, levelH(k) - y * tileSize)

    /** Parte del bitmap da disegnare (DZI: esclude l'overlap). */
    open fun crop(k: Int, x: Int, y: Int, bmpW: Int, bmpH: Int): Rect = Rect(0, 0, bmpW, bmpH)

    abstract suspend fun load(k: Int, x: Int, y: Int): TileData?

    open fun close() {}

    /** Breve descrizione per l'overlay info ("IIIF Rijksmuseum", "Commons, anteprima 3840 px"…). */
    open fun describe(): String = javaClass.simpleName
}
