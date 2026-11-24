package it.firemuseum.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import it.firemuseum.model.Artwork
import it.firemuseum.model.NRect
import it.firemuseum.model.Target
import it.firemuseum.viewer.DeepZoomView
import kotlin.math.max
import kotlin.math.sin

/**
 * "Cerca e trova" stile libro-gioco: un obiettivo alla volta ("Trova: il cane con il collare"),
 * mirino al centro dello schermo, OK per dire "è qui!".
 *  - Bisogna essere abbastanza vicini (l'obiettivo deve occupare ≥ 2,5% della larghezza schermo):
 *    niente vittorie "a caso" dalla vista d'insieme.
 *  - Indizi progressivi: 1) zona a parole, 2) riquadro lampeggiante largo, 3) volo automatico.
 *  - Punti: 100 per obiettivo, −25 per indizio, bonus velocità.
 */
class Game(val art: Artwork, private val view: DeepZoomView, maxTargets: Int = 10) {

    sealed class Result {
        class Found(val t: Target, val points: Int) : Result()
        class TooFar(val t: Target) : Result()
        object Miss : Result()
        object Done : Result()
    }

    val targets: List<Target> = run {
        val usable = art.targets.filter { it.rect.w in 0.0005f..0.35f && it.rect.h in 0.0005f..0.35f }
        // prima quelli scritti a mano (catalogo/utente), poi quelli da Wikidata
        (usable.filter { it.origin != "wikidata" }.shuffled() + usable.filter { it.origin == "wikidata" }.shuffled())
            .distinctBy { it.label.lowercase() }
            .take(maxTargets)
    }
    private val found = LinkedHashSet<Target>()
    private val skipped = HashSet<Target>()
    private var hintLevel = 0
    private var targetStart = SystemClock.uptimeMillis()
    private var flashUntil = 0L
    private var flashRect: NRect? = null
    var score = 0; private set

    val current: Target? get() = targets.firstOrNull { it !in found && it !in skipped }
    val progress: String get() = "${found.size}/${targets.size}"
    val isOver: Boolean get() = current == null

    private val okPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = 0xFF5BD17A.toInt() }
    private val okFill = Paint().apply { color = 0x335BD17A }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = 0xFFE8B04B.toInt() }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26f; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
    private val r = RectF()

    fun check(): Result {
        val t0 = current ?: return Result.Done
        val p = view.centerNormalized()
        val vis = view.visibleNormalized()
        // tolleranza: mezzo mirino (≈ 2,5% dello schermo)
        val tolX = vis.w * 0.025f
        val hit = targets.filter { it !in found && it.rect.contains(p.x, p.y, tolX) }
            .minByOrNull { if (it == t0) 0 else 1 } ?: return Result.Miss
        if (hit.rect.w / vis.w < 0.025f) return Result.TooFar(hit)
        found += hit
        val secs = (SystemClock.uptimeMillis() - targetStart) / 1000
        val pts = max(10, 100 - hintLevel * 25 + (if (secs < 30) 30 else if (secs < 90) 10 else 0))
        score += pts
        hintLevel = 0
        targetStart = SystemClock.uptimeMillis()
        flashRect = hit.rect; flashUntil = SystemClock.uptimeMillis() + 1500
        return Result.Found(hit, pts)
    }

    fun skip() { current?.let { skipped += it }; hintLevel = 0; targetStart = SystemClock.uptimeMillis() }

    /** Restituisce il testo dell'indizio e, al livello 3, fa volare la vista sull'obiettivo. */
    fun hint(): String? {
        val t = current ?: return null
        hintLevel = (hintLevel + 1).coerceAtMost(3)
        return when (hintLevel) {
            1 -> t.hint ?: "Cerca ${zone(t.rect)}."
            2 -> "Guarda nel riquadro giallo!"
            else -> { view.flyToRect(t.rect, fill = 0.25, speed = 0.8); "Eccolo, quasi… premi OK sul mirino!" }
        }
    }

    private fun zone(n: NRect): String {
        val v = when { n.cy < 0.33f -> "in alto"; n.cy > 0.66f -> "in basso"; else -> "al centro" }
        val h = when { n.cx < 0.33f -> "a sinistra"; n.cx > 0.66f -> "a destra"; else -> "" }
        return listOf(v, h).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Disegna obiettivi trovati, riquadro-indizio e lampo di conferma. */
    fun draw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        for (t in found) {
            view.screenRectOf(t.rect, r)
            if (r.width() < 6) continue
            c.drawRect(r, okFill); c.drawRect(r, okPaint)
            if (r.width() > 80) c.drawText("✓ " + t.label, r.left, r.top - 8, labelPaint)
        }
        val t = current
        if (t != null && hintLevel >= 2) {
            // riquadro largo 5× l'obiettivo, che pulsa
            val m = 5f
            val big = NRect(t.rect.cx - t.rect.w * m / 2, t.rect.cy - t.rect.h * m / 2, t.rect.w * m, t.rect.h * m)
            view.screenRectOf(big, r)
            hintPaint.alpha = (150 + 100 * sin(now / 180.0)).toInt().coerceIn(40, 255)
            c.drawRect(r, hintPaint)
            view.postInvalidateOnAnimation()
        }
        flashRect?.let { fr ->
            if (now < flashUntil) {
                view.screenRectOf(fr, r)
                val grow = (1 - (flashUntil - now) / 1500f) * 40
                r.inset(-grow, -grow)
                okPaint.alpha = ((flashUntil - now) / 1500f * 255).toInt()
                c.drawRect(r, okPaint)
                okPaint.alpha = 255
                view.postInvalidateOnAnimation()
            } else flashRect = null
        }
    }
}
