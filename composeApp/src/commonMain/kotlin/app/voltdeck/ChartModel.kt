package app.voltdeck

import app.voltdeck.storage.AggRow
import app.voltdeck.storage.EnergyBucket
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Tronçon continu d'une courbe : x en minutes entières depuis l'origine de l'axe, y = valeur moyenne.
 * Vico refuse des x à plus de quatre décimales et déduit son pas du PGCD des écarts : des minutes entières
 * donnent un pas exact (1), alors que des heures fractionnaires (1/60) font échouer le tracé.
 */
class Segment(val x: List<Double>, val y: List<Double>)

/**
 * Découpe une série en tronçons. Un trou de collecte (plus d'un pas et demi entre deux points) coupe la courbe :
 * rien n'est interpolé sur ce qui n'a pas été mesuré.
 */
fun segmentsOf(rows: List<AggRow>, origin: Instant): List<Segment> {
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
        xs += ((row.start - origin).inWholeSeconds + SECONDS_PER_MINUTE / 2).floorDiv(SECONDS_PER_MINUTE).toDouble()
        ys += row.stats.mean
        previous = row
    }
    segments += Segment(xs, ys)
    return segments
}

/** L'instant représenté par un x de [Segment] (minutes depuis [origin]). */
fun instantAt(origin: Instant, minutes: Double): Instant = origin + minutes.roundToLong().minutes

/** Début de l'heure locale qui contient [t] : origine d'axe alignée sur des heures pleines. */
fun startOfHour(t: Instant, zone: TimeZone): Instant {
    val local = t.toLocalDateTime(zone)
    return LocalDateTime(local.year, local.month, local.day, local.hour, 0).toInstant(zone)
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

private const val SECONDS_PER_MINUTE = 60L
