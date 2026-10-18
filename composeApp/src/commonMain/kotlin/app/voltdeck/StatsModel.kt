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

/** Sur quelle base deux périodes sont comparées. */
enum class ComparisonBasis {
    /** Période terminée contre la précédente de même longueur. */
    WHOLE,

    /** Les premiers jours de chaque période (période entamée, ou mois de longueurs différentes). */
    FIRST_DAYS,

    /** Seuls les jours bien mesurés des deux côtés (voir [MIN_MEASURED_HOURS]) : il manque des jours, ou des heures, d'un côté ou de l'autre. */
    COMMON_DAYS,
}

/** Variation en % sur [days] jours comparés, la base étant donnée par [basis]. */
data class Comparison(val percent: Double, val days: Int, val basis: ComparisonBasis)

/**
 * Jours à comparer : les mêmes premiers jours des deux périodes, limités aux jours terminés de la période
 * en cours (jamais une période entamée contre une période complète, ni un mois de 31 jours contre un de 30).
 */
fun comparableDays(period: StatsPeriod, today: LocalDate): Pair<List<LocalDate>, List<LocalDate>> {
    val previousDays = period.previous().days
    val current = period.days.filter { it < today }
    val n = minOf(current.size, previousDays.size)
    return current.take(n) to previousDays.take(n)
}

/** Un jour ne compte dans une comparaison que s'il a au moins ce nombre d'heures mesurées : une demi-journée fausserait le total. */
const val MIN_MEASURED_HOURS = 20

/** Jours locaux dont au moins [MIN_MEASURED_HOURS] créneaux horaires satisfont [measured] (production connue pour la production ; production ET flux de la batterie connus pour la consommation, qui est la différence des deux). */
fun wellMeasuredDays(hours: List<EnergyBucket>, zone: TimeZone, measured: (EnergyBucket) -> Boolean): Set<LocalDate> =
    hours.filter(measured).groupingBy { it.start.toLocalDateTime(zone).date }.eachCount().filterValues { it >= MIN_MEASURED_HOURS }.keys

/** Vrai si les deux listes de jours sont les périodes entières : la précédente peut être plus longue que la période en cours. */
fun isWholeComparison(period: StatsPeriod, currentDays: List<LocalDate>, previousDays: List<LocalDate>): Boolean =
    currentDays.size == period.days.size && previousDays.size == period.previous().days.size

/**
 * Compare deux listes d'énergie par rang de jour (`null` = jour sans mesure). Seuls les rangs mesurés des deux côtés
 * comptent : sinon une période précédente à moitié collectée gonflerait la variation. `null` si aucun rang n'est commun.
 */
fun compareEnergy(current: List<Double?>, previous: List<Double?>, wholePeriods: Boolean): Comparison? {
    val pairs = current.zip(previous).mapNotNull { (c, p) -> if (c != null && p != null) c to p else null }
    if (pairs.isEmpty()) return null
    val percent = variation(
        EnergyTotal(pairs.sumOf { it.first }, false, null),
        EnergyTotal(pairs.sumOf { it.second }, false, null),
    ) ?: return null
    val basis = when {
        pairs.size < current.size -> ComparisonBasis.COMMON_DAYS
        wholePeriods -> ComparisonBasis.WHOLE
        else -> ComparisonBasis.FIRST_DAYS
    }
    return Comparison(percent, pairs.size, basis)
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
    val wholePeriods = isWholeComparison(period, currentDays, previousDays)
    fun hoursOf(dates: List<LocalDate>) = if (dates.isEmpty()) emptyList() else
        history.buckets(Level.HOUR, dates.first().atStartOfDayIn(zone), dates.last().plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone), zone)
    val currentHours = hoursOf(currentDays)
    val previousHours = hoursOf(previousDays)
    fun producedKwh(dates: List<LocalDate>, hours: List<EnergyBucket>): List<Double?> {
        val complete = wellMeasuredDays(hours, zone) { it.producedWh != null }
        return dates.map { if (it in complete) stats.solarProduction(it, zone).value else null }
    }
    fun consumedKwh(dates: List<LocalDate>, hours: List<EnergyBucket>): List<Double?> {
        val complete = wellMeasuredDays(hours, zone) { it.producedWh != null && it.batteryNetWh != null }
        return dates.map { if (it in complete) stats.consumption(it, zone, now).value?.div(1000.0) else null }
    }

    return StatsData(
        period = period,
        level = level,
        buckets = if (to > from) history.buckets(level, from, to, zone) else emptyList(),
        produced = produced(period.days),
        consumed = consumed(period.days),
        producedComparison = compareEnergy(producedKwh(currentDays, currentHours), producedKwh(previousDays, previousHours), wholePeriods),
        consumedComparison = compareEnergy(consumedKwh(currentDays, currentHours), consumedKwh(previousDays, previousHours), wholePeriods),
        soc = soc,
        socMin = min(soc),
        socMax = max(soc),
        voltageMin = min(voltage),
        voltageMax = max(voltage),
    )
}
