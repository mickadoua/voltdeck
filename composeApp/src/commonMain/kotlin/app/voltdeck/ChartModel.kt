package app.voltdeck

import app.voltdeck.storage.AggRow
import app.voltdeck.storage.EnergyBucket
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
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

/**
 * Pas de l'axe horizontal, en minutes : l'écart habituel (médiane) entre deux points d'un même tronçon, 1 si aucun tronçon n'en a deux.
 * Vico déduirait le pas du PGCD des écarts, instable dès qu'un écart sort du lot (changement d'heure). Un pas fixé à 1 minute, lui,
 * donnait à une série horaire 60 fois sa largeur : le zoom maximal tombait à quelques minutes d'une semaine, et aux limites du
 * dessin (coordonnées à des dizaines de millions de pixels) pour un an.
 */
fun xStepOf(segments: List<Segment>): Double {
    val gaps = segments.flatMap { it.x.zipWithNext { a, b -> b - a } }.filter { it > 0.0 }.sorted()
    return gaps.getOrNull(gaps.size / 2) ?: 1.0
}

/** Fenêtre la plus étroite qu'on puisse atteindre au pincement : [MIN_VISIBLE_POINTS] points, et au moins une heure. */
fun closestWindowMinutes(step: Double): Double = maxOf(step * MIN_VISIBLE_POINTS, 60.0)

const val MIN_VISIBLE_POINTS = 12

/** L'instant représenté par un x de [Segment] (minutes depuis [origin]). */
fun instantAt(origin: Instant, minutes: Double): Instant = origin + minutes.roundToLong().minutes

/**
 * Jour porté par un repère de l'axe posé toutes les 1 440 minutes absolues depuis [origin] (un minuit local) : le
 * minuit local le plus proche. Un jour de changement d'heure dure 23 ou 25 h, le repère suivant tombe alors à 23 h
 * ou 1 h : la date lue à l'instant exact serait celle de la veille ou en double.
 */
fun dayTick(origin: Instant, minutes: Double, zone: TimeZone): LocalDate {
    val t = instantAt(origin, minutes).toLocalDateTime(zone)
    return if (t.hour >= 12) t.date.plus(1, DateTimeUnit.DAY) else t.date
}

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
