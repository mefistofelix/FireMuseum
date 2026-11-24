package it.firemuseum.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.firemuseum.App
import it.firemuseum.R
import it.firemuseum.catalog.Filter
import it.firemuseum.catalog.Sort
import it.firemuseum.model.Artwork
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Galleria: ricerca libera (anche con il microfono della tastiera Fire TV), filtri, griglia. */
class MainActivity : AppCompatActivity() {

    private val app get() = App.instance
    private lateinit var search: EditText
    private lateinit var grid: RecyclerView
    private lateinit var count: TextView
    private lateinit var storageLabel: TextView
    private lateinit var adapter: ArtworkAdapter
    private val filters = LinkedHashSet<Filter>()
    private var sort = Sort.CATALOG
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        search = findViewById(R.id.search)
        grid = findViewById(R.id.grid)
        count = findViewById(R.id.count)
        storageLabel = findViewById(R.id.storage)

        adapter = ArtworkAdapter(ThumbLoader(lifecycleScope), { a, pos -> openViewer(a, pos, null) }, { a -> itemMenu(a) })
        grid.layoutManager = GridLayoutManager(this, 5)
        grid.adapter = adapter
        grid.setItemViewCacheSize(30)

        buildChips()

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch { delay(250); refresh() }
            }
        })
        search.setOnEditorActionListener { v, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                grid.requestFocus(); true
            } else false
        }

        lifecycleScope.launch {
            app.catalog.load()
            refresh()
            grid.postDelayed({ (grid.getChildAt(0) ?: grid).requestFocus() }, 150)  // non lasciare il focus sulla ricerca (apre la tastiera)
            // arricchimento Wikidata in background (autore, anno, stile, museo, obiettivi, finestre)
            app.catalog.all.forEach { a ->
                if (a.qid != null || a.targets.isEmpty()) {
                    app.wikidata.enrich(a)
                }
            }
            adapter.notifyDataSetChanged()
        }
    }

    override fun onResume() {
        super.onResume()
        app.storage.refresh()
        storageLabel.text = app.storage.describe()
        adapter.notifyDataSetChanged()
    }

    private fun chip(text: String, selected: Boolean = false, onClick: (TextView) -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 16f
            isFocusable = true
            isSelected = selected
            setPadding(28, 12, 28, 12)
            setBackgroundResource(R.drawable.chip_bg)
            setTextColor(getColor(R.color.text))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 14 }
            setOnClickListener { onClick(this) }
        }

    private fun buildChips() {
        val row = findViewById<LinearLayout>(R.id.chips)
        row.addView(chip("▶ Slideshow") { startSlideshow(false) })
        row.addView(chip("∞ Viaggio infinito") { startSlideshow(true) })
        row.addView(chip("🎲 Sorpresa") { current().randomOrNull()?.let { openViewer(it, 0, "game") } })
        Filter.values().forEach { f ->
            row.addView(chip(f.label, f in filters) { v ->
                if (!filters.add(f)) filters.remove(f)
                v.isSelected = f in filters
                refresh()
            })
        }
        row.addView(chip("↕ " + sort.label) { v ->
            sort = Sort.values()[(sort.ordinal + 1) % Sort.values().size]
            v.text = "↕ " + sort.label
            refresh()
        })
        row.addView(chip("⚙ Impostazioni") { settingsDialog() })
    }

    private fun current(): List<Artwork> = app.catalog.search(search.text.toString(), filters, sort)

    private fun refresh() {
        val list = current()
        adapter.items = list
        count.text = "${list.size} opere · OK apri · tieni premuto OK per altre azioni · ⏯ slideshow"
    }

    private fun openViewer(a: Artwork, pos: Int, mode: String?) {
        val ids = adapter.items.map { it.id }
        startActivity(Intent(this, ViewerActivity::class.java).apply {
            putExtra(ViewerActivity.EXTRA_ID, a.id)
            putExtra(ViewerActivity.EXTRA_LIST, ids.toTypedArray())
            mode?.let { putExtra(ViewerActivity.EXTRA_MODE, it) }
        })
    }

    private fun startSlideshow(infinite: Boolean) {
        val list = current()
        if (list.isEmpty()) return
        val first = if (infinite) list.filter { it.portals.isNotEmpty() || it.tags.contains("quadri nel quadro") }.randomOrNull() ?: list.random() else list.first()
        startActivity(Intent(this, ViewerActivity::class.java).apply {
            putExtra(ViewerActivity.EXTRA_ID, first.id)
            putExtra(ViewerActivity.EXTRA_LIST, list.map { it.id }.toTypedArray())
            putExtra(ViewerActivity.EXTRA_MODE, if (infinite) "journey" else "slideshow")
        })
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) { startSlideshow(false); return true }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val v = currentFocus
            val pos = v?.let { grid.findContainingViewHolder(it)?.bindingAdapterPosition } ?: -1
            if (pos >= 0) itemMenu(adapter.items[pos]) else settingsDialog()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun itemMenu(a: Artwork) {
        val fav = a.id in app.settings.favorites
        val opts = arrayOf("Esplora", "🎯 Gioca a cerca e trova", "▶ Slideshow da qui", if (fav) "★ Togli dai preferiti" else "☆ Aggiungi ai preferiti")
        AlertDialog.Builder(this).setTitle(a.title).setItems(opts) { _, w ->
            when (w) {
                0 -> openViewer(a, 0, null)
                1 -> openViewer(a, 0, "game")
                2 -> openViewer(a, 0, "slideshow")
                3 -> { app.settings.toggleFavorite(a.id); adapter.notifyDataSetChanged() }
            }
        }.show()
    }

    private fun settingsDialog() {
        val s = app.settings
        fun gb(b: Long) = if (b <= 0) "automatico" else "${b shr 30} GB"
        val items = arrayOf(
            "Velocità movimento: ${"%.1f".format(s.speed)}×",
            "Solo opere adatte ai bambini: ${if (s.kidsOnly) "sì" else "no"}",
            "Secondi per opera nello slideshow: ${s.slideSeconds}",
            "Limite cache su disco esterno: ${gb(s.tileCacheMaxBytes)}",
            "Cache interna se manca il disco: ${if (s.allowInternalFallback) "sì (${s.internalFallbackBytes shr 20} MB)" else "no"}",
            "Server LAN piramidi: ${s.lanServer.ifBlank { "nessuno" }}",
            "User-Agent / contatto per Wikimedia",
            "Archivio MEGA: ${if (s.megaLink.isBlank()) "spento" else app.mega.describe()}",
            "Esporta i miei obiettivi",
            "Svuota cache tasselli",
            "Stato archivio",
        )
        AlertDialog.Builder(this).setTitle("Impostazioni").setItems(items) { _, w ->
            when (w) {
                0 -> { val v = listOf(0.6f, 0.8f, 1f, 1.25f, 1.6f, 2f); s.speed = v[(v.indexOfFirst { it >= s.speed - 0.01f } + 1) % v.size]; settingsDialog() }
                1 -> { s.kidsOnly = !s.kidsOnly; refresh(); settingsDialog() }
                2 -> { val v = listOf(20, 40, 60, 120, 300); s.slideSeconds = v[(v.indexOf(s.slideSeconds) + 1) % v.size]; settingsDialog() }
                3 -> { val v = listOf(0L, 2L, 5L, 10L, 20L, 50L, 100L).map { it shl 30 }; s.tileCacheMaxBytes = v[(v.indexOf(s.tileCacheMaxBytes) + 1) % v.size]; app.governor.trimAsync(); settingsDialog() }
                4 -> { s.allowInternalFallback = !s.allowInternalFallback; app.storage.refresh(); settingsDialog() }
                5 -> textDialog("Server LAN (es. http://192.168.1.10:8000/)", s.lanServer) { s.lanServer = it }
                6 -> textDialog("User-Agent (metti un contatto tuo)", s.userAgent) { s.userAgent = it.ifBlank { Settings.DEFAULT_UA } }
                7 -> textDialog("Link cartella MEGA (vuoto = spento)", s.megaLink) { s.megaLink = it; recreate() }
                8 -> Toast.makeText(this, app.catalog.exportUserTargets()?.path ?: "Nessun disco", Toast.LENGTH_LONG).show()
                9 -> { lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { app.tiles.clear() }; app.memory.clear() }
                10 -> AlertDialog.Builder(this).setTitle("Archivio").setMessage(
                    "${app.storage.describe()}\n\nPercorso: ${app.storage.current?.root}\n" +
                        "Tasselli: ${app.tiles.totalBytes shr 20} MB in ${app.tiles.segmentCount} segmenti\n" +
                        "Originali/anteprime: ${app.blobs.totalBytes shr 20} MB\n\n" +
                        "Piramidi preparate sul PC: copiale in\n${app.storage.current?.root}/pyramids/<id>.zip"
                ).setPositiveButton("OK", null).show()
            }
        }.show()
    }

    private fun textDialog(title: String, value: String, onOk: (String) -> Unit) {
        val et = EditText(this).apply { setText(value); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("OK") { _, _ -> onOk(et.text.toString().trim()) }
            .setNegativeButton("Annulla", null).show()
    }

    @Suppress("unused") private fun View.hide() { visibility = View.GONE }
}
