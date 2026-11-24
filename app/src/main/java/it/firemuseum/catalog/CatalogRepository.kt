package it.firemuseum.catalog

import android.util.Log
import it.firemuseum.App
import it.firemuseum.model.Artwork
import it.firemuseum.model.NRect
import it.firemuseum.model.Source
import it.firemuseum.model.Target
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/** Filtri della griglia principale. */
enum class Filter(val label: String) {
    KIDS("👶 Bambini"), GIGA("🔍 ≥ 20.000 px"), GAME("🎯 Con obiettivi"), PORTALS("🪟 Con finestre"),
    FAVORITES("★ Preferiti"), OFFLINE("💾 Sul disco"), BRUEGEL("Bruegel"), BOSCH("Bosch"), BEUCKELAER("Beuckelaer"),
}

enum class Sort(val label: String) { CATALOG("Ordine catalogo"), RESOLUTION("Risoluzione"), AUTHOR("Autore"), YEAR("Anno") }

/**
 * Catalogo = assets/catalog.json (di serie) + qualsiasi <volume>/catalog/ *.json generato dal PC
 * (tools/build_catalog.py), uniti per id (quelli sul disco vincono). Obiettivi creati
 * dall'utente salvati a parte, piccoli, in filesDir/annotations (sopravvivono al cambio di disco).
 */
class CatalogRepository(private val app: App) {

    @Volatile var all: List<Artwork> = emptyList(); private set
    private val byId = HashMap<String, Artwork>()

    suspend fun load(): List<Artwork> = withContext(Dispatchers.IO) {
        val map = LinkedHashMap<String, Artwork>()
        fun ingest(text: String, origin: String) {
            runCatching {
                val root = JSONObject(text)
                val arr = root.optJSONArray("artworks") ?: JSONArray()
                for (i in 0 until arr.length()) Artwork.from(arr.getJSONObject(i))?.let { map[it.id] = it }
            }.onFailure { Log.w(TAG, "bad catalog $origin: ${it.message}") }
        }
        // catalog.json (scelte curate) poi catalog_*.json (generati da tools/build_catalog.py)
        (app.assets.list("")?.filter { it.startsWith("catalog") && it.endsWith(".json") }?.sorted() ?: listOf("catalog.json"))
            .forEach { name -> ingest(app.assets.open(name).bufferedReader().readText(), "assets/$name") }
        // archivio MEGA: catalogo completo con piramidi pronte (sostituisce le voci con lo stesso id)
        runCatching { app.mega.indexText() }.getOrNull()?.let { ingest(it, "mega/index.json") }
        app.storage.dir("catalog")?.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }?.forEach {
            ingest(it.readText(), it.name)
        }
        map.values.forEach { loadUserTargets(it) }
        synchronized(byId) { byId.clear(); byId.putAll(map) }
        all = map.values.toList()
        all
    }

    fun get(id: String): Artwork? = synchronized(byId) { byId[id] }

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

    fun search(query: String, filters: Set<Filter>, sort: Sort): List<Artwork> {
        val words = norm(query).split(Regex("\\s+")).filter { it.isNotBlank() }
        val fav = app.settings.favorites
        val kids = app.settings.kidsOnly || Filter.KIDS in filters
        var list = all.filter { a ->
            val hay = a.searchText()
            words.all { w -> hay.contains(w) } &&
                (!kids || a.kidSafe) &&
                (Filter.GIGA !in filters || a.maxSide >= 20000) &&
                (Filter.GAME !in filters || a.targets.isNotEmpty() || a.qid != null) &&
                (Filter.PORTALS !in filters || a.portals.isNotEmpty() || a.tags.contains("quadri nel quadro")) &&
                (Filter.FAVORITES !in filters || a.id in fav) &&
                (Filter.OFFLINE !in filters || isOffline(a)) &&
                (Filter.BRUEGEL !in filters || hay.contains("bruegel")) &&
                (Filter.BOSCH !in filters || hay.contains("bosch")) &&
                (Filter.BEUCKELAER !in filters || hay.contains("beuckelaer"))
        }
        list = when (sort) {
            Sort.CATALOG -> list
            Sort.RESOLUTION -> list.sortedByDescending { it.width.toLong() * it.height }
            Sort.AUTHOR -> list.sortedBy { it.author ?: "~" }
            Sort.YEAR -> list.sortedBy { it.year?.filter { c -> c.isDigit() }?.take(4)?.toIntOrNull() ?: 9999 }
        }
        return list
    }

    fun isOffline(a: Artwork): Boolean {
        val d = app.storage.dir("pyramids") ?: return false
        return File(d, "${a.id}.zip").exists() || File(d, "${a.id}/${a.id}.dzi").exists()
    }

    /** Risolve il riferimento di una finestra: id di catalogo, commons:…, wikidata:…, iiif:… */
    suspend fun resolveRef(ref: String, label: String?): Artwork? {
        get(ref)?.let { return it }
        val src = Source.fromRef(ref) ?: return null
        val id = "ref-" + ref.hashCode().toUInt().toString(36)
        get(id)?.let { return it }
        val a = Artwork(id = id, title = label ?: ref.substringAfter(':').replace('_', ' '), source = src,
            qid = (src as? Source.Wikidata)?.qid)
        app.wikidata.enrich(a)
        synchronized(byId) { byId[id] = a }
        return a
    }

    // ---------- obiettivi creati dall'utente ----------

    private fun userFile(a: Artwork) = File(app.filesDir, "annotations/${a.id.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")

    private fun loadUserTargets(a: Artwork) {
        val f = userFile(a)
        if (!f.exists()) return
        runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                a.targets += Target(o.getString("label"), NRect.from(o), o.optString("hint").ifBlank { null }, o.optInt("difficulty", 2), "user")
            }
        }
    }

    fun addUserTarget(a: Artwork, t: Target) {
        a.targets += t
        saveUserTargets(a)
    }

    fun removeUserTarget(a: Artwork, t: Target) {
        a.targets.remove(t)
        saveUserTargets(a)
    }

    private fun saveUserTargets(a: Artwork) {
        val f = userFile(a)
        f.parentFile?.mkdirs()
        val arr = JSONArray()
        a.targets.filter { it.origin == "user" }.forEach { t ->
            arr.put(JSONObject().put("label", t.label).put("x", t.rect.x).put("y", t.rect.y).put("w", t.rect.w).put("h", t.rect.h)
                .putOpt("hint", t.hint).put("difficulty", t.difficulty))
        }
        f.writeText(arr.toString(1))
    }

    /** Esporta gli obiettivi dell'utente (per condividerli o rimetterli nel catalogo sul PC). */
    fun exportUserTargets(): File? {
        val d = app.storage.dir("export") ?: return null
        val out = JSONObject()
        all.filter { a -> a.targets.any { it.origin == "user" } }.forEach { a ->
            out.put(a.id, JSONArray(a.targets.filter { it.origin == "user" }.map { t ->
                JSONObject().put("label", t.label).put("x", t.rect.x).put("y", t.rect.y).put("w", t.rect.w).put("h", t.rect.h)
            }))
        }
        return File(d, "user_targets.json").apply { writeText(out.toString(1)) }
    }

    companion object { private const val TAG = "Catalog" }
}
