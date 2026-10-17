package app.voltdeck.storage

import app.voltdeck.storage.db.VoltdeckDatabase
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** Ce qu'un passage de [Rollup] a recalculé. */
data class RollupResult(val hours: Int, val days: Int, val months: Int)

/**
 * Roll-ups heure, jour et mois (VOL-1 §5.4).
 *
 * - **Idempotent** : une période est toujours *recalculée* depuis le niveau inférieur puis écrite par `INSERT OR REPLACE`,
 *   jamais incrémentée. Rejouer un roll-up redonne exactement les mêmes lignes.
 * - **Périodes closes seulement** : une heure est close à son terme ; un jour (ou un mois) local l'est quand son terme est
 *   atteint par la dernière heure consolidée. La période en cours se lit à la volée ([AggregateReader]).
 * - **Fuseau** : l'heure est en UTC ; jour et mois suivent le fuseau du téléphone au moment du roll-up, enregistré dans
 *   `tz_id`. Un jour de 25 h (retour à l'heure d'hiver) est borné par ses vrais minuits locaux. Les jours déjà consolidés
 *   gardent le fuseau avec lequel ils l'ont été ; seuls les jours recalculés prennent le fuseau courant.
 *   Limite connue : un fuseau à décalage de 30 ou 45 min a des heures à cheval, rattachées au jour où elles commencent.
 * - **Reprise** : le passage incrémental repart de la dernière heure consolidée (relue, car une ligne minute peut lui avoir
 *   été fusionnée depuis). Chaque niveau s'écrit dans une transaction : un arrêt en cours de route l'annule et le passage
 *   suivant la refait.
 */
class Rollup(private val database: VoltdeckDatabase) {
    fun run(now: Instant, zone: TimeZone): RollupResult {
        val hourTo = now.hourStart()
        val firstMinute = database.minuteQueries.minuteBounds().executeAsOne().first_bucket ?: return RollupResult(0, 0, 0)
        val lastHour = database.hourQueries.hourBounds().executeAsOne().last_bucket
        val hourFrom = if (lastHour == null) floorTo(firstMinute, HOUR_SECONDS) else lastHour
        return consolidate(hourFrom, hourTo, zone)
    }

    /** « Recalculer les statistiques » : refait toutes les heures qui ont encore des minutes, puis tous les jours et mois. */
    fun rebuild(now: Instant, zone: TimeZone): RollupResult {
        val hourTo = now.hourStart()
        val firstMinute = database.minuteQueries.minuteBounds().executeAsOne().first_bucket
        val firstHour = database.hourQueries.hourBounds().executeAsOne().first_bucket
        val from = listOfNotNull(firstMinute?.let { floorTo(it, HOUR_SECONDS) }, firstHour).minOrNull() ?: return RollupResult(0, 0, 0)
        val minutesFrom = firstMinute?.let { floorTo(it, HOUR_SECONDS) } ?: hourTo
        return consolidate(hourFrom = minOf(minutesFrom, hourTo), hourTo = hourTo, zone = zone, dayFrom = from)
    }

    private fun consolidate(hourFrom: Long, hourTo: Long, zone: TimeZone, dayFrom: Long = hourFrom): RollupResult {
        var hours = 0
        if (hourFrom < hourTo) {
            database.transaction { database.hourQueries.rollupHours(hourFrom, hourTo) }
            hours = ((hourTo - hourFrom) / HOUR_SECONDS).toInt()
        }
        val firstDay = instantOf(minOf(dayFrom, hourFrom)).localDate(zone)
        val lastDay = instantOf(hourTo - 1).localDate(zone)
        val days = ArrayList<LocalDate>()
        var day = firstDay
        while (day <= lastDay) {
            if (day.nextDay().start(zone).epochSeconds <= hourTo) days += day
            day = day.nextDay()
        }
        database.transaction { days.forEach { rollupDay(it, zone) } }

        val months = days.map { it.firstOfMonth() }.distinct().filter { it.firstOfNextMonth().start(zone).epochSeconds <= hourTo }
        database.transaction { months.forEach { rollupMonth(it, zone) } }
        return RollupResult(hours, days.size, months.size)
    }

    private fun rollupDay(day: LocalDate, zone: TimeZone) {
        val key = day.toString()
        database.dayQueries.deleteDayRows(key)
        database.dayQueries.rollupDay(key, zone.id, day.start(zone).epochSeconds, day.nextDay().start(zone).epochSeconds)
    }

    private fun rollupMonth(firstOfMonth: LocalDate, zone: TimeZone) {
        val key = monthKey(firstOfMonth)
        database.monthQueries.deleteMonthRows(key)
        database.monthQueries.rollupMonth(key, zone.id, firstOfMonth.toString(), firstOfMonth.firstOfNextMonth().toString())
    }
}
