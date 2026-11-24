package it.firemuseum.tiles

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * Lettore zip ad accesso casuale sopra una funzione "leggi byte [a, b)" (file remoto a intervalli).
 * Legge solo la directory centrale (in coda al file), poi ogni voce con una sola richiesta.
 * Supporta zip64 e le voci "stored" (quelle di `vips dzsave --container zip`) o deflate.
 */
class RangeZip private constructor(
    private val read: suspend (Long, Long) -> ByteArray,
    val entries: Map<String, Entry>,
) {
    class Entry(val name: String, val offset: Long, val compSize: Long, val size: Long, val method: Int, val extraLen: Int)

    suspend fun bytes(name: String): ByteArray? {
        val e = entries[name] ?: return null
        // intestazione locale (30 + nome + extra) + dati in una richiesta; l'extra locale può differire
        val guess = 30L + e.name.toByteArray().size + e.extraLen + 64
        var buf = read(e.offset, e.offset + guess + e.compSize)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getInt(0) != 0x04034b50) throw IOException("zip: intestazione locale errata per $name")
        val start = 30 + (bb.getShort(26).toInt() and 0xffff) + (bb.getShort(28).toInt() and 0xffff)
        if (start + e.compSize > buf.size) buf = read(e.offset, e.offset + start + e.compSize)
        val data = buf.copyOfRange(start, (start + e.compSize).toInt())
        return when (e.method) {
            0 -> data
            8 -> Inflater(true).run {
                setInput(data); val out = ByteArray(e.size.toInt()); inflate(out); end(); out
            }
            else -> throw IOException("zip: metodo ${e.method} non supportato")
        }
    }

    companion object {
        /** [cached] = directory centrale già scaricata (si salva su disco per non rileggerla). */
        suspend fun open(size: Long, read: suspend (Long, Long) -> ByteArray, cached: ByteArray? = null,
                         onDirectory: (ByteArray) -> Unit = {}): RangeZip {
            val cd = cached ?: run {
                val tailStart = maxOf(0L, size - 65_558)
                val tail = read(tailStart, size)
                val tb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
                var eocd = -1
                for (i in tail.size - 22 downTo 0) if (tb.getInt(i) == 0x06054b50) { eocd = i; break }
                if (eocd < 0) throw IOException("zip: fine directory non trovata")
                var cdSize = tb.getInt(eocd + 12).toLong() and 0xffffffffL
                var cdOff = tb.getInt(eocd + 16).toLong() and 0xffffffffL
                if (cdOff == 0xffffffffL || cdSize == 0xffffffffL) {        // zip64
                    val loc = eocd - 20
                    if (loc < 0 || tb.getInt(loc) != 0x07064b50) throw IOException("zip64: locatore assente")
                    val z64 = tb.getLong(loc + 8)
                    val rec = if (z64 >= tailStart) ByteBuffer.wrap(tail, (z64 - tailStart).toInt(), 56)
                    else ByteBuffer.wrap(read(z64, z64 + 56))
                    rec.order(ByteOrder.LITTLE_ENDIAN)
                    val p = rec.position()
                    cdSize = rec.getLong(p + 40); cdOff = rec.getLong(p + 48)
                }
                val bytes = if (cdOff >= tailStart && cdOff + cdSize <= size) tail.copyOfRange((cdOff - tailStart).toInt(), (cdOff - tailStart + cdSize).toInt())
                else read(cdOff, cdOff + cdSize)
                onDirectory(bytes)
                bytes
            }
            return RangeZip(read, parse(cd))
        }

        private fun parse(cd: ByteArray): Map<String, Entry> {
            val b = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)
            val out = HashMap<String, Entry>()
            var p = 0
            while (p + 46 <= cd.size && b.getInt(p) == 0x02014b50) {
                val method = b.getShort(p + 10).toInt() and 0xffff
                var comp = b.getInt(p + 20).toLong() and 0xffffffffL
                var size = b.getInt(p + 24).toLong() and 0xffffffffL
                val nl = b.getShort(p + 28).toInt() and 0xffff
                val el = b.getShort(p + 30).toInt() and 0xffff
                val cl = b.getShort(p + 32).toInt() and 0xffff
                var off = b.getInt(p + 42).toLong() and 0xffffffffL
                val name = String(cd, p + 46, nl, Charsets.UTF_8)
                var q = p + 46 + nl
                val qe = q + el
                while (q + 4 <= qe) {                                         // extra zip64 (0x0001)
                    val id = b.getShort(q).toInt() and 0xffff
                    val len = b.getShort(q + 2).toInt() and 0xffff
                    if (id == 1) {
                        var r = q + 4
                        if (size == 0xffffffffL) { size = b.getLong(r); r += 8 }
                        if (comp == 0xffffffffL) { comp = b.getLong(r); r += 8 }
                        if (off == 0xffffffffL) { off = b.getLong(r) }
                    }
                    q += 4 + len
                }
                out[name] = Entry(name, off, comp, size, method, el)
                p += 46 + nl + el + cl
            }
            return out
        }
    }
}
