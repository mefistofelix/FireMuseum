package it.firemuseum.catalog

import android.util.Log
import it.firemuseum.App
import it.firemuseum.model.Artwork
import it.firemuseum.model.NRect
import it.firemuseum.model.Portal
import it.firemuseum.model.Source
import it.firemuseum.model.Target
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Arricchimento da Wikidata (metadati + bersagli + finestre), con cache JSON sul disco esterno.
 *
 * Il tesoro: il qualificatore P2677 "posizione relativa nell'immagine" sulle dichiarazioni
 * P180 "raffigura". A novembre 2026 ~1.700 dipinti hanno ~4.500 regioni annotate a mano dalla
 * comunità (strumento "Wikidata Image Positions"): diventano obiettivi del gioco già pronti.
 * Se l'oggetto raffigurato è a sua volta un dipinto con immagine (quadri-nel-quadro di Panini,
 * Zoffany, Morse, Teniers…), la regione diventa una FINESTRA in cui entrare.
 */
class Wikidata(private val app: App) {

    private fun filePathUri(file: String): String =
        "http://commons.wikimedia.org/wiki/Special:FilePath/" +
            URLEncoder.encode(file.replace('_', ' '), "UTF-8").replace("+", "%20")

    /** QID dell'opera a partire dal nome file Commons (P18). */
    suspend fun qidForFile(file: String): String? {
        val cacheName = "wdq_" + file.hashCode().toUInt().toString(36) + ".txt"
        app.blobs.file(cacheName)?.let { return it.readText().ifBlank { null } }
        val q = "SELECT ?item WHERE { ?item wdt:P18 <${filePathUri(file)}> } LIMIT 1"
        val r = app.net.sparql(q) ?: return null
        val qid = r.optJSONObject("results")?.optJSONArray("bindings")?.optJSONObject(0)
            ?.optJSONObject("item")?.optString("value")?.substringAfterLast('/')
        app.blobs.putSmall(cacheName, (qid ?: "").toByteArray())
        return qid
    }

    /** Nome file Commons (P18) di un elemento Wikidata. */
    suspend fun imageFile(qid: String): String? {
        val cacheName = "wdi_$qid.txt"
        app.blobs.file(cacheName)?.let { return it.readText().ifBlank { null } }
        val o = app.net.json("https://www.wikidata.org/wiki/Special:EntityData/$qid.json") ?: return null
        val f = o.optJSONObject("entities")?.optJSONObject(qid)?.optJSONObject("claims")?.optJSONArray("P18")
            ?.optJSONObject(0)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value")
        app.blobs.putSmall(cacheName, (f ?: "").toByteArray())
        return f?.replace(' ', '_')
    }

    suspend fun enrich(a: Artwork) {
        if (a.enriched) return
        try {
            if (a.qid == null) {
                val file = (a.source as? Source.Commons)?.file
                    ?: (a.source as? Source.Wikidata)?.qid?.let { a.qid = it; null }
                if (file != null) a.qid = qidForFile(file)
            }
            val qid = a.qid ?: return
            val cacheName = "wde_$qid.json"
            val cached = app.blobs.file(cacheName)?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            val data = cached ?: fetch(qid)?.also { app.blobs.putSmall(cacheName, it.toString().toByteArray()) } ?: return
            apply(a, data)
            a.enriched = true
        } catch (t: Throwable) {
            Log.w(TAG, "enrich ${a.id}: ${t.message}")
        }
    }

    private suspend fun fetch(qid: String): JSONObject? {
        val meta = app.net.sparql(
            """
            SELECT ?creatorLabel ?inception ?movementLabel ?genreLabel ?collectionLabel ?locationLabel ?materialLabel ?desc ?h ?w WHERE {
              BIND(wd:$qid AS ?item)
              OPTIONAL { ?item wdt:P170 ?creator }
              OPTIONAL { ?item wdt:P571 ?inception }
              OPTIONAL { ?item wdt:P135 ?movement }
              OPTIONAL { ?item wdt:P136 ?genre }
              OPTIONAL { ?item wdt:P195 ?collection }
              OPTIONAL { ?item wdt:P276 ?location }
              OPTIONAL { ?item wdt:P186 ?material }
              OPTIONAL { ?item wdt:P2048 ?h }
              OPTIONAL { ?item wdt:P2049 ?w }
              OPTIONAL { ?item schema:description ?desc FILTER(LANG(?desc) = "it") }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "it,en,nl,de,fr". }
            } LIMIT 80
            """.trimIndent()
        ) ?: return null
        val regions = app.net.sparql(
            """
            SELECT ?d ?dLabel ?pos ?img ?isArt WHERE {
              wd:$qid p:P180 ?st . ?st ps:P180 ?d ; pq:P2677 ?pos .
              OPTIONAL { ?d wdt:P18 ?img }
              BIND(EXISTS { ?d wdt:P31/wdt:P279* wd:Q4502142 } AS ?isArt)
              SERVICE wikibase:label { bd:serviceParam wikibase:language "it,en,nl,de,fr". }
            }
            """.trimIndent()
        )
        return JSONObject().put("meta", meta).put("regions", regions ?: JSONObject())
    }

    private fun values(res: JSONObject?, v: String): List<String> {
        val b = res?.optJSONObject("results")?.optJSONArray("bindings") ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until b.length()) b.optJSONObject(i)?.optJSONObject(v)?.optString("value")?.takeIf { it.isNotBlank() }?.let { out += it }
        return out.toList()
    }

    private fun apply(a: Artwork, data: JSONObject) {
        val m = data.optJSONObject("meta")
        fun join(v: String) = values(m, v).filterNot { it.matches(Regex("Q\\d+")) }.take(3).joinToString(", ").ifBlank { null }
        if (a.author == null) a.author = join("creatorLabel")
        if (a.year == null) a.year = values(m, "inception").firstOrNull()?.let { Regex("-?\\d{3,4}").find(it)?.value }
        if (a.style == null) a.style = join("movementLabel")
        if (a.genre == null) a.genre = join("genreLabel")
        if (a.museum == null) a.museum = join("collectionLabel") ?: join("locationLabel")
        if (a.description == null) a.description = values(m, "desc").firstOrNull()
        val material = join("materialLabel")
        val h = values(m, "h").firstOrNull(); val w = values(m, "w").firstOrNull()
        val extra = listOfNotNull(material, if (h != null && w != null) "${h.take(6)} × ${w.take(6)} cm" else null)
        if (extra.isNotEmpty()) a.description = listOfNotNull(a.description, extra.joinToString(" · ")).joinToString("\n")
        a.year?.toIntOrNull()?.let { y -> a.tags = (a.tags + centuryTag(y)).distinct() }

        val b = data.optJSONObject("regions")?.optJSONObject("results")?.optJSONArray("bindings") ?: return
        val existing = a.targets.map { it.label.lowercase() }.toMutableSet()
        for (i in 0 until b.length()) {
            val r = b.optJSONObject(i) ?: continue
            val label = r.optJSONObject("dLabel")?.optString("value") ?: continue
            val rect = r.optJSONObject("pos")?.optString("value")?.let { NRect.fromPct(it) } ?: continue
            val dq = r.optJSONObject("d")?.optString("value")?.substringAfterLast('/') ?: continue
            if (label.matches(Regex("Q\\d+"))) continue
            val isArt = r.optJSONObject("isArt")?.optString("value") == "true"
            val hasImg = r.optJSONObject("img") != null
            if (isArt && hasImg) {
                if (a.portals.none { it.to == "wikidata:$dq" }) a.portals += Portal(rect, "wikidata:$dq", label)
            }
            if (existing.add(label.lowercase() + rect.cx)) {
                a.targets += Target(label, rect, null, difficulty = if (rect.w * rect.h < 0.002f) 3 else 2, origin = "wikidata")
            }
        }
    }

    companion object {
        private const val TAG = "Wikidata"
        fun centuryTag(y: Int): String {
            val c = (y - 1) / 100 + 1
            val roman = listOf("", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII", "XIII", "XIV", "XV",
                "XVI", "XVII", "XVIII", "XIX", "XX", "XXI")
            return if (c in 1..21) "${roman[c]} secolo" else "$c secolo"
        }
    }
}
