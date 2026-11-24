package it.firemuseum.tiles

import it.firemuseum.net.Net
import org.json.JSONObject

/**
 * IIIF Image API 2.x / 3.x (Rijksmuseum/Micrio, National Gallery of Art, Getty, Yale, Wellcome,
 * biblioteche, mappe…). Il server ritaglia e scala per noi: nessun file enorme in locale.
 */
class IiifSource(
    private val net: Net,
    private val base: String,
    override val width: Int,
    override val height: Int,
    override val tileSize: Int,
    private val version: Int,
    private val format: String,
    private val maxWidth: Int,
) : TileSource() {

    override val id: String = "iiif:" + base.hashCode().toUInt().toString(36)

    override fun minK(): Int {
        if (maxWidth <= 0 || maxWidth >= width) return 0
        // il server limita la dimensione massima servita: non chiedere livelli più fini
        var k = 0
        while ((width shr k) > maxWidth) k++
        return k
    }

    override suspend fun load(k: Int, x: Int, y: Int): TileData? {
        val r = imageRect(k, x, y)
        if (r.width() <= 0 || r.height() <= 0) return null
        val (cw, ch) = contentSize(k, x, y)
        val region = if (r.left == 0 && r.top == 0 && r.width() == width && r.height() == height) "full"
            else "${r.left},${r.top},${r.width()},${r.height()}"
        val size = if (version >= 3) "$cw,$ch" else "$cw,"
        val url = "$base/$region/$size/0/default.$format"
        return net.bytes(url)?.let { TileData.Bytes(it) }
    }

    override fun describe() = "IIIF v$version · ${java.net.URI(base).host}"

    companion object {
        /** Legge info.json e costruisce la sorgente. */
        suspend fun open(net: Net, infoUrl: String): IiifSource? {
            val o: JSONObject = net.json(infoUrl) ?: return null
            val id = (o.optString("id").ifBlank { o.optString("@id") }).ifBlank { infoUrl.removeSuffix("/info.json") }
            val ctx = o.opt("@context")?.toString() ?: ""
            val version = if (ctx.contains("/3/") || o.optString("type") == "ImageService3") 3 else 2
            val tiles = o.optJSONArray("tiles")?.optJSONObject(0)
            val ts = tiles?.optInt("width", 512) ?: 512
            val formats = o.optJSONArray("formats")?.let { a -> List(a.length()) { a.getString(it) } }
                ?: (o.optJSONArray("profile")?.optJSONObject(1)?.optJSONArray("formats")?.let { a -> List(a.length()) { a.getString(it) } })
                ?: emptyList()
            val fmt = if (formats.isEmpty() || formats.contains("jpg")) "jpg" else formats.first()
            val maxW = o.optInt("maxWidth", 0).takeIf { it > 0 }
                ?: o.optJSONArray("profile")?.optJSONObject(1)?.optInt("maxWidth", 0) ?: 0
            return IiifSource(net, id.trimEnd('/'), o.getInt("width"), o.getInt("height"), ts.coerceIn(128, 1024), version, fmt, maxW)
        }
    }
}
