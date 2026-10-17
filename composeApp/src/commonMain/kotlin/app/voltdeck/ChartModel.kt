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

/** Colonnes d'énergie : un créneau sans mesure vaut 0 (colonne vide) ; le tableau, lui, affiche « — ». */
class EnergyColumns(val produced: List<Double>, val consumed: List<Double>)

fun energyColumns(slots: List<Instant>, buckets: List<EnergyBucket>): EnergyColumns {
    val byStart = buckets.associateBy { it.start }
    return EnergyColumns(
        slots.map { byStart[it]?.producedWh?.coerceAtLeast(0.0) ?: 0.0 },
        slots.map { byStart[it]?.consumedWh?.coerceAtLeast(0.0) ?: 0.0 },
    )
}

private const val SECONDS_PER_HOUR = 3600.0
