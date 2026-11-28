package it.firemuseum.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import it.firemuseum.App
import it.firemuseum.cache.CachedTile
import it.firemuseum.model.Artwork
import it.firemuseum.model.NRect
import it.firemuseum.tiles.TileReq
import it.firemuseum.tiles.TileSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Visualizzatore a zoom profondo pensato per il telecomando:
 *  - D-pad: spostamento con accelerazione progressiva (tocco breve = piccolo passo preciso);
 *  - OK breve: zoom ×2 animato; OK tenuto premuto: zoom continuo che accelera;
 *  - programma su/giù tenuti premuti: zoom avanti/indietro continuo; INDIETRO: zoom out a scatti;
 *  - gamepad: levetta sinistra = spostamento analogico, grilletti/levetta destra = zoom.
 * Velocità in pixel-SCHERMO al secondo: la sensazione è identica a ogni livello di zoom.
 *
 * Inquadratura: mai più piccola della vista d'insieme e, appena l'immagine supera lo schermo
 * in un asse, i bordi restano agganciati ai lati del televisore (niente fasce vuote zoomando
 * verso un angolo). Le fasce inevitabili della vista d'insieme prendono il colore medio di
 * quello che si vede, con una dissolvenza lenta.
 */
class DeepZoomView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    interface Listener {
        fun onArtworkChanged(a: Artwork) {}
        fun onUserMoved() {}
        fun onCenterTap() {}
    }

    private val app = App.instance
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var listener: Listener? = null

    var artwork: Artwork? = null; private set
    var source: TileSource? = null; private set
    val cam = Cam(0.0, 0.0, 1.0)
    private var flight: Flight? = null
    private var flightStart = 0L
    private val flightFrom = Cam(0.0, 0.0, 1.0)
    private var flightDone: (() -> Unit)? = null

    // ---- stato input ----
    private val held = HashMap<Int, Long>()
    private var vx = 0.0; private var vy = 0.0; private var zv = 0.0
    private var axX = 0f; private var axY = 0f; private var axZoom = 0f
    private var lastFrameNs = 0L
    private var frameNs = 0L
    private var frameScheduled = false
    private var fading = false

    // ---- colore di sfondo dalle tinte dell'opera ----
    private class Ambient(val src: TileSource, val w: Int, val h: Int, val px: IntArray)
    private var ambient: Ambient? = null
    private var ambientBuilding: TileSource? = null
    private var bgR = 0f; private var bgG = 0f; private var bgB = 0f
    private var bgMs = 0L

    // ---- disegno ----
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isDither = false }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f; textAlign = Paint.Align.CENTER }
    private val dst = RectF()
    private val reqs = ArrayList<TileReq>(256)
    private var lastSubmitSig = 0L

    private val onTile: () -> Unit = { invalidate() }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); app.loader.addListener(onTile) }
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        app.loader.removeListener(onTile)
        scope.cancel()
    }

    // =====================================================================================
    // Apertura opere
    // =====================================================================================

    fun open(a: Artwork, onReady: ((Boolean) -> Unit)? = null) {
        scope.launch {
            val s = app.sources.get(a)
            if (s == null) { onReady?.invoke(false); return@launch }
            setCurrent(a, s)
            fitNow()
            onReady?.invoke(true)
        }
    }

    /** Riapre la sorgente corrente (dopo download dell'originale o copia della piramide). */
    fun reloadSource() {
        val a = artwork ?: return
        scope.launch {
            app.sources.invalidate(a.id)
            val s = app.sources.get(a) ?: return@launch
            val keep = Cam(cam.cx, cam.cy, cam.scale)
            setCurrent(a, s)
            cam.copyFrom(keep)
            invalidate()
        }
    }

    private fun setCurrent(a: Artwork, s: TileSource) {
        cancelFlight()
        artwork = a; source = s
        listener?.onArtworkChanged(a)
        invalidate()
    }

    fun fitScale(s: TileSource? = source): Double {
        s ?: return 1.0
        if (width == 0 || height == 0) return 1.0
        return min(width.toDouble() / s.width, height.toDouble() / s.height)
    }

    private fun maxScale(): Double {
        val s = source ?: return 4.0
        return max(2.5 / (1 shl s.minK()), fitScale(s))
    }

    fun fitCam(): Cam {
        val s = source ?: return Cam(0.0, 0.0, 1.0)
        return Cam(s.width / 2.0, s.height / 2.0, fitScale())
    }

    private fun fitNow() {
        if (width == 0) { post { fitNow() }; return }
        cam.copyFrom(fitCam())
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (ow == 0 && source != null) fitNow()
    }

    /**
     * Inquadratura lecita: scala tra vista d'insieme e massimo; in ogni asse in cui l'immagine è più
     * grande dello schermo i bordi non entrano nello schermo, negli altri l'immagine resta centrata.
     * È continua, quindi applicarla a ogni fotogramma dei voli non crea scatti.
     */
    private fun constrain(c: Cam, s: TileSource) {
        c.scale = c.scale.coerceIn(fitScale(s), maxScale())
        val hw = width / 2.0 / c.scale; val hh = height / 2.0 / c.scale
        c.cx = if (s.width > 2 * hw) c.cx.coerceIn(hw, s.width - hw) else s.width / 2.0
        c.cy = if (s.height > 2 * hh) c.cy.coerceIn(hh, s.height - hh) else s.height / 2.0
    }

    // =====================================================================================
    // Movimento automatico
    // =====================================================================================

    fun flyTo(target: Cam, speed: Double = 1.0, minDur: Double = 0.35, maxDur: Double = 7.0, done: (() -> Unit)? = null) {
        val s = source
        if (width == 0 || s == null) return
        val to = target.copy().also { constrain(it, s) }
        flightFrom.copyFrom(cam)
        flight = Flight(flightFrom, to, width.toDouble(), speed, minDur, maxDur)
        flightStart = -1L          // fissato al primo vsync: il volo parte dal fotogramma successivo, senza salto
        flightDone = done
        vx = 0.0; vy = 0.0; zv = 0.0
        schedule()
    }

    /** Vola fino a inquadrare [r] (coordinate normalizzate) riempiendo [fill] dello schermo. */
    fun flyToRect(r: NRect, fill: Double = 0.6, speed: Double = 1.0, maxDur: Double = 7.0, done: (() -> Unit)? = null) {
        val s = source ?: return
        val rw = max(r.w * s.width.toDouble(), 1.0); val rh = max(r.h * s.height.toDouble(), 1.0)
        val sc = min(width * fill / rw, height * fill / rh)
        flyTo(Cam(r.cx * s.width.toDouble(), r.cy * s.height.toDouble(), sc), speed, 0.35, maxDur, done)
    }

    fun zoomStep(factor: Double) {
        flyTo(Cam(cam.cx, cam.cy, cam.scale * factor), speed = 1.6, minDur = 0.28, maxDur = 0.6)
    }

    fun home() = flyTo(fitCam(), speed = 1.4)

    val isFlying: Boolean get() = flight != null
    fun cancelFlight() { flight = null; flightDone = null }

    // =====================================================================================
    // Input
    // =====================================================================================

    private val panKeys = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
    private val zoomInKeys = setOf(KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_ZOOM_IN)
    private val zoomOutKeys = setOf(KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_ZOOM_OUT)
    private val centerKeys = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_NUMPAD_ENTER)

    /** Restituisce true se il tasto è stato consumato dal visualizzatore. */
    fun handleKey(e: KeyEvent): Boolean {
        val c = e.keyCode
        val ours = c in panKeys || c in zoomInKeys || c in zoomOutKeys || c in centerKeys
        if (!ours) return false
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (e.repeatCount == 0 && !held.containsKey(c)) {
                held[c] = SystemClock.uptimeMillis()
                if (flight != null) cancelFlight()
                listener?.onUserMoved()
                schedule()
            }
            return true
        }
        if (e.action == KeyEvent.ACTION_UP) {
            val down = held.remove(c) ?: return true
            val dur = SystemClock.uptimeMillis() - down
            if (c in panKeys && dur < TAP_MS) {
                // passo breve e preciso: ~1/8 di schermo con decadimento morbido
                val step = NUDGE * height * app.settings.speed / DECAY_PAN
                when (c) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> vx = -step
                    KeyEvent.KEYCODE_DPAD_RIGHT -> vx = step
                    KeyEvent.KEYCODE_DPAD_UP -> vy = -step
                    KeyEvent.KEYCODE_DPAD_DOWN -> vy = step
                }
                schedule()
            }
            if (c in centerKeys && dur < LONG_MS) listener?.onCenterTap()
            if ((c in zoomInKeys) && dur < TAP_MS) zoomStep(2.0)
            if ((c in zoomOutKeys) && dur < TAP_MS) zoomStep(0.5)
            return true
        }
        return true
    }

    /** Gamepad / telecomandi con levetta. */
    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK && e.action == MotionEvent.ACTION_MOVE) {
            fun dz(v: Float) = if (abs(v) < 0.15f) 0f else v
            axX = dz(e.getAxisValue(MotionEvent.AXIS_X))
            axY = dz(e.getAxisValue(MotionEvent.AXIS_Y))
            val rt = e.getAxisValue(MotionEvent.AXIS_RTRIGGER).coerceAtLeast(e.getAxisValue(MotionEvent.AXIS_GAS))
            val lt = e.getAxisValue(MotionEvent.AXIS_LTRIGGER).coerceAtLeast(e.getAxisValue(MotionEvent.AXIS_BRAKE))
            val rz = -dz(e.getAxisValue(MotionEvent.AXIS_RZ))
            axZoom = if (abs(rt - lt) > 0.05f) rt - lt else rz
            if (axX != 0f || axY != 0f || axZoom != 0f) { cancelFlight(); listener?.onUserMoved() }
            schedule()
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    /** Touch/mouse (emulatore, tablet): trascina per spostare. */
    private var lastTouch: PointF? = null
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastTouch = PointF(e.x, e.y); cancelFlight() }
            MotionEvent.ACTION_MOVE -> lastTouch?.let {
                cam.cx -= (e.x - it.x) / cam.scale; cam.cy -= (e.y - it.y) / cam.scale
                it.set(e.x, e.y); clamp(); invalidate()
            }
            MotionEvent.ACTION_UP -> lastTouch = null
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent) = handleKey(event) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent) = handleKey(event) || super.onKeyUp(keyCode, event)

    private fun isHeld(keys: Set<Int>) = keys.any { held.containsKey(it) }
    private fun heldSince(keys: Set<Int>): Long = keys.mapNotNull { held[it] }.maxOrNull() ?: 0L

    // =====================================================================================
    // Ciclo di animazione
    // =====================================================================================

    private val frameCb = Choreographer.FrameCallback { ns ->
        frameScheduled = false
        val dt = if (lastFrameNs == 0L) 1 / 60.0 else ((ns - lastFrameNs) / 1e9).coerceIn(0.001, 0.1)
        lastFrameNs = ns
        frameNs = ns
        val moving = step(dt)
        invalidate()
        if (moving) schedule() else lastFrameNs = 0L
    }

    private fun schedule() {
        if (!frameScheduled) { frameScheduled = true; Choreographer.getInstance().postFrameCallback(frameCb) }
    }

    private fun smooth(x: Double): Double { val t = x.coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }

    /** Avanza la fisica; true se c'è ancora movimento. */
    private fun step(dt: Double): Boolean {
        val src = source ?: return false
        val now = SystemClock.uptimeMillis()
        val speed = app.settings.speed.toDouble()

        flight?.let { f ->
            // tempo del vsync, non dell'orologio: posizioni equidistanti fotogramma per fotogramma
            if (flightStart < 0) flightStart = frameNs
            val t = (frameNs - flightStart) / 1e9 / f.duration
            f.at(t.coerceAtMost(1.0), cam)
            constrain(cam, src)
            if (t >= 1.0) {
                flight = null
                val d = flightDone; flightDone = null
                d?.invoke()
            }
            return true
        }

        // --- spostamento ---
        var dx = 0.0; var dy = 0.0
        if (held.containsKey(KeyEvent.KEYCODE_DPAD_LEFT)) dx -= 1
        if (held.containsKey(KeyEvent.KEYCODE_DPAD_RIGHT)) dx += 1
        if (held.containsKey(KeyEvent.KEYCODE_DPAD_UP)) dy -= 1
        if (held.containsKey(KeyEvent.KEYCODE_DPAD_DOWN)) dy += 1
        if (dx != 0.0 || dy != 0.0) {
            val t = (now - heldSince(panKeys)) / 1000.0
            if (t * 1000 > TAP_MS) {
                // rampa: lento e preciso all'inizio, poi accelera fino a ~1,7 schermi/s
                val sp = (PAN_MIN + (PAN_MAX - PAN_MIN) * smooth((t - 0.15) / PAN_RAMP)) * height * speed
                val n = hypot(dx, dy)
                val a = 1 - exp(-dt / 0.05)
                vx += (dx / n * sp - vx) * a
                vy += (dy / n * sp - vy) * a
            }
        } else if (axX != 0f || axY != 0f) {
            val sp = PAN_MAX * height * speed
            fun curve(v: Float) = v * abs(v) * abs(v).toDouble() // risposta cubica: precisione vicino al centro
            vx = curve(axX) * sp; vy = curve(axY) * sp
        } else {
            val d = exp(-dt / DECAY_PAN)
            vx *= d; vy *= d
        }

        // --- zoom ---
        val longCenter = centerKeys.any { k -> held[k]?.let { now - it > LONG_MS } == true }
        val zin = (isHeld(zoomInKeys) && now - heldSince(zoomInKeys) > TAP_MS) || longCenter
        val zout = isHeld(zoomOutKeys) && now - heldSince(zoomOutKeys) > TAP_MS
        if (zin != zout) {
            val since = if (zin) (if (longCenter) heldSince(centerKeys) + LONG_MS else heldSince(zoomInKeys)) else heldSince(zoomOutKeys)
            val t = (now - since) / 1000.0
            val rate = (ZOOM_MIN + (ZOOM_MAX - ZOOM_MIN) * smooth(t / ZOOM_RAMP)) * speed * (if (zin) 1 else -1)
            zv += (rate - zv) * (1 - exp(-dt / 0.06))
        } else if (axZoom != 0f) {
            zv = axZoom * ZOOM_MAX * speed
        } else {
            zv *= exp(-dt / DECAY_ZOOM)
        }

        cam.cx += vx * dt / cam.scale
        cam.cy += vy * dt / cam.scale
        cam.scale *= 2.0.pow(zv * dt)
        clamp()

        val moving = abs(vx) > 2 || abs(vy) > 2 || abs(zv) > 0.01 || held.isNotEmpty() ||
            axX != 0f || axY != 0f || axZoom != 0f
        if (!moving) { vx = 0.0; vy = 0.0; zv = 0.0 }
        return moving
    }

    private fun clamp() {
        val s = source ?: return
        if (cam.scale >= maxScale()) zv = min(zv, 0.0)
        if (cam.scale <= fitScale()) zv = max(zv, 0.0)
        val bx = cam.cx; val by = cam.cy
        constrain(cam, s)
        if (cam.cx != bx) vx = 0.0          // contro il bordo: niente inerzia che "spinge"
        if (cam.cy != by) vy = 0.0
    }

    val isAtOverview: Boolean get() = cam.scale <= fitScale() * 1.05

    // =====================================================================================
    // Sfondo
    // =====================================================================================

    /** Mappa minuscola dei colori dell'opera, dal livello più grossolano (un solo tassello di solito). */
    private fun ensureAmbient(src: TileSource) {
        if (ambient?.src === src || ambientBuilding === src) return
        val k = src.maxK
        val tiles = ArrayList<Pair<Rect, CachedTile>>()
        for (y in 0 until src.rows(k)) for (x in 0 until src.cols(k)) {
            val t = app.memory[src.key(k, x, y)] ?: return       // non ancora arrivato: si riprova al prossimo disegno
            tiles += src.imageRect(k, x, y) to t
        }
        ambientBuilding = src
        scope.launch {
            val a = withContext(Dispatchers.Default) {
                runCatching {
                    val gw = AMBIENT_W
                    val gh = (AMBIENT_W * src.height.toDouble() / src.width).roundToInt().coerceIn(2, 64)
                    val grid = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
                    val c = Canvas(grid)
                    val p = Paint(Paint.FILTER_BITMAP_FLAG)
                    val sx = gw.toFloat() / src.width; val sy = gh.toFloat() / src.height
                    for ((r, t) in tiles) {
                        // i bitmap HARDWARE non si leggono né si disegnano su una tela software: copia
                        val sw = t.bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: continue
                        c.drawBitmap(sw, t.src, RectF(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy), p)
                        sw.recycle()
                    }
                    val px = IntArray(gw * gh)
                    grid.getPixels(px, 0, gw, 0, 0, gw, gh)
                    grid.recycle()
                    Ambient(src, gw, gh, px)
                }.getOrNull()
            }
            if (ambientBuilding === src) ambientBuilding = null
            if (a != null && source === src) { ambient = a; invalidate() }
        }
    }

    /** Colore medio della parte visibile, scurito: fa da cornice senza competere con il quadro. */
    private fun drawBackground(canvas: Canvas, src: TileSource, il: Double, itop: Double, ir: Double, ib: Double) {
        ensureAmbient(src)
        val a = ambient?.takeIf { it.src === src }
        var tr = 0f; var tg = 0f; var tb = 0f
        if (a != null) {
            val x0 = (il / src.width * a.w).toInt().coerceIn(0, a.w - 1)
            val x1 = (ir / src.width * a.w).toInt().coerceIn(x0, a.w - 1)
            val y0 = (itop / src.height * a.h).toInt().coerceIn(0, a.h - 1)
            val y1 = (ib / src.height * a.h).toInt().coerceIn(y0, a.h - 1)
            var n = 0
            for (y in y0..y1) for (x in x0..x1) {
                val c = a.px[y * a.w + x]
                tr += Color.red(c); tg += Color.green(c); tb += Color.blue(c); n++
            }
            tr = tr / n * BG_DIM; tg = tg / n * BG_DIM; tb = tb / n * BG_DIM
        }
        val now = SystemClock.uptimeMillis()
        val dt = if (bgMs == 0L) 0.0 else ((now - bgMs) / 1000.0).coerceAtMost(0.1)
        bgMs = now
        val k = (1 - exp(-dt / BG_FADE_S)).toFloat()
        bgR += (tr - bgR) * k; bgG += (tg - bgG) * k; bgB += (tb - bgB) * k
        canvas.drawColor(Color.rgb(bgR.roundToInt(), bgG.roundToInt(), bgB.roundToInt()))
        if (abs(tr - bgR) + abs(tg - bgG) + abs(tb - bgB) > 1.5f) fading = true else bgMs = 0L
    }

    // =====================================================================================
    // Disegno
    // =====================================================================================

    override fun onDraw(canvas: Canvas) {
        val s = source
        if (s == null) {
            canvas.drawColor(Color.BLACK)
            canvas.drawText("Caricamento…", width / 2f, height / 2f, textPaint)
            return
        }
        reqs.clear()
        fading = false
        val ox = width / 2.0 - cam.cx * cam.scale
        val oy = height / 2.0 - cam.cy * cam.scale

        // sfondo (si vede nelle fasce della vista d'insieme) dal colore della parte visibile
        drawBackground(canvas, s,
            max(0.0, -ox / cam.scale), max(0.0, -oy / cam.scale),
            min(s.width.toDouble(), (width - ox) / cam.scale), min(s.height.toDouble(), (height - oy) / cam.scale))

        drawSource(canvas, s, ox, oy, cam.scale, 255, 0f)

        // invia le richieste solo se qualcosa è cambiato (camera o insieme richiesto)
        val sig = reqs.fold(17L) { acc, r -> acc * 31 + r.key.hashCode() }
        if (sig != lastSubmitSig) { lastSubmitSig = sig; app.loader.submit(ArrayList(reqs)) }
        if (fading) postInvalidateOnAnimation()
    }

    /**
     * Disegna una sorgente con trasformazione schermo = o + p * sc.
     * Pittore: dal livello completo più fine ≥ ideale fino all'ideale, così non restano mai buchi
     * e l'overdraw resta di 1–2 strati anche su GPU modeste.
     */
    private fun drawSource(canvas: Canvas, src: TileSource, ox: Double, oy: Double, sc: Double, alpha: Int, basePrio: Float) {
        val ideal = floor(ln(1.0 / sc) / ln(2.0) + 1e-9).toInt()
        val kMin = src.minK(); val kMax = src.maxK
        val kIdeal = ideal.coerceIn(kMin, kMax)

        // area visibile in pixel immagine
        val il = max(0.0, (0 - ox) / sc); val itop = max(0.0, (0 - oy) / sc)
        val ir = min(src.width.toDouble(), (width - ox) / sc); val ib = min(src.height.toDouble(), (height - oy) / sc)
        if (ir <= il || ib <= itop) return

        fun range(k: Int, margin: Int): IntArray {
            val span = src.tileSize.toDouble() * (1 shl k)
            return intArrayOf(
                max(0, floor(il / span).toInt() - margin), min(src.cols(k) - 1, floor((ir - 1e-6) / span).toInt() + margin),
                max(0, floor(itop / span).toInt() - margin), min(src.rows(k) - 1, floor((ib - 1e-6) / span).toInt() + margin),
            )
        }

        // livello completo più fine (un tassello ancora in dissolvenza non conta: sotto serve il livello grossolano)
        val now = SystemClock.uptimeMillis()
        var kStart = kMax
        for (k in kIdeal..kMax) {
            val rg = range(k, 0)
            var complete = true
            loop@ for (y in rg[2]..rg[3]) for (x in rg[0]..rg[1]) {
                val t = app.memory[src.key(k, x, y)]
                if (t == null || t.shownAt == 0L || now - t.shownAt < FADE_MS) { complete = false; break@loop }
            }
            if (complete) { kStart = k; break }
        }

        for (k in kStart downTo kIdeal) {
            val rg = range(k, 0)
            for (y in rg[2]..rg[3]) for (x in rg[0]..rg[1]) {
                val t = app.memory[src.key(k, x, y)] ?: continue
                val r: Rect = src.imageRect(k, x, y)
                // coordinate frazionarie: arrotondare al pixel faceva avanzare i tasselli a scatti di 1 px
                // nei movimenti lenti; i bordi condivisi hanno lo stesso valore, quindi niente fessure
                dst.set(
                    (ox + r.left * sc).toFloat(), (oy + r.top * sc).toFloat(),
                    (ox + r.right * sc).toFloat(), (oy + r.bottom * sc).toFloat(),
                )
                // i dettagli nuovi entrano in dissolvenza sopra il livello più grossolano invece di "scattare"
                if (t.shownAt == 0L) t.shownAt = now
                val age = now - t.shownAt
                val fade = if (k == kStart || age >= FADE_MS) 1f else { fading = true; age / FADE_MS.toFloat() }
                tilePaint.alpha = (alpha * fade).roundToInt()
                canvas.drawBitmap(t.bitmap, t.src, dst, tilePaint)
            }
        }

        // ---- richieste ----
        val cxs = width / 2.0; val cys = height / 2.0
        fun dist(k: Int, x: Int, y: Int): Float {
            val span = src.tileSize * (1 shl k) * sc
            val tx = ox + (x + 0.5) * span; val ty = oy + (y + 0.5) * span
            return (hypot(tx - cxs, ty - cys) / max(width, height)).toFloat()
        }
        fun want(k: Int, margin: Int, prio: Float) {
            val rg = range(k, margin)
            for (y in rg[2]..rg[3]) for (x in rg[0]..rg[1]) reqs += TileReq(src, k, x, y, prio + dist(k, x, y))
        }
        want(kMax, 0, basePrio)
        for (k in min(kMax, kIdeal + 2) downTo kIdeal + 1) want(k, 0, basePrio + 1f + (k - kIdeal) * 0.1f)
        want(kIdeal, 0, basePrio + 2f)
        want(kIdeal, 1, basePrio + 20f)
        if (zv > 0.1 && kIdeal - 1 >= kMin) {
            val rg = range(kIdeal - 1, 0)
            val midX = (rg[0] + rg[1]) / 2; val midY = (rg[2] + rg[3]) / 2
            for (y in max(rg[2], midY - 1)..min(rg[3], midY + 1)) for (x in max(rg[0], midX - 1)..min(rg[1], midX + 1))
                reqs += TileReq(src, kIdeal - 1, x, y, basePrio + 40f)
        }
    }

    /** Fattore di ingrandimento leggibile (1× = vista d'insieme). */
    fun zoomFactor(): Double = cam.scale / fitScale()

    companion object {
        private const val FADE_MS = 220L         // dissolvenza dei tasselli appena arrivati
        private const val TAP_MS = 160L
        private const val LONG_MS = 320L
        private const val NUDGE = 0.12           // passo breve: frazione dell'altezza schermo
        private const val PAN_MIN = 0.35         // schermi/secondo all'inizio
        private const val PAN_MAX = 1.7          // schermi/secondo a regime
        private const val PAN_RAMP = 1.1         // secondi per arrivare a regime
        private const val ZOOM_MIN = 0.7         // raddoppi/secondo all'inizio
        private const val ZOOM_MAX = 2.6         // raddoppi/secondo a regime
        private const val ZOOM_RAMP = 1.0
        private const val DECAY_PAN = 0.12       // inerzia al rilascio (s)
        private const val DECAY_ZOOM = 0.15
        private const val AMBIENT_W = 32         // colonne della mappa dei colori
        private const val BG_DIM = 0.55f         // sfondo più scuro dell'opera
        private const val BG_FADE_S = 0.6        // costante di tempo della dissolvenza dello sfondo
    }
}
