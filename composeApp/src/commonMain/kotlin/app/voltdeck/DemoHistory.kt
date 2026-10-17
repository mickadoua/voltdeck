package app.voltdeck

import app.voltdeck.simulator.SimulatedPlant
import app.voltdeck.storage.Level
import app.voltdeck.storage.MinuteAggregator
import app.voltdeck.storage.Rollup
import app.voltdeck.storage.db.VoltdeckDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

const val DEMO_HISTORY_DAYS = 60

/** Début de l'historique de démo : minuit, [days] jours avant [until]. */
fun demoHistoryStart(until: Instant, zone: TimeZone, days: Int = DEMO_HISTORY_DAYS): Instant =
    until.toLocalDateTime(zone).date.minus(days, DateTimeUnit.DAY).atStartOfDayIn(zone)

/**
 * Remplit la base de démo de [demoHistoryStart] à minuit du jour de [until] (heures, puis jours et mois), pour que les vues
 * Semaine et Mois aient de quoi montrer. Le jour de [until] reste à la collecte en direct, sinon son total mélangerait les
 * deux. Les équipements doivent déjà y être enregistrés depuis ce début. Une transaction par jour simulé : la collecte en
 * direct peut écrire entre deux, et une annulation n'abandonne que le jour en cours.
 */
suspend fun seedDemoHistory(db: VoltdeckDatabase, until: Instant, zone: TimeZone, days: Int = DEMO_HISTORY_DAYS) {
    if (days <= 0) return
    val job = currentCoroutineContext().job
    val plant = SimulatedPlant(DEMO_SEED, demoHistoryStart(until, zone, days), zone, ::demoWeather)
    val aggregator = MinuteAggregator(db, Level.HOUR)
    var at = demoHistoryStart(until, zone, days)
    val end = until.toLocalDateTime(zone).date.atStartOfDayIn(zone)
    while (at < end) {
        val dayEnd = minOf(at + 1.days, end)
        db.transaction {
            while (at < dayEnd) {
                job.ensureActive()
                plant.readingsAt(at).forEach(aggregator::add)
                at += 1.minutes
            }
        }
    }
    db.transaction { aggregator.flushAll() }
    Rollup(db).rebuild(until, zone)
}

/** Ciel du jour, de couvert (0,3) à clair (1), tiré de la date : le même historique à chaque lancement. */
internal fun demoWeather(day: LocalDate): Double = Random(DEMO_SEED + day.toEpochDays().toLong()).nextDouble(0.3, 1.0)
