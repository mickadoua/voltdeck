package app.voltdeck

import app.voltdeck.storage.AggRow
import app.voltdeck.storage.EnergyBucket
import kotlin.time.Instant

/** Tronçon continu d'une courbe : x en heures depuis le début de la période, y = valeur moyenne. */
class Segment(val x: List<Double>, val y: List<Double>)

/**
 * Découpe une série en tronçons. Un trou de collecte (plus d'un pas et demi entre deux points) coupe la courbe :
 * rien n'est interpolé sur ce qui n'a pas été mesuré.
 */
fun segmentsOf(rows: List<AggRow>, from: Instant): List<Segment> {
    if (rows.isEmpty()) return emptyList()
    val step = rows.zipWithNext { a, b -> b.start - a.start }.filter { it.isPositive() }.minOrNull()
    val segments = mutableListOf<Segment>()
    var xs = mutableListOf<Double>()
    var ys = mutableListOf<Double>()
    var previous: AggRow? = null
    rows.forEach { row ->
        val gap = previous != null && step != null && row.start - previous!!.start > step * 1.5
        if (gap) {
            segments += Segment(xs, ys)
            xs = mutableListOf()
            ys = mutableListOf()
        }
        xs += (row.start - from).inWholeSeconds / SECONDS_PER_HOUR
        ys += row.stats.mean
        previous = row
    }
    segments += Segment(xs, ys)
    return segments
}

/**
 * Colonnes d'énergie. [produced] et [consumed] gardent la valeur lue (`null` = pas de mesure, à lire « — »,
 * comme le tableau) ; seules [producedDrawn] et [consumedDrawn] servent au dessin : colonne vide et jamais sous l'axe.
 */
class EnergyColumns(val produced: List<Double?>, val consumed: List<Double?>) {
    val producedDrawn: List<Double> = produced.map { (it ?: 0.0).coerceAtLeast(0.0) }
    val consumedDrawn: List<Double> = consumed.map { (it ?: 0.0).coerceAtLeast(0.0) }
}

fun energyColumns(slots: List<Instant>, buckets: List<EnergyBucket>): EnergyColumns {
    val byStart = buckets.associateBy { it.start }
    return EnergyColumns(
        slots.map { byStart[it]?.producedWh },
        slots.map { byStart[it]?.consumedWh },
    )
}

private const val SECONDS_PER_HOUR = 3600.0
