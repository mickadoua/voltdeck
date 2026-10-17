package app.voltdeck.storage

import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Metric
import app.voltdeck.storage.db.VoltdeckDatabase
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * Énergie d'une période, en Wh. `null` = inconnu pour cette période (trou de collecte, source sans mesure), jamais 0.
 * [batteryNetWh] > 0 : la batterie s'est chargée. [consumedWh] = [producedWh] − [batteryNetWh], comme dans [SystemStats.consumption].
 */
data class EnergyBucket(val start: Instant, val producedWh: Double?, val batteryNetWh: Double?, val consumedWh: Double?)

/** Énergie heure par heure ou jour par jour, pour les graphiques de statistiques. */
class EnergyHistory(
    database: VoltdeckDatabase,
    private val measuresBattery: (DeviceId) -> Boolean = { true },
) {
    private val devices = DeviceStore(database)
    private val reader = AggregateReader(database)

    /** [level] : [Level.HOUR] ou [Level.DAY]. Une période sans aucune mesure n'apparaît pas : c'est un trou, pas un zéro. */
    fun buckets(level: Level, from: Instant, to: Instant, zone: TimeZone): List<EnergyBucket> {
        require(level == Level.HOUR || level == Level.DAY) { "niveau $level non pris en charge" }
        fun rows(id: DeviceId, metric: Metric): Map<Instant, Double> {
            val key = SeriesKey(id, metric)
            val list = if (level == Level.HOUR) reader.hourly(key, from, to) else reader.daily(key, from, to, zone)
            return list.associate { it.start to it.stats.sum }
        }
        val active = devices.all().filter { it.isActiveDuring(from, to) }
        val chargers = active.filter { it.role == DeviceRole.SOLAR_CHARGER }
        val converters = active.filter { it.role == DeviceRole.DCDC }
        val flow = (active.filter { it.role == DeviceRole.SHUNT && measuresBattery(it.id) } + active.filter { it.role == DeviceRole.BATTERY })
            .firstOrNull { rows(it.id, Metric.ENERGY_IN_WH).isNotEmpty() }

        val produced = chargers.map { rows(it.id, Metric.ENERGY_IN_WH) to rows(it.id, Metric.ENERGY_OUT_WH) }
        val flowIn = flow?.let { rows(it.id, Metric.ENERGY_IN_WH) }.orEmpty()
        val flowOut = flow?.let { rows(it.id, Metric.ENERGY_OUT_WH) }.orEmpty()
        val starts = (produced.flatMap { it.first.keys } + flowIn.keys).toSortedSet()

        return starts.map { start ->
            val producedWh = if (chargers.isNotEmpty() && produced.all { start in it.first }) {
                produced.sumOf { (energyIn, energyOut) -> energyIn.getValue(start) - (energyOut[start] ?: 0.0) }
            } else {
                null
            }
            val netWh = flowIn[start]?.let { it - (flowOut[start] ?: 0.0) }
            val consumed = if (producedWh != null && netWh != null && converters.isEmpty()) {
                (producedWh - netWh).takeIf { it >= -SystemStats.NEGATIVE_TOLERANCE_WH }?.coerceAtLeast(0.0)
            } else {
                null
            }
            EnergyBucket(start, producedWh, netWh, consumed)
        }
    }
}
