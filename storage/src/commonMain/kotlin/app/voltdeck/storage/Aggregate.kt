package app.voltdeck.storage

import app.voltdeck.core.DeviceId
import app.voltdeck.core.Metric
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Instant

/** Statistiques d'une série sur une période : `count`, `min`, `max`, `sum`, `last`. */
data class AggStats(val count: Long, val min: Double, val max: Double, val sum: Double, val last: Double) {
    val mean: Double get() = sum / count

    /** Combine avec [later], la période (ou les mesures) qui suit dans le temps : sa valeur `last` l'emporte. */
    fun merge(later: AggStats) = AggStats(
        count = count + later.count,
        min = min(min, later.min),
        max = max(max, later.max),
        sum = sum + later.sum,
        last = later.last,
    )

    companion object {
        fun of(value: Double) = AggStats(1, value, value, value, value)

        /** Combine des périodes déjà triées dans le temps. */
        fun combine(parts: List<AggStats>): AggStats? = parts.reduceOrNull { a, b -> a.merge(b) }
    }
}

data class SeriesKey(val deviceId: DeviceId, val metric: Metric, val channel: Int = 0)

/** Une période : [start] est le début de la minute ou de l'heure (UTC), ou minuit local du jour ou du mois. */
data class AggRow(val start: Instant, val stats: AggStats)

/** Métriques que seul le stockage produit (énergie intégrée) : un échantillon de pilote portant l'une d'elles est ignoré. */
val DERIVED_METRICS: Set<Metric> = setOf(
    Metric.ENERGY_IN_WH,
    Metric.ENERGY_OUT_WH,
    Metric.CHARGE_IN_AH,
    Metric.CHARGE_OUT_AH,
    Metric.PV_ENERGY_WH,
)
