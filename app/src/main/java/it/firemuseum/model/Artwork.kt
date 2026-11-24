package it.firemuseum.model

import org.json.JSONArray
import org.json.JSONObject

/** Rettangolo in coordinate normalizzate dell'immagine (0..1). */
data class NRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val cx get() = x + w / 2
    val cy get() = y + h / 2
    fun contains(px: Float, py: Float, tol: Float = 0f) =
        px >= x - tol && px <= x + w + tol && py >= y - tol && py <= y + h + tol

    companion object {
        /** Formato IIIF/Wikidata P2677: "pct:x,y,w,h" (percentuali). */
        fun fromPct(s: String): NRect? {
            val v = s.removePrefix("pct:").split(",").mapNotNull { it.trim().toFloatOrNull() }
            if (v.size != 4) return null
            return NRect(v[0] / 100f, v[1] / 100f, v[2] / 100f, v[3] / 100f)
        }
        fun from(o: JSONObject) = NRect(
            o.optDouble("x").toFloat(), o.optDouble("y").toFloat(),
            o.optDouble("w").toFloat(), o.optDouble("h").toFloat()
        )
    }
}

/** Obiettivo del gioco "cerca e trova". */
data class Target(
    val label: String,
    val rect: NRect,
    val hint: String? = null,
    val difficulty: Int = 2,
    val origin: String = "catalog", // catalog | wikidata | user
)

/** Finestra/portale: zoomando dentro [rect] si "entra" nell'opera [to] (id catalogo, commons:File, wikidata:Q…). */
data class Portal(val rect: NRect, val to: String, val label: String? = null)

/** Sorgente dei pixel. */
sealed class Source {
    data class Commons(val file: String) : Source()
    data class Iiif(val info: String) : Source()
    data class Dzi(val url: String) : Source()           // http(s)://…/x.dzi, oppure percorso relativo sul disco (.dzi / .zip)
    data class Local(val path: String) : Source()        // JPEG/PNG sul disco esterno
    data class Wikidata(val qid: String) : Source()      // risolto a runtime tramite P18

    fun key(): String = when (this) {
        is Commons -> "c:" + file
        is Iiif -> "i:" + info
        is Dzi -> "d:" + url
        is Local -> "l:" + path
        is Wikidata -> "w:" + qid
    }

    fun toJson(): JSONObject = when (this) {
        is Commons -> JSONObject().put("type", "commons").put("file", file)
        is Iiif -> JSONObject().put("type", "iiif").put("info", info)
        is Dzi -> JSONObject().put("type", "dzi").put("url", url)
        is Local -> JSONObject().put("type", "local").put("path", path)
        is Wikidata -> JSONObject().put("type", "wikidata").put("qid", qid)
    }

    companion object {
        fun from(o: JSONObject): Source? = when (o.optString("type")) {
            "commons" -> Commons(o.getString("file").replace(' ', '_').removePrefix("File:"))
            "iiif" -> Iiif(o.getString("info"))
            "dzi" -> Dzi(o.getString("url"))
            "local" -> Local(o.getString("path"))
            "wikidata" -> Wikidata(o.getString("qid"))
            else -> null
        }

        /** "commons:File.jpg", "wikidata:Q123", "iiif:https://…/info.json" oppure id catalogo (null). */
        fun fromRef(ref: String): Source? = when {
            ref.startsWith("commons:") -> Commons(ref.removePrefix("commons:").replace(' ', '_'))
            ref.startsWith("wikidata:") -> Wikidata(ref.removePrefix("wikidata:"))
            ref.startsWith("iiif:") -> Iiif(ref.removePrefix("iiif:"))
            ref.startsWith("dzi:") -> Dzi(ref.removePrefix("dzi:"))
            else -> null
        }
    }
}

data class Artwork(
    val id: String,
    var title: String,
    var titleOrig: String? = null,
    var author: String? = null,
    var year: String? = null,
    var style: String? = null,
    var genre: String? = null,
    var museum: String? = null,
    var description: String? = null,
    var license: String? = null,
    var tags: List<String> = emptyList(),
    var kidSafe: Boolean = true,
    var warning: String? = null,
    var width: Int = 0,
    var height: Int = 0,
    var bytes: Long = 0,
    val source: Source,
    var qid: String? = null,
    var pageUrl: String? = null,
    val targets: MutableList<Target> = mutableListOf(),
    val portals: MutableList<Portal> = mutableListOf(),
    var enriched: Boolean = false,
    /** Nome della piramide zip nell'archivio MEGA (tools/mega_pack.py), se c'è. */
    var mirror: String? = null,
    /** Da dove leggere l'opera: "mega" (archivio, con [mirror]) oppure "origin" (sorgente originale). */
    var host: String? = null,
    var mirrorBytes: Long = 0,
) {
    val megapixels: Double get() = width.toDouble() * height / 1e6
    val maxSide: Int get() = maxOf(width, height)

    /** Testo normalizzato su cui si fa la ricerca libera. */
    val haystack: String by lazy { searchText() }
    fun searchText() = listOfNotNull(title, titleOrig, author, year, style, genre, museum, description, license, *tags.toTypedArray())
        .joinToString(" ").lowercase().let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD) }
        .replace(Regex("\\p{M}"), "")

    fun resolutionBadge(): String = when {
        maxSide >= 20000 -> "GIGA ${maxSide / 1000}k"
        maxSide >= 8000 -> "${maxSide / 1000}k"
        maxSide > 0 -> "${maxSide}px"
        else -> "?"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); putOpt("titleOrig", titleOrig); putOpt("author", author)
        putOpt("year", year); putOpt("style", style); putOpt("genre", genre); putOpt("museum", museum)
        putOpt("description", description); putOpt("license", license); put("tags", JSONArray(tags))
        put("kidSafe", kidSafe); putOpt("warning", warning); put("width", width); put("height", height)
        put("bytes", bytes); put("source", source.toJson()); putOpt("mirror", mirror); putOpt("host", host); if (mirrorBytes > 0) put("mirrorBytes", mirrorBytes); putOpt("qid", qid); putOpt("pageUrl", pageUrl)
        put("targets", JSONArray(targets.filter { it.origin == "catalog" }.map { t ->
            JSONObject().put("label", t.label).put("x", t.rect.x).put("y", t.rect.y).put("w", t.rect.w)
                .put("h", t.rect.h).putOpt("hint", t.hint).put("difficulty", t.difficulty)
        }))
        put("portals", JSONArray(portals.map { p ->
            JSONObject().put("x", p.rect.x).put("y", p.rect.y).put("w", p.rect.w).put("h", p.rect.h)
                .put("to", p.to).putOpt("label", p.label)
        }))
    }

    companion object {
        fun from(o: JSONObject): Artwork? {
            val src = o.optJSONObject("source")?.let { Source.from(it) } ?: return null
            fun s(k: String) = o.optString(k).takeIf { it.isNotBlank() && it != "null" }
            val a = Artwork(
                id = o.getString("id"), title = s("title") ?: o.getString("id"), titleOrig = s("titleOrig"),
                author = s("author"), year = s("year"), style = s("style"), genre = s("genre"),
                museum = s("museum"), description = s("description"), license = s("license"),
                tags = o.optJSONArray("tags")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList(),
                kidSafe = o.optBoolean("kidSafe", true), warning = s("warning"),
                width = o.optInt("width"), height = o.optInt("height"), bytes = o.optLong("bytes"),
                source = src, qid = s("qid"), pageUrl = s("pageUrl"),
                mirror = s("mirror"), host = s("host"), mirrorBytes = o.optLong("mirrorBytes"),
            )
            o.optJSONArray("targets")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    a.targets += Target(t.getString("label"), NRect.from(t), t.optString("hint").ifBlank { null },
                        t.optInt("difficulty", 2), t.optString("origin", "catalog"))
                }
            }
            o.optJSONArray("portals")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    a.portals += Portal(NRect.from(p), p.getString("to"), p.optString("label").ifBlank { null })
                }
            }
            return a
        }
    }
}
