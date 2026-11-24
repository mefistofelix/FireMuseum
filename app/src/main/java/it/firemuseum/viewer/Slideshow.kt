package it.firemuseum.viewer

import it.firemuseum.model.Artwork
import it.firemuseum.model.NRect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * Regia automatica "documentario":
 *   vista d'insieme con didascalia -> 3–5 voli lenti sui dettagli (obiettivi noti o punti casuali)
 *   -> se l'opera ha finestre, a volte ci entra ("viaggio infinito") -> altrimenti opera successiva.
 * Qualsiasi tasto di movimento la interrompe; ▶︎⏸ la riprende.
 */
class Slideshow(
    private val view: DeepZoomView,
    private val nextArtwork: () -> Artwork?,
    private val secondsPerArtwork: () -> Int,
    private val onCaption: (Artwork) -> Unit,
    private val onState: (Boolean) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    var infiniteJourney = false
    val running: Boolean get() = job?.isActive == true

    fun start() {
        if (running) return
        job = scope.launch {
            onState(true)
            try {
                while (true) {
                    val a = view.artwork ?: break
                    onCaption(a)
                    val enteredPortal = playArtwork(a)
                    if (!enteredPortal) {
                        val n = nextArtwork() ?: break
                        val ok = suspendCancellableCoroutine<Boolean> { c -> view.open(n) { c.resume(it) } }
                        if (!ok) continue
                        delay(600)
                    }
                }
            } finally {
                onState(false)
            }
        }
    }

    fun stop() { job?.cancel(); job = null; view.cancelFlight() }

    private suspend fun fly(r: NRect, fill: Double, speed: Double, maxDur: Double = 7.0) =
        suspendCancellableCoroutine<Unit> { c ->
            view.flyToRect(r, fill, speed, maxDur) { if (c.isActive) c.resume(Unit) }
            c.invokeOnCancellation { view.cancelFlight() }
        }

    private suspend fun flyHome() = suspendCancellableCoroutine<Unit> { c ->
        view.flyTo(view.fitCam(), speed = 0.5) { if (c.isActive) c.resume(Unit) }
        c.invokeOnCancellation { view.cancelFlight() }
    }

    /** true se l'opera è cambiata entrando in una finestra (allora si continua da lì). */
    private suspend fun playArtwork(a: Artwork): Boolean {
        val total = secondsPerArtwork().coerceIn(10, 600) * 1000L
        val t0 = System.currentTimeMillis()
        if (!view.isAtOverview) flyHome()
        delay(3500)

        val shots = pickShots(a)
        for (r in shots) {
            if (System.currentTimeMillis() - t0 > total) break
            fly(r, fill = 0.55 + Random.nextDouble() * 0.25, speed = 0.30)
            // lenta deriva "Ken Burns" sul dettaglio
            val drift = NRect(r.x + (Random.nextFloat() - 0.5f) * r.w * 0.5f, r.y + (Random.nextFloat() - 0.5f) * r.h * 0.5f, r.w * 0.85f, r.h * 0.85f)
            fly(drift, fill = 0.6, speed = 0.08, maxDur = 5.0)
            if (view.artwork !== a) return true
        }

        if ((infiniteJourney || Random.nextFloat() < 0.35f) && a.portals.isNotEmpty()) {
            val p = a.portals.random()
            // vola verso la finestra e oltre: l'ingresso avviene da solo quando la riempie
            fly(p.rect, fill = 1.15, speed = 0.35, maxDur = 8.0)
            delay(300)
            if (view.artwork !== a) return true
        }
        flyHome()
        delay(1500)
        return false
    }

    private fun pickShots(a: Artwork): List<NRect> {
        val n = 3 + Random.nextInt(3)
        val known = a.targets.filter { it.rect.w < 0.5f && it.rect.h < 0.5f }.shuffled().take(n).map { t ->
            // inquadra un po' di contesto attorno all'obiettivo
            val m = 1.8f
            NRect(t.rect.cx - t.rect.w * m / 2, t.rect.cy - t.rect.h * m / 2, t.rect.w * m, t.rect.h * m)
        }
        if (known.size >= n) return known
        val aspect = if (a.height > 0) a.width.toFloat() / a.height else 1.4f
        val random = List(n - known.size) {
            val w = 0.06f + Random.nextFloat() * 0.18f
            val h = w * aspect * 9f / 16f
            NRect(0.12f + Random.nextFloat() * (0.76f - w), 0.12f + Random.nextFloat() * (0.76f - h), w, h)
        }
        return (known + random).shuffled()
    }
}
