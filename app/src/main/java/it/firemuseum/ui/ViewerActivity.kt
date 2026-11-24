package it.firemuseum.ui

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import it.firemuseum.App
import it.firemuseum.R
import it.firemuseum.game.Game
import it.firemuseum.model.Artwork
import it.firemuseum.model.NRect
import it.firemuseum.model.Target
import it.firemuseum.tiles.CommonsSource
import it.firemuseum.viewer.DeepZoomView
import it.firemuseum.viewer.Slideshow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Schermo di esplorazione. Telecomando:
 *   frecce = muovi (tieni premuto = accelera) · OK = zoom ×2 (tieni = zoom continuo)
 *   ⏩/⏪ = zoom avanti/indietro continuo · INDIETRO = zoom out / esci dalla finestra / esci
 *   ⏯ = slideshow · MENU (≡) = menu azioni (info, gioco, indizio, scarica originale…)
 *   Nel gioco: OK = "è qui!" (il mirino al centro), tieni premuto OK = zoom.
 */
class ViewerActivity : AppCompatActivity(), DeepZoomView.Listener {

    private val app get() = App.instance
    private lateinit var zoom: DeepZoomView
    private lateinit var caption: TextView
    private lateinit var hud: TextView
    private lateinit var gamePanel: TextView
    private lateinit var toast: TextView
    private lateinit var menu: ScrollView
    private lateinit var menuList: LinearLayout
    private lateinit var info: ScrollView
    private lateinit var infoText: TextView

    private var ids: List<String> = emptyList()
    private var index = 0
    private var game: Game? = null
    private lateinit var slideshow: Slideshow
    private var captionJob: Job? = null
    private var toastJob: Job? = null
    private var lastBack = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()

        ids = intent.getStringArrayExtra(EXTRA_LIST)?.toList() ?: emptyList()
        val id = intent.getStringExtra(EXTRA_ID)
        index = ids.indexOf(id).coerceAtLeast(0)
        slideshow = Slideshow(zoom, ::nextArtwork, { app.settings.slideSeconds }, { app.settings.slidePace }, ::showCaption) { on ->
            hudRefresh(); zoom.crosshair = game != null && !on
        }

        val a = id?.let { app.catalog.get(it) } ?: run { finish(); return }
        openArtwork(a) {
            when (intent.getStringExtra(EXTRA_MODE)) {
                "game" -> startGame()
                "slideshow" -> slideshow.start()
                "journey" -> { slideshow.infiniteJourney = true; slideshow.start() }
            }
        }
    }

    // ------------------------------------------------------------------ UI

    private fun tv(size: Float, gravity: Int): TextView = TextView(this).apply {
        textSize = size
        setTextColor(Color.WHITE)
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
        setBackgroundResource(R.drawable.panel_bg)
        setPadding(24, 14, 24, 14)
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity).apply {
            setMargins(36, 30, 36, 30)
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        zoom = DeepZoomView(this).also { it.listener = this }
        root.addView(zoom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        caption = tv(20f, Gravity.TOP or Gravity.START).apply { maxWidth = 1100 }
        hud = tv(13f, Gravity.BOTTOM or Gravity.END).apply { alpha = 0.75f }
        gamePanel = tv(22f, Gravity.TOP or Gravity.END).apply { visibility = View.GONE; maxWidth = 620 }
        toast = tv(26f, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { visibility = View.GONE }
        listOf(caption, hud, gamePanel, toast).forEach { root.addView(it) }

        menuList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 18, 18, 18) }
        menu = ScrollView(this).apply {
            addView(menuList)
            setBackgroundResource(R.drawable.panel_bg)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(560, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply { setMargins(0, 30, 30, 30) }
        }
        root.addView(menu)

        infoText = TextView(this).apply { textSize = 18f; setTextColor(Color.WHITE); setPadding(36, 30, 36, 30); setLineSpacing(6f, 1f) }
        info = ScrollView(this).apply {
            addView(infoText)
            isFocusable = true
            setBackgroundResource(R.drawable.panel_bg)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(900, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START).apply { setMargins(30, 30, 30, 30) }
        }
        root.addView(info)
        setContentView(root)
        zoom.requestFocus()
    }

    private fun say(msg: String, ms: Long = 2600) {
        toast.text = msg
        toast.visibility = View.VISIBLE
        toastJob?.cancel()
        toastJob = lifecycleScope.launch { delay(ms); toast.visibility = View.GONE }
    }

    private fun showCaption(a: Artwork) {
        caption.text = buildString {
            append(a.title)
            val sub = listOfNotNull(a.author, a.year).joinToString(", ")
            if (sub.isNotEmpty()) append("\n").append(sub)
            a.museum?.let { append("\n").append(it) }
            if (zoom.depth > 0) append("\n🪟 dentro una finestra (livello ${zoom.depth}) — INDIETRO per uscire")
        }
        caption.alpha = 0f; caption.visibility = View.VISIBLE
        caption.animate().alpha(1f).setDuration(400).start()
        captionJob?.cancel()
        captionJob = lifecycleScope.launch {
            delay(6000)
            caption.animate().alpha(0f).setDuration(800).withEndAction { caption.visibility = View.GONE }.start()
        }
    }

    private fun hudRefresh() {
        val s = zoom.source
        hud.text = buildString {
            append("×%.1f".format(zoom.zoomFactor()))
            s?.let { append(" · ").append(it.describe()) }
            if (slideshow.running) append(" · ▶ slideshow")
            if (app.storage.current?.internalFallback != false) append(" · ⚠ senza disco esterno")
        }
    }

    // ------------------------------------------------------------------ opere

    private fun openArtwork(a: Artwork, then: (() -> Unit)? = null) {
        game = null; gamePanel.visibility = View.GONE; zoom.crosshair = false; zoom.decorate = null
        zoom.open(a) { ok ->
            if (!ok) { say("Impossibile aprire l'immagine (rete o file mancante)", 4000); return@open }
            then?.invoke()
        }
        lifecycleScope.launch {
            app.wikidata.enrich(a)
            zoom.refreshPortals()
            if (zoom.artwork === a) showCaption(a)
        }
    }

    private fun nextArtwork(): Artwork? {
        if (ids.isEmpty()) return null
        index = (index + 1) % ids.size
        return app.catalog.get(ids[index])
    }

    override fun onArtworkChanged(a: Artwork, depth: Int) {
        showCaption(a)
        hudRefresh()
        if (depth > 0) lifecycleScope.launch { app.wikidata.enrich(a); zoom.refreshPortals() }
        if (game != null && game?.art !== a) stopGame()
    }

    override fun onUserMoved() {
        if (slideshow.running) { slideshow.stop(); say("Slideshow in pausa — ⏯ per riprendere") }
        lifecycleScope.launch { delay(250); hudRefresh() }
    }

    override fun onCenterTap() {
        val g = game
        if (g != null) checkGame(g) else zoom.zoomStep(2.0)
    }

    // ------------------------------------------------------------------ gioco

    private fun startGame() {
        val a = zoom.artwork ?: return
        lifecycleScope.launch {
            app.wikidata.enrich(a)
            val g = Game(a, zoom)
            if (g.targets.isEmpty()) {
                say("Nessun obiettivo per quest'opera: aggiungine dal menu (≡ → Aggiungi obiettivo qui)", 5000)
                return@launch
            }
            game = g
            zoom.crosshair = true
            zoom.decorate = { c -> g.draw(c) }
            updateGamePanel()
            say("Cerca e trova! Metti il mirino sull'oggetto e premi OK")
        }
    }

    private fun stopGame() {
        game = null; zoom.crosshair = false; zoom.decorate = null; gamePanel.visibility = View.GONE; zoom.invalidate()
    }

    private fun updateGamePanel() {
        val g = game ?: return
        gamePanel.visibility = View.VISIBLE
        gamePanel.text = if (g.isOver) "🏆 Finito! Punti: ${g.score}\n(≡ per un'altra partita)"
            else "🎯 Trova:\n${g.current?.label}\n\n${g.progress} · ${g.score} punti\n≡ indizio"
    }

    private fun checkGame(g: Game) {
        when (val r = g.check()) {
            is Game.Result.Found -> say("✓ Trovato: ${r.t.label}  +${r.points}")
            is Game.Result.TooFar -> say("Forse… avvicinati di più per esserne sicuro!")
            Game.Result.Miss -> say("Non qui… continua a cercare")
            Game.Result.Done -> say("Partita finita!")
        }
        updateGamePanel()
        zoom.invalidate()
    }

    private fun addTargetHere() {
        val a = zoom.artwork ?: return
        val v = zoom.visibleNormalized()
        val p = zoom.centerNormalized()
        val w = v.w * 0.18f; val h = v.h * 0.18f
        val rect = NRect(p.x - w / 2, p.y - h / 2, w, h)
        val et = EditText(this).apply { hint = "Cosa c'è sotto il mirino? (puoi dettarlo)"; inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Nuovo obiettivo").setView(et)
            .setPositiveButton("Salva") { _, _ ->
                val label = et.text.toString().trim()
                if (label.isNotEmpty()) {
                    app.catalog.addUserTarget(a, Target(label, rect, null, 2, "user"))
                    say("Salvato «$label» (${a.targets.count { it.origin == "user" }} tuoi obiettivi)")
                }
            }.setNegativeButton("Annulla", null).show()
    }

    // ------------------------------------------------------------------ menu

    private fun openMenu() {
        menuList.removeAllViews()
        val a = zoom.artwork
        val g = game
        fun item(label: String, action: () -> Unit) {
            menuList.addView(Button(this).apply {
                text = label; isAllCaps = false; textSize = 17f; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(getColor(R.color.text)); setBackgroundResource(R.drawable.chip_bg)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10 }
                setOnClickListener { closeMenu(); action() }
            })
        }
        item("ℹ Informazioni sull'opera") { showInfo() }
        if (g == null) item("🎯 Gioca: cerca e trova") { startGame() }
        else {
            item("💡 Indizio") { g.hint()?.let { say(it, 4000) }; zoom.invalidate() }
            item("⏭ Salta questo obiettivo") { g.skip(); updateGamePanel() }
            item("🔁 Nuova partita") { stopGame(); startGame() }
            item("✖ Termina il gioco") { stopGame() }
        }
        item("➕ Aggiungi obiettivo qui (mirino)") { zoom.crosshair = true; zoom.invalidate(); addTargetHere() }
        item(if (slideshow.running) "⏸ Ferma slideshow" else "▶ Slideshow") { if (slideshow.running) slideshow.stop() else slideshow.start() }
        item("∞ Viaggio infinito nelle finestre") { slideshow.infiniteJourney = true; slideshow.start() }
        item("🏠 Vista d'insieme") { zoom.home() }
        item(if (zoom.showPortalHints) "🪟 Nascondi contorni finestre" else "🪟 Mostra contorni finestre") { zoom.showPortalHints = !zoom.showPortalHints; zoom.invalidate() }
        (zoom.source as? CommonsSource)?.let { cs ->
            if (!cs.originalOnDisk) item("💾 Scarica l'originale (${cs.originalBytes shr 20} MB, ${cs.width}×${cs.height}) sul disco esterno") { downloadOriginal(cs) }
        }
        if (a != null) {
            val fav = a.id in app.settings.favorites
            item(if (fav) "★ Togli dai preferiti" else "☆ Aggiungi ai preferiti") {
                val now = app.settings.toggleFavorite(a.id)
                (zoom.source as? CommonsSource)?.pinOriginal(now)
                say(if (now) "Aggiunto ai preferiti (l'originale non verrà eliminato)" else "Tolto dai preferiti")
            }
        }
        item("⏭ Opera successiva") { nextArtwork()?.let { openArtwork(it) } }
        menu.visibility = View.VISIBLE
        menuList.getChildAt(0)?.requestFocus()
    }

    private fun closeMenu() { menu.visibility = View.GONE; zoom.requestFocus() }

    private fun showInfo() {
        val a = zoom.artwork ?: return
        val s = zoom.source
        infoText.text = buildString {
            append(a.title).append("\n")
            a.titleOrig?.takeIf { it != a.title }?.let { append("($it)\n") }
            append("\n")
            fun row(k: String, v: String?) { if (!v.isNullOrBlank()) append("$k: $v\n") }
            row("Autore", a.author); row("Anno", a.year); row("Stile / movimento", a.style); row("Genere", a.genre)
            row("Museo / collezione", a.museum)
            row("Risoluzione", s?.let { "${it.width} × ${it.height} px (%.0f Mpx)".format(it.width.toDouble() * it.height / 1e6) })
            row("Peso originale", if (a.bytes > 0) "%.1f MB".format(a.bytes / 1e6) else null)
            row("Licenza", a.license); row("Tag", a.tags.joinToString(", ").ifBlank { null })
            row("Obiettivi", if (a.targets.isNotEmpty()) "${a.targets.size} (${a.targets.count { it.origin == "wikidata" }} da Wikidata, ${a.targets.count { it.origin == "user" }} tuoi)" else null)
            row("Finestre", if (a.portals.isNotEmpty()) "${a.portals.size}: " + a.portals.mapNotNull { it.label }.take(8).joinToString(", ") else null)
            row("Wikidata", a.qid); row("Sorgente", s?.describe()); row("Pagina", a.pageUrl)
            a.warning?.let { append("\n⚠ $it\n") }
            a.description?.let { append("\n").append(it).append("\n") }
            append("\n(INDIETRO per chiudere)")
        }
        info.visibility = View.VISIBLE
        info.requestFocus()
    }

    private fun downloadOriginal(cs: CommonsSource) {
        if (app.storage.current?.internalFallback != false) {
            say("Collega un disco esterno: gli originali non vengono salvati nella memoria interna", 5000); return
        }
        say("Download dell'originale avviato…", 3000)
        lifecycleScope.launch {
            val ok = cs.downloadOriginal { done, total ->
                runOnUiThread { hud.text = "💾 %d / %d MB".format(done shr 20, total shr 20) }
            }
            if (ok) { say("Originale pronto: dettaglio massimo!"); zoom.reloadSource() }
            else say(cs.originalProblem ?: "Download non riuscito (spazio o rete)", 5000)
        }
    }

    // ------------------------------------------------------------------ tasti

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (info.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
                if (e.action == KeyEvent.ACTION_UP) { info.visibility = View.GONE; zoom.requestFocus() }
                return true
            }
            return super.dispatchKeyEvent(e)
        }
        if (menu.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
                if (e.action == KeyEvent.ACTION_UP) closeMenu()
                return true
            }
            return super.dispatchKeyEvent(e)
        }
        when (code) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_BUTTON_Y -> {
                if (e.action == KeyEvent.ACTION_UP) openMenu(); return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_BUTTON_X -> {
                if (e.action == KeyEvent.ACTION_UP) {
                    if (slideshow.running) slideshow.stop() else { say("▶ Slideshow"); slideshow.start() }
                }
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> {
                if (e.action == KeyEvent.ACTION_UP) onBackKey()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (e.action == KeyEvent.ACTION_UP) nextArtwork()?.let { openArtwork(it) }; return true }
        }
        if (zoom.handleKey(e)) { lifecycleScope.launch { hudRefresh() }; return true }
        return super.dispatchKeyEvent(e)
    }

    private fun onBackKey() {
        if (slideshow.running) { slideshow.stop(); say("Slideshow fermato"); return }
        if (!zoom.isAtOverview) { zoom.zoomStep(0.5); return }
        if (zoom.popPortal()) return
        val now = System.currentTimeMillis()
        if (now - lastBack < 1800) finish() else { lastBack = now; say("Premi ancora INDIETRO per tornare alla galleria", 1800) }
    }

    override fun onPause() {
        super.onPause()
        slideshow.stop()
        app.blobs.flush()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_LIST = "list"
        const val EXTRA_MODE = "mode"
    }
}
