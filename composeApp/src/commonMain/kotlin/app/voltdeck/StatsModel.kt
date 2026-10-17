package app.voltdeck

import app.voltdeck.core.DeviceId
import app.voltdeck.core.Metric
import app.voltdeck.storage.AggRow
import app.voltdeck.storage.AggregateReader
import app.voltdeck.storage.EnergyBucket
import app.voltdeck.storage.EnergyHistory
import app.voltdeck.storage.Level
import app.voltdeck.storage.Nature
import app.voltdeck.storage.SeriesKey
import app.voltdeck.storage.SystemStats
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

enum class PeriodKind(val label: String) { DAY("Jour"), WEEK("Semaine"), MONTH("Mois") }

data class StatsPeriod(val kind: PeriodKind, val anchor: LocalDate) {
    val first: LocalDate
        get() = when (kind) {
            PeriodKind.DAY -> anchor
            PeriodKind.WEEK -> anchor.minus(anchor.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
            PeriodKind.MONTH -> LocalDate(anchor.year, anchor.month, 1)
        }

    val endExclusive: LocalDate
        get() = when (kind) {
            PeriodKind.DAY -> first.plus(1, DateTimeUnit.DAY)
            PeriodKind.WEEK -> first.plus(7, DateTimeUnit.DAY)
            PeriodKind.MONTH -> first.plus(1, DateTimeUnit.MONTH)
        }

    val days: List<LocalDate>
        get() = generateSequence(first) { it.plus(1, DateTimeUnit.DAY) }.takeWhile { it < endExclusive }.toList()

    fun previous() = StatsPeriod(kind, step(-1))

    fun next() = StatsPeriod(kind, step(1))

    fun contains(date: LocalDate) = date >= first && date < endExclusive

    private fun step(sign: Int): LocalDate = when (kind) {
        PeriodKind.DAY -> first.plus(sign, DateTimeUnit.DAY)
        PeriodKind.WEEK -> first.plus(7 * sign, DateTimeUnit.DAY)
        PeriodKind.MONTH -> first.plus(sign, DateTimeUnit.MONTH)
    }

    val label: String
        get() = when (kind) {
            PeriodKind.DAY -> "${DAYS[first.dayOfWeek.isoDayNumber - 1]} ${first.day} ${MONTHS[first.month.number - 1]}"
            PeriodKind.WEEK -> {
                val last = endExclusive.minus(1, DateTimeUnit.DAY)
                if (first.month == last.month) {
                    "${first.day} → ${last.day} ${MONTHS[last.month.number - 1]}"
                } else {
                    "${first.day} ${SHORT_MONTHS[first.month.number - 1]} → ${last.day} ${SHORT_MONTHS[last.month.number - 1]}"
                }
            }
            PeriodKind.MONTH -> "${MONTHS[first.month.number - 1]} ${first.year}"
        }

    companion object {
        private val DAYS = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
        private val MONTHS = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")
        private val SHORT_MONTHS = listOf("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.")
    }
}

/** Total d'énergie d'une période, en kWh, avec sa nature (« calculée » dès qu'une journée l'est). */
data class EnergyTotal(val kwh: Double, val calculated: Boolean, val coverage: Double?)

data class Extremum(val value: Double, val at: Instant)

data class StatsData(
    val period: StatsPeriod,
    val level: Level,
    val buckets: List<EnergyBucket>,
    val produced: EnergyTotal?,
    val consumed: EnergyTotal?,
    val producedComparison: Comparison?,
    val consumedComparison: Comparison?,
    val soc: List<AggRow>,
    val socMin: Extremum?,
    val socMax: Extremum?,
    val voltageMin: Extremum?,
    val voltageMax: Extremum?,
)

/** Variation en %, signe explicite ; `null` si la période précédente est inconnue ou nulle. */
fun variation(current: EnergyTotal?, previous: EnergyTotal?): Double? {
    if (current == null || previous == null || previous.kwh <= 0.0) return null
    return (current.kwh - previous.kwh) / previous.kwh * 100.0
}

/** Variation sur [days] jours ; [partial] quand la période en cours n'est pas terminée et que seuls ses premiers jours sont comparés. */
data class Comparison(val percent: Double, val days: Int, val partial: Boolean)

/**
 * Jours à comparer : toute la période si elle est terminée, sinon ses seuls jours terminés face aux
 * mêmes premiers jours de la période précédente (jamais une période entamée contre une période complète).
 */
fun comparableDays(period: StatsPeriod, today: LocalDate): Pair<List<LocalDate>, List<LocalDate>> {
    val previousDays = period.previous().days
    if (period.days.last() < today) return period.days to previousDays
    val finished = period.days.filter { it < today }
    val n = minOf(finished.size, previousDays.size)
    return finished.take(n) to previousDays.take(n)
}

fun loadStats(
    stats: SystemStats,
    history: EnergyHistory,
    reader: AggregateReader,
    battery: DeviceId?,
    period: StatsPeriod,
    zone: TimeZone,
    now: Instant,
): StatsData {
    val today = now.toLocalDateTime(zone).date
    val from = period.first.atStartOfDayIn(zone)
    val to = minOf(period.endExclusive.atStartOfDayIn(zone), now)
    val level = if (period.kind == PeriodKind.DAY) Level.HOUR else Level.DAY

    fun produced(dates: List<LocalDate>): EnergyTotal? {
        val days = dates.filter { it <= today }.map { stats.solarProduction(it, zone) }.filter { it.value != null }
        if (days.isEmpty()) return null
        return EnergyTotal(days.sumOf { it.value!! }, days.any { it.nature == Nature.CALCULATED }, null)
    }

    fun consumed(dates: List<LocalDate>): EnergyTotal? {
        val days = dates.filter { it <= today }.map { stats.consumption(it, zone, now) }.filter { it.value != null }
        if (days.isEmpty()) return null
        return EnergyTotal(days.sumOf { it.value!! } / 1000.0, true, days.mapNotNull { it.coverage }.average().takeIf { !it.isNaN() })
    }

    val soc = battery?.let { reader.series(SeriesKey(it, Metric.SOC), from, to, now, zone).rows }.orEmpty()
    val voltage = battery?.let { reader.series(SeriesKey(it, Metric.BATTERY_VOLTAGE), from, to, now, zone).rows }.orEmpty()
    fun min(rows: List<AggRow>) = rows.minByOrNull { it.stats.min }?.let { Extremum(it.stats.min, it.start) }
    fun max(rows: List<AggRow>) = rows.maxByOrNull { it.stats.max }?.let { Extremum(it.stats.max, it.start) }

    val (currentDays, previousDays) = comparableDays(period, today)
    val partial = currentDays.size < period.days.size
    fun comparison(current: EnergyTotal?, previous: EnergyTotal?) =
        variation(current, previous)?.let { Comparison(it, currentDays.size, partial) }

    return StatsData(
        period = period,
        level = level,
        buckets = if (to > from) history.buckets(level, from, to, zone) else emptyList(),
        produced = produced(period.days),
        consumed = consumed(period.days),
        producedComparison = comparison(produced(currentDays), produced(previousDays)),
        consumedComparison = comparison(consumed(currentDays), consumed(previousDays)),
        soc = soc,
        socMin = min(soc),
        socMax = max(soc),
        voltageMin = min(voltage),
        voltageMax = max(voltage),
    )
}
