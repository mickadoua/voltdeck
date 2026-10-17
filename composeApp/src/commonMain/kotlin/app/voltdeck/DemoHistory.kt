package app.voltdeck

import app.voltdeck.simulator.SimulatedPlant
import app.voltdeck.storage.DeviceStore
import app.voltdeck.storage.Level
import app.voltdeck.storage.MinuteAggregator
import app.voltdeck.storage.Rollup
import app.voltdeck.storage.db.VoltdeckDatabase
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

const val DEMO_HISTORY_DAYS = 60

/**
 * Remplit la base de démo avec [days] jours simulés avant [until] (heures, puis jours et mois), pour que les vues
 * Semaine et Mois aient de quoi montrer. Les équipements sont enregistrés dès le début de cet historique.
 */
fun seedDemoHistory(db: VoltdeckDatabase, devices: List<RegisteredDevice>, until: Instant, zone: TimeZone, days: Int = DEMO_HISTORY_DAYS) {
    if (days <= 0) return
    val from = until.toLocalDateTime(zone).date.minus(days, DateTimeUnit.DAY).atStartOfDayIn(zone)
    db.transaction {
        val store = DeviceStore(db)
        devices.forEach { store.register(it.id, it.driverId, it.role, it.name, it.platformId.value, it.model, from) }
        val plant = SimulatedPlant(DEMO_SEED, from, zone, ::demoWeather)
        val aggregator = MinuteAggregator(db, Level.HOUR)
        var at = from
        while (at < until) {
            plant.readingsAt(at).forEach(aggregator::add)
            at += 1.minutes
        }
        aggregator.flushAll()
    }
    Rollup(db).rebuild(until, zone)
}

/** Ciel du jour, de couvert (0,3) à clair (1), tiré de la date : le même historique à chaque lancement. */
internal fun demoWeather(day: LocalDate): Double = Random(DEMO_SEED + day.toEpochDays().toLong()).nextDouble(0.3, 1.0)
