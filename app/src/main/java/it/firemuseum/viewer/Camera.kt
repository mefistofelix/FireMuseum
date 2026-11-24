package it.firemuseum.viewer

import kotlin.math.abs
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tanh

/** Telecamera: centro in pixel immagine + scala (pixel schermo per pixel immagine). */
data class Cam(var cx: Double, var cy: Double, var scale: Double) {
    fun copyFrom(o: Cam) { cx = o.cx; cy = o.cy; scale = o.scale }
}

/**
 * Volo "smooth and efficient zooming and panning" (van Wijk & Nuij 2003):
 * per spostarsi lontano prima si allontana, poi trasla, poi si riavvicina — come Google Earth.
 * Usato da slideshow, indizi del gioco e "vai all'obiettivo".
 */
class Flight(from: Cam, to: Cam, viewW: Double, speed: Double = 1.0, minDur: Double = 0.35, maxDur: Double = 7.0) {
    private val vw = viewW
    private val c0x = from.cx; private val c0y = from.cy
    private val c1x = to.cx; private val c1y = to.cy
    private val w0 = viewW / from.scale
    private val w1 = viewW / to.scale
    private val u1 = sqrt((c1x - c0x) * (c1x - c0x) + (c1y - c0y) * (c1y - c0y))
    private val rho = 1.42
    private val r0: Double
    private val totalS: Double
    private val degenerate = u1 < 1e-6 * maxOf(w0, w1)
    val duration: Double

    init {
        if (degenerate) {
            r0 = 0.0
            totalS = abs(ln(w1 / w0)) / rho
        } else {
            fun b(i: Int): Double {
                val wi = if (i == 0) w0 else w1
                val sign = if (i == 0) 1 else -1
                return (w1 * w1 - w0 * w0 + sign * rho * rho * rho * rho * u1 * u1) / (2 * wi * rho * rho * u1)
            }
            fun r(i: Int): Double { val bi = b(i); return ln(-bi + sqrt(bi * bi + 1)) }
            r0 = r(0)
            totalS = (r(1) - r0) / rho
        }
        duration = (totalS * 0.55 / speed).coerceIn(minDur, maxDur)
    }

    /** t in [0,1] -> camera. Ease-in-out sul parametro di cammino. */
    fun at(t: Double, out: Cam) {
        val e = if (t >= 1) 1.0 else if (t <= 0) 0.0 else t * t * (3 - 2 * t)
        val s = e * totalS
        val w: Double
        val u: Double
        if (degenerate) {
            val k = if (w1 > w0) 1 else -1
            w = w0 * exp(k * rho * s)
            u = 0.0
            out.cx = c0x + (c1x - c0x) * e
            out.cy = c0y + (c1y - c0y) * e
        } else {
            w = w0 * cosh(r0) / cosh(rho * s + r0)
            u = w0 / (rho * rho) * cosh(r0) * tanh(rho * s + r0) - w0 / (rho * rho) * sinh(r0)
            val f = u / u1
            out.cx = c0x + (c1x - c0x) * f
            out.cy = c0y + (c1y - c0y) * f
        }
        out.scale = vw / w
        if (t >= 1) { out.cx = c1x; out.cy = c1y; out.scale = vw / w1 }
    }
}
