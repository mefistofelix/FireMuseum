package it.firemuseum.tiles

import android.graphics.Rect
import it.firemuseum.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max

/**
 * Deep Zoom Image (formato di libvips `dzsave`, OpenSeadragon).
 * Tre modalità:
 *  - HTTP: piramide servita dal PC in LAN (python -m http.server) o da un sito;
 *  - cartella sul disco esterno;
 *  - un unico .zip "stored" (dzsave --container zip): consigliato su USB, evita 100.000 file piccoli.
 */
class DziSource private constructor(
    override val id: String,
    override val width: Int,
    override val height: Int,
    override val tileSize: Int,
    private val overlap: Int,
    private val format: String,
    private val fetch: suspend (String) -> ByteArray?,
    private val local: Boolean,
    private val closer: () -> Unit,
    private val label: String,
) : TileSource() {

    // Livello DZI massimo: l'immagine piena sta a livello ceil(log2(max(W,H)))
    private val maxLevel = ceil(ln(max(width, height).toDouble()) / ln(2.0)).toInt()

    override val diskCacheable: Boolean get() = !local

    override suspend fun load(k: Int, x: Int, y: Int): TileData? {
        val level = maxLevel - k
        if (level < 0) return null
        return fetch("$level/${x}_$y.$format")?.let { TileData.Bytes(it) }
    }

    override fun crop(k: Int, x: Int, y: Int, bmpW: Int, bmpH: Int): Rect {
        val ox = if (x > 0) overlap else 0
        val oy = if (y > 0) overlap else 0
        val (cw, ch) = contentSize(k, x, y)
        return Rect(ox, oy, minOf(bmpW, ox + cw), minOf(bmpH, oy + ch))
    }

    override fun close() = closer()
    override fun describe() = label

    companion object {
        private fun parse(xml: String): IntArray? {
            fun attr(name: String) = Regex("$name=\"([^\"]+)\"").find(xml)?.groupValues?.get(1)
            val w = attr("Width")?.toIntOrNull() ?: return null
            val h = attr("Height")?.toIntOrNull() ?: return null
            val ts = attr("TileSize")?.toIntOrNull() ?: 254
            val ov = attr("Overlap")?.toIntOrNull() ?: 0
            return intArrayOf(w, h, ts, ov)
        }
        private fun fmt(xml: String) = Regex("Format=\"([^\"]+)\"").find(xml)?.groupValues?.get(1) ?: "jpg"

        suspend fun openHttp(net: Net, dziUrl: String): DziSource? {
            val xml = net.bytes(dziUrl)?.toString(Charsets.UTF_8) ?: return null
            val p = parse(xml) ?: return null
            val base = dziUrl.substringBeforeLast(".dzi") + "_files/"
            return DziSource("dzi:" + dziUrl.hashCode().toUInt().toString(36), p[0], p[1], p[2], p[3], fmt(xml),
                { rel -> net.bytes(base + rel, retries = 2) }, local = false, closer = {}, label = "Deep Zoom · rete")
        }

        suspend fun openDir(dzi: File): DziSource? = withContext(Dispatchers.IO) {
            val xml = runCatching { dzi.readText() }.getOrNull() ?: return@withContext null
            val p = parse(xml) ?: return@withContext null
            val base = File(dzi.parentFile, dzi.nameWithoutExtension + "_files")
            DziSource("dzd:" + dzi.path.hashCode().toUInt().toString(36), p[0], p[1], p[2], p[3], fmt(xml),
                { rel -> withContext(Dispatchers.IO) { File(base, rel).takeIf { it.exists() }?.readBytes() } },
                local = true, closer = {}, label = "Deep Zoom · disco")
        }

        /** Piramide zip remota letta a intervalli (archivio MEGA): i tasselli finiscono nella cache su USB. */
        suspend fun openRangeZip(id: String, zip: RangeZip, label: String): DziSource? {
            val dziName = zip.entries.keys.firstOrNull { it.endsWith(".dzi") } ?: return null
            val xml = zip.bytes(dziName)?.toString(Charsets.UTF_8) ?: return null
            val p = parse(xml) ?: return null
            val prefix = dziName.removeSuffix(".dzi") + "_files/"
            return DziSource(id, p[0], p[1], p[2], p[3], fmt(xml), { rel -> zip.bytes(prefix + rel) },
                local = false, closer = {}, label = label)
        }

        /** .zip prodotto da `vips dzsave img out.zip` (compressione 0 = accesso casuale veloce). */
        suspend fun openZip(zip: File): DziSource? = withContext(Dispatchers.IO) {
            val zf = runCatching { ZipFile(zip) }.getOrNull() ?: return@withContext null
            val dziEntry = zf.entries().asSequence().firstOrNull { it.name.endsWith(".dzi") }
                ?: run { zf.close(); return@withContext null }
            val xml = zf.getInputStream(dziEntry).use { it.readBytes().toString(Charsets.UTF_8) }
            val p = parse(xml) ?: run { zf.close(); return@withContext null }
            val prefix = dziEntry.name.removeSuffix(".dzi") + "_files/"
            val lock = Any()
            DziSource("dzz:" + zip.path.hashCode().toUInt().toString(36), p[0], p[1], p[2], p[3], fmt(xml),
                { rel ->
                    withContext(Dispatchers.IO) {
                        synchronized(lock) {
                            zf.getEntry(prefix + rel)?.let { e -> zf.getInputStream(e).use { it.readBytes() } }
                        }
                    }
                }, local = true, closer = { runCatching { zf.close() } }, label = "Deep Zoom · zip su disco")
        }
    }
}
