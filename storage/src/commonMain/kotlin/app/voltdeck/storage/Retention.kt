package app.voltdeck.storage

import app.voltdeck.storage.db.VoltdeckDatabase
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Rétention (VOL-1 §5.4) : les minutes sont conservées [minuteRetention] (30 jours), le reste sans limite.
 *
 * - La coupure est alignée sur l'heure : une heure n'est jamais à moitié purgée, donc un recalcul d'heure ne peut
 *   pas réduire une heure à ses seules minutes survivantes.
 * - **Rien n'est purgé tant que l'heure n'est pas consolidée** : on ne supprime que les minutes antérieures à la fin de
 *   la dernière heure de `agg_hour`. Si le roll-up est en retard, les minutes attendent.
 */
class Retention(
    private val database: VoltdeckDatabase,
    private val minuteRetention: Duration = MINUTE_RETENTION,
) {
    /** Supprime les minutes périmées à l'instant [now] et renvoie leur nombre. */
    fun purge(now: Instant): Long {
        val lastHour = database.hourQueries.hourBounds().executeAsOne().last_bucket ?: return 0
        val cutoff = (now - minuteRetention).hourStart()
        val limit = minOf(cutoff, lastHour + HOUR_SECONDS)
        var deleted = 0L
        database.transaction {
            val before = database.minuteQueries.countMinutes().executeAsOne()
            database.minuteQueries.deleteMinutesBefore(limit)
            deleted = before - database.minuteQueries.countMinutes().executeAsOne()
        }
        return deleted
    }
}

/** Entretien périodique : roll-up, puis rétention (dans cet ordre, pour ne jamais purger une minute non consolidée). */
class Maintenance(database: VoltdeckDatabase, minuteRetention: Duration = MINUTE_RETENTION) {
    private val rollup = Rollup(database)
    private val retention = Retention(database, minuteRetention)

    fun run(now: Instant, zone: TimeZone): RollupResult = rollup.run(now, zone).also { retention.purge(now) }

    fun rebuild(now: Instant, zone: TimeZone): RollupResult = rollup.rebuild(now, zone)
}
