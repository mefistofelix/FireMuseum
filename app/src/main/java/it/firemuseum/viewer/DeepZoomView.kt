package it.firemuseum.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
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
import it.firemuseum.model.Artwork
import it.firemuseum.model.NRect
import it.firemuseum.model.Portal
import it.firemuseum.tiles.TileReq
import it.firemuseum.tiles.TileSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
 *  - ⏪/⏩ tenuti premuti: zoom indietro/avanti continuo; INDIETRO: zoom out a scatti;
 *  - gamepad: levetta sinistra = spostamento analogico, grilletti/levetta destra = zoom.
 * Velocità in pixel-SCHERMO al secondo: la sensazione è identica a ogni livello di zoom.
 *
 * "Finestre" (portali): un'opera può contenere rettangoli che rimandano a un'altra opera.
 * Avvicinandosi la si vede comparire in dissolvenza al suo posto; quando riempie lo schermo
 * ci si "entra" senza stacchi. Allontanandosi oltre la vista d'insieme si torna al quadro esterno.
 */
class DeepZoomView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    interface Listener {
        fun onArtworkChanged(a: Artwork, depth: Int) {}
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

    // ---- finestre / portali ----
    private class Child(val portal: Portal, var art: Artwork?, var src: TileSource?, var loading: Boolean = false)
    private class Frame(val art: Artwork, val src: TileSource, val portal: Portal, val child: Child)
    private val children = ArrayList<Child>()
    private val stack = ArrayDeque<Frame>()
    val depth: Int get() = stack.size
    var showPortalHints = true
    /** Slideshow in corso: niente contorni tratteggiati delle finestre (servono solo navigando a mano). */
    var autoPlay = false

    // ---- decorazioni (gioco, indizi) ----
    var decorate: ((Canvas) -> Unit)? = null
    var crosshair = false

    // ---- disegno ----
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isDither = false }
    private val portalPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xCCE8B04B.toInt()
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.WHITE }
    private val crossShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = 0x99000000.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f; textAlign = Paint.Align.CENTER }
    private val dst = RectF()
    private val tmpRect = RectF()
    private val reqs = ArrayList<TileReq>(256)
    private var lastSubmitSig = 0L

    private val onTile: () -> Unit = { invalidate() }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
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
        stack.clear()
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
        artwork = a; source = s
        children.clear()
        a.portals.forEach { children += Child(it, null, null) }
        listener?.onArtworkChanged(a, stack.size)
        invalidate()
    }

    fun refreshPortals() {
        val a = artwork ?: return
        val existing = children.map { it.portal }.toSet()
        a.portals.filter { it !in existing }.forEach { children += Child(it, null, null) }
        invalidate()
    }

    fun fitScale(s: TileSource? = source): Double {
        s ?: return 1.0
        if (width == 0 || height == 0) return 1.0
        return min(width.toDouble() / s.width, height.toDouble() / s.height)
    }

    private fun maxScale(): Double {
        val s = source ?: return 4.0
        return 2.5 / (1 shl s.minK())
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

    // =====================================================================================
    // Movimento automatico
    // =====================================================================================

    fun flyTo(target: Cam, speed: Double = 1.0, minDur: Double = 0.35, maxDur: Double = 7.0, done: (() -> Unit)? = null) {
        if (width == 0) return
        flightFrom.copyFrom(cam)
        flight = Flight(flightFrom, target, width.toDouble(), speed, minDur, maxDur)
        flightStart = -1L          // fissato al primo vsync: il volo parte dal fotogramma successivo, senza salto
        flightDone = done
        vx = 0.0; vy = 0.0; zv = 0.0
        schedule()
    }

    /** Vola fino a inquadrare [r] (coordinate normalizzate) riempiendo [fill] dello schermo. */
    fun flyToRect(r: NRect, fill: Double = 0.6, speed: Double = 1.0, maxDur: Double = 7.0, done: (() -> Unit)? = null) {
        val s = source ?: return
        val rw = max(r.w * s.width.toDouble(), 1.0); val rh = max(r.h * s.height.toDouble(), 1.0)
        val sc = min(width * fill / rw, height * fill / rh).coerceIn(fitScale() * 0.9, maxScale())
        flyTo(Cam(r.cx * s.width.toDouble(), r.cy * s.height.toDouble(), sc), speed, 0.35, maxDur, done)
    }

    fun zoomStep(factor: Double) {
        val target = Cam(cam.cx, cam.cy, (cam.scale * factor).coerceIn(fitScale() * 0.7, maxScale()))
        flyTo(target, speed = 1.6, minDur = 0.28, maxDur = 0.6)
    }

    fun home() = flyTo(fitCam(), speed = 1.4)

    val isFlying: Boolean get() = flight != null
    fun cancelFlight() { flight = null; flightDone = null }

    // =====================================================================================
    // Input
    // =====================================================================================

    private val panKeys = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
    private val zoomInKeys = setOf(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_UP)
    private val zoomOutKeys = setOf(KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_DOWN)
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
                it.set(e.x, e.y); clamp(0.0); invalidate()
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
            if (t >= 1.0) {
                flight = null
                val d = flightDone; flightDone = null
                d?.invoke()
            }
            checkPortals(true)
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
        clamp(dt)
        checkPortals(zv > 0.05)

        val moving = abs(vx) > 2 || abs(vy) > 2 || abs(zv) > 0.01 || held.isNotEmpty() ||
            axX != 0f || axY != 0f || axZoom != 0f
        if (!moving) { vx = 0.0; vy = 0.0; zv = 0.0 }
        return moving
    }

    private fun clamp(dt: Double) {
        val s = source ?: return
        val minS = fitScale() * (if (stack.isEmpty()) 0.85 else 0.5)
        if (cam.scale > maxScale()) { cam.scale = maxScale(); zv = min(zv, 0.0) }
        if (cam.scale < minS) { cam.scale = minS; zv = max(zv, 0.0) }
        cam.cx = cam.cx.coerceIn(0.0, s.width.toDouble())
        cam.cy = cam.cy.coerceIn(0.0, s.height.toDouble())
        // sotto la vista d'insieme: riporta dolcemente al centro
        if (cam.scale <= fitScale() * 1.001 && dt > 0) {
            val k = min(1.0, dt * 6)
            cam.cx += (s.width / 2.0 - cam.cx) * k
            cam.cy += (s.height / 2.0 - cam.cy) * k
        }
    }

    // =====================================================================================
    // Portali: entrata / uscita
    // =====================================================================================

    /** Rettangolo (schermo) in cui va disegnata l'opera figlia: "contain" dentro il portale. */
    private fun childScreenRect(c: Child, out: RectF): Boolean {
        val s = source ?: return false
        val cs = c.src ?: return false
        val ox = width / 2.0 - cam.cx * cam.scale
        val oy = height / 2.0 - cam.cy * cam.scale
        val pl = ox + c.portal.rect.x * s.width * cam.scale
        val pt = oy + c.portal.rect.y * s.height * cam.scale
        val pw = c.portal.rect.w * s.width * cam.scale
        val ph = c.portal.rect.h * s.height * cam.scale
        val k = min(pw / cs.width, ph / cs.height)
        val w = cs.width * k; val h = cs.height * k
        out.set((pl + (pw - w) / 2).toFloat(), (pt + (ph - h) / 2).toFloat(), (pl + (pw + w) / 2).toFloat(), (pt + (ph + h) / 2).toFloat())
        return true
    }

    private fun checkPortals(zoomingIn: Boolean) {
        val s = source ?: return
        // uscita: troppo lontani dentro un'opera figlia
        if (stack.isNotEmpty() && cam.scale < fitScale() * 0.72) { exitPortal(); return }
        if (!zoomingIn) return
        for (c in children) {
            if (!childReady(c)) continue           // quadro non ancora arrivato: entrare darebbe schermo nero
            if (!childScreenRect(c, tmpRect)) continue
            val coversCenter = tmpRect.contains(width / 2f, height / 2f)
            if (coversCenter && (tmpRect.width() >= width * 0.92f || tmpRect.height() >= height * 0.92f)) {
                enterPortal(c, RectF(tmpRect), s)
                return
            }
        }
    }

    private fun enterPortal(c: Child, r: RectF, parentSrc: TileSource) {
        val a = artwork ?: return
        val cs = c.src ?: return
        val ca = c.art ?: return
        val k = r.width() / cs.width.toDouble()
        val ccx = (width / 2.0 - r.left) / k
        val ccy = (height / 2.0 - r.top) / k
        stack.addLast(Frame(a, parentSrc, c.portal, c))
        if (stack.size > 12) stack.removeFirst()
        val fl = flight; val fd = flightDone
        setCurrent(ca, cs)
        cam.cx = ccx; cam.cy = ccy; cam.scale = k
        if (fl != null) { flight = null; flightDone = null; fd?.invoke() }
    }

    private fun exitPortal() {
        val f = stack.removeLastOrNull() ?: return
        val cs = source ?: return
        // rettangolo attuale dell'opera figlia sullo schermo
        val left = width / 2.0 - cam.cx * cam.scale
        val top = height / 2.0 - cam.cy * cam.scale
        val cwScreen = cs.width * cam.scale
        // rettangolo "contain" della figlia dentro il portale, in pixel del padre
        val pr = f.portal.rect
        val pw = pr.w * f.src.width; val ph = pr.h * f.src.height
        val k = min(pw / cs.width, ph / cs.height)
        val rw = cs.width * k; val rh = cs.height * k
        val rl = pr.x * f.src.width + (pw - rw) / 2
        val rt = pr.y * f.src.height + (ph - rh) / 2
        val ps = cwScreen / rw
        f.child.src = cs
        setCurrent(f.art, f.src)
        children.firstOrNull { it.portal == f.portal }?.let { it.src = cs; it.art = f.child.art }
        cam.scale = ps
        cam.cx = rl + (width / 2.0 - left) / ps
        cam.cy = rt + (height / 2.0 - top) / ps
    }

    /** Torna indietro di un livello di finestra (tasto INDIETRO a vista d'insieme). */
    fun popPortal(): Boolean {
        if (stack.isEmpty()) return false
        // anima un'uscita: zoom out fino alla soglia, poi exitPortal scatta da solo
        flyTo(Cam(cam.cx, cam.cy, fitScale() * 0.6), speed = 1.5, maxDur = 1.2)
        return true
    }

    val isAtOverview: Boolean get() = cam.scale <= fitScale() * 1.05

    /** Il quadro della finestra ha almeno il livello più grossolano in memoria. */
    private fun childReady(c: Child): Boolean {
        val cs = c.src ?: return false
        val k = cs.maxK
        for (y in 0 until cs.rows(k)) for (x in 0 until cs.cols(k)) if (app.memory[cs.key(k, x, y)] == null) return false
        return true
    }

    /** Per lo slideshow: la finestra [p] dell'opera corrente è pronta per entrarci. */
    fun portalReady(p: Portal): Boolean = children.firstOrNull { it.portal == p }?.let { childReady(it) } == true

    private fun ensureChild(c: Child) {
        if (c.src != null || c.loading) return
        c.loading = true
        scope.launch {
            val ca = app.catalog.resolveRef(c.portal.to, c.portal.label)
            c.art = ca
            c.src = ca?.let { app.sources.get(it) }
            c.loading = false
            invalidate()
        }
    }

    // =====================================================================================
    // Disegno
    // =====================================================================================

    override fun onDraw(canvas: Canvas) {
        val s = source
        if (s == null) {
            canvas.drawText("Caricamento…", width / 2f, height / 2f, textPaint)
            return
        }
        reqs.clear()
        fading = false
        val ox = width / 2.0 - cam.cx * cam.scale
        val oy = height / 2.0 - cam.cy * cam.scale

        // dentro una finestra: disegna anche il quadro esterno attorno (solo livelli grossolani)
        stack.lastOrNull()?.let { f ->
            val pr = f.portal.rect
            val pw = pr.w * f.src.width; val ph = pr.h * f.src.height
            val k = min(pw / s.width, ph / s.height)
            val rl = pr.x * f.src.width + (pw - s.width * k) / 2
            val rt = pr.y * f.src.height + (ph - s.height * k) / 2
            val ps = cam.scale / k
            drawSource(canvas, f.src, ox - rl * ps, oy - rt * ps, ps, 255, 30f, coarseOnly = true)
        }

        drawSource(canvas, s, ox, oy, cam.scale, 255, 0f)

        // finestre
        for (c in children) {
            val pr = c.portal.rect
            val l = (ox + pr.x * s.width * cam.scale).toFloat()
            val t = (oy + pr.y * s.height * cam.scale).toFloat()
            val r = l + (pr.w * s.width * cam.scale).toFloat()
            val b = t + (pr.h * s.height * cam.scale).toFloat()
            if (r < 0 || b < 0 || l > width || t > height) continue
            val size = max(r - l, b - t)
            if (size > 90) ensureChild(c)
            val cs = c.src
            if (cs != null && childScreenRect(c, tmpRect)) {
                val alpha = (smooth((size - 140.0) / 420.0) * 255).roundToInt()
                if (alpha > 0) {
                    canvas.save()
                    canvas.clipRect(tmpRect)
                    val k = tmpRect.width() / cs.width.toDouble()
                    drawSource(canvas, cs, tmpRect.left.toDouble(), tmpRect.top.toDouble(), k, alpha, 12f)
                    canvas.restore()
                }
            }
            if (showPortalHints && !autoPlay && size > 36 && size < max(width, height) * 0.8f) {
                hintPaint.alpha = (200 * (1 - smooth((size - 300.0) / 600.0))).roundToInt().coerceIn(40, 200)
                canvas.drawRect(l, t, r, b, hintPaint)
            }
        }

        decorate?.invoke(canvas)

        if (crosshair) {
            val cx = width / 2f; val cy = height / 2f; val g = 26f
            for (p in arrayOf(crossShadow, crossPaint)) {
                canvas.drawCircle(cx, cy, g, p)
                canvas.drawLine(cx - g * 1.8f, cy, cx - g * 0.6f, cy, p)
                canvas.drawLine(cx + g * 0.6f, cy, cx + g * 1.8f, cy, p)
                canvas.drawLine(cx, cy - g * 1.8f, cx, cy - g * 0.6f, p)
                canvas.drawLine(cx, cy + g * 0.6f, cx, cy + g * 1.8f, p)
            }
        }

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
    private fun drawSource(canvas: Canvas, src: TileSource, ox: Double, oy: Double, sc: Double, alpha: Int, basePrio: Float, coarseOnly: Boolean = false) {
        val ideal = floor(ln(1.0 / sc) / ln(2.0) + 1e-9).toInt()
        val kMin = src.minK(); val kMax = src.maxK
        var kIdeal = ideal.coerceIn(kMin, kMax)
        if (coarseOnly) kIdeal = min(kMax, kIdeal + 2)

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
        if (!coarseOnly) {
            want(kIdeal, 1, basePrio + 20f)
            if (zv > 0.1 && kIdeal - 1 >= kMin) {
                val rg = range(kIdeal - 1, 0)
                val midX = (rg[0] + rg[1]) / 2; val midY = (rg[2] + rg[3]) / 2
                for (y in max(rg[2], midY - 1)..min(rg[3], midY + 1)) for (x in max(rg[0], midX - 1)..min(rg[1], midX + 1))
                    reqs += TileReq(src, kIdeal - 1, x, y, basePrio + 40f)
            }
        }
    }

    // =====================================================================================
    // Utilità per gioco/overlay
    // =====================================================================================

    fun screenRectOf(n: NRect, out: RectF): RectF {
        val s = source ?: return out.apply { setEmpty() }
        val ox = width / 2.0 - cam.cx * cam.scale
        val oy = height / 2.0 - cam.cy * cam.scale
        out.set(
            (ox + n.x * s.width * cam.scale).toFloat(), (oy + n.y * s.height * cam.scale).toFloat(),
            (ox + (n.x + n.w) * s.width * cam.scale).toFloat(), (oy + (n.y + n.h) * s.height * cam.scale).toFloat(),
        )
        return out
    }

    /** Punto al centro dello schermo (mirino) in coordinate normalizzate. */
    fun centerNormalized(): PointF {
        val s = source ?: return PointF(0.5f, 0.5f)
        return PointF((cam.cx / s.width).toFloat(), (cam.cy / s.height).toFloat())
    }

    /** Area visibile in coordinate normalizzate (per creare obiettivi). */
    fun visibleNormalized(): NRect {
        val s = source ?: return NRect(0f, 0f, 1f, 1f)
        val w = width / cam.scale / s.width; val h = height / cam.scale / s.height
        return NRect((cam.cx / s.width - w / 2).toFloat(), (cam.cy / s.height - h / 2).toFloat(), w.toFloat(), h.toFloat())
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
    }
}
