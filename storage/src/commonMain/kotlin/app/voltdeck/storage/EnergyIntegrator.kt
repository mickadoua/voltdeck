package app.voltdeck.storage

import kotlin.time.Instant

/** Écart maximal entre deux mesures pour intégrer l'intervalle : au-delà, c'est un trou, jamais interpolé (VOL-1 §5.2). */
internal const val MAX_INTEGRATION_GAP_SECONDS = 60.0

/**
 * Intégrale (méthode des trapèzes) d'une grandeur échantillonnée, découpée par minute.
 *
 * Le segment entre deux mesures est linéaire : on le coupe aux frontières de minute (valeur interpolée sur le segment
 * lui-même, pas sur des moyennes) et, s'il change de signe, au passage par zéro. On obtient ainsi la part positive
 * (énergie entrante) et la part négative (énergie sortante, en valeur absolue) de chaque minute, exactes pour le modèle linéaire.
 */
internal class EnergyIntegrator {
    private var previousAt: Instant? = null
    private var previousValue = 0.0

    /** Oublie la mesure précédente : la chaîne est coupée (mesure manquante dans un relevé). */
    fun reset() {
        previousAt = null
    }

    /**
     * Ajoute la mesure ([at], [value]). Si l'intervalle depuis la précédente est de 60 s au plus, appelle [sink] pour
     * chaque minute traversée avec (début de minute en secondes epoch, aire positive, aire négative) en valeur × secondes.
     */
    fun add(at: Instant, value: Double, sink: (minuteStart: Long, positive: Double, negative: Double) -> Unit) {
        val before = previousAt
        val beforeValue = previousValue
        previousAt = at
        previousValue = value
        if (before == null) return
        val gap = seconds(at) - seconds(before)
        if (gap <= 0.0 || gap > MAX_INTEGRATION_GAP_SECONDS) return
        integrate(seconds(before), beforeValue, seconds(at), value, sink)
    }

    private fun seconds(at: Instant): Double = at.epochSeconds + at.nanosecondsOfSecond / 1e9

    private fun integrate(t0: Double, v0: Double, t1: Double, v1: Double, sink: (Long, Double, Double) -> Unit) {
        var cursor = t0
        var cursorValue = v0
        var minute = floorTo(kotlin.math.floor(t0).toLong(), MINUTE_SECONDS)
        while (cursor < t1) {
            val boundary = (minute + MINUTE_SECONDS).toDouble()
            val end = if (t1 <= boundary) t1 else boundary
            val endValue = v0 + (v1 - v0) * (end - t0) / (t1 - t0)
            piece(cursor, cursorValue, end, endValue) { positive, negative -> sink(minute, positive, negative) }
            cursor = end
            cursorValue = endValue
            minute += MINUTE_SECONDS
        }
    }

    private fun piece(ta: Double, va: Double, tb: Double, vb: Double, out: (Double, Double) -> Unit) {
        val dt = tb - ta
        when {
            va >= 0.0 && vb >= 0.0 -> out((va + vb) / 2.0 * dt, 0.0)
            va <= 0.0 && vb <= 0.0 -> out(0.0, -(va + vb) / 2.0 * dt)
            va > 0.0 -> {
                val positiveSpan = dt * va / (va - vb)
                out(va * positiveSpan / 2.0, -vb * (dt - positiveSpan) / 2.0)
            }
            else -> {
                val negativeSpan = dt * -va / (vb - va)
                out(vb * (dt - negativeSpan) / 2.0, -va * negativeSpan / 2.0)
            }
        }
    }
}
