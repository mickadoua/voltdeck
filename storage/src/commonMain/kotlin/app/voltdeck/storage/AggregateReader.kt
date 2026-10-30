package app.voltdeck.storage

import app.voltdeck.storage.db.VoltdeckDatabase
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

/** Nombre de points au-delà duquel un graphique n'a plus de sens (VOL-1 §5.4) : le niveau est choisi pour ne jamais le dépasser. */
const val MAX_CHART_POINTS = 1500

/** Série prête à tracer : le niveau d'agrégat retenu et ses lignes, triées par début de période. */
data class Series(val level: Level, val rows: List<AggRow>)

/**
 * Lecture des agrégats.
 *
 * Les vues [hourly], [daily] et [monthly] donnent la série **à jour** : les périodes consolidées viennent de leur table,
 * la fin de série (période en cours, ou pas encore consolidée) est recalculée à la volée depuis le niveau inférieur.
 * Aucune interpolation : une période sans mesure n'a pas de ligne.
 */
class AggregateReader(private val database: VoltdeckDatabase) {
    /** Lignes d'un niveau, lues telles quelles dans leur table, avec un début de période dans `[from, to)`. */
    fun stored(level: Level, key: SeriesKey, from: Instant, to: Instant, zone: TimeZone): List<AggRow> {
        val device = key.deviceId.value
        val metric = key.metric.name
        val channel = key.channel.toLong()
        return when (level) {
            Level.MINUTE -> database.minuteQueries.selectMinute(device, metric, channel, from.epochSeconds, to.epochSeconds)
                .executeAsList().map { AggRow(instantOf(it.bucket_start), AggStats(it.count, it.min, it.max, it.sum, it.last)) }

            Level.HOUR -> database.hourQueries.selectHour(device, metric, channel, from.epochSeconds, to.epochSeconds)
                .executeAsList().map { AggRow(instantOf(it.bucket_start), AggStats(it.count, it.min, it.max, it.sum, it.last)) }

            Level.DAY -> {
                val (firstKey, endKey) = dayKeyRange(from, to, zone)
                val zoneFor = zoneResolver(zone)
                database.dayQueries.selectDay(device, metric, channel, firstKey, endKey).executeAsList()
                    .map { AggRow(LocalDate.parse(it.day).start(zoneFor(it.tz_id)), AggStats(it.count, it.min, it.max, it.sum, it.last)) }
                    .filter { it.start >= from && it.start < to }
            }

            Level.MONTH -> {
                val firstKey = monthKey(from.localDate(zone))
                val endKey = monthKey((to - 1.nanoseconds).localDate(zone).firstOfNextMonth())
                val zoneFor = zoneResolver(zone)
                database.monthQueries.selectMonth(device, metric, channel, firstKey, endKey).executeAsList()
                    .map { AggRow(parseMonthKey(it.month).start(zoneFor(it.tz_id)), AggStats(it.count, it.min, it.max, it.sum, it.last)) }
                    .filter { it.start >= from && it.start < to }
            }
        }
    }

    /** Heures dont le début est dans `[from, to)` : les heures consolidées, plus celles que seules les minutes décrivent encore. */
    fun hourly(key: SeriesKey, from: Instant, to: Instant): List<AggRow> {
        val fromSeconds = from.hourStart().let { if (instantOf(it) < from) it + HOUR_SECONDS else it }
        val stored = database.hourQueries.selectHour(key.deviceId.value, key.metric.name, key.channel.toLong(), fromSeconds, to.epochSeconds)
            .executeAsList().associate { it.bucket_start to AggRow(instantOf(it.bucket_start), AggStats(it.count, it.min, it.max, it.sum, it.last)) }
        val fromMinutes = (stored.keys.maxOrNull()?.plus(HOUR_SECONDS)) ?: fromSeconds
        val derived = database.minuteQueries
            .selectMinutesAsHours(key.deviceId.value, key.metric.name, key.channel.toLong(), maxOf(fromMinutes, fromSeconds), to.epochSeconds)
            .executeAsList()
            .filter { it.hour_start !in stored }
            .map { AggRow(instantOf(it.hour_start), AggStats(it.count!!, it.min!!, it.max!!, it.sum!!, it.last)) }
        return (stored.values + derived).sortedBy { it.start }
    }

    /** Jours locaux dont le début est dans `[from, to)`. */
    fun daily(key: SeriesKey, from: Instant, to: Instant, zone: TimeZone): List<AggRow> {
        val stored = stored(Level.DAY, key, from, to, zone)
        val tailFrom = stored.lastOrNull()?.start?.localDate(zone)?.nextDay()?.start(zone) ?: from
        if (tailFrom >= to) return stored
        val derived = hourly(key, maxOf(tailFrom, from), to)
            .groupBy { it.start.localDate(zone) }
            .map { (day, rows) -> AggRow(day.start(zone), AggStats.combine(rows.map { it.stats })!!) }
            .filter { it.start >= from && it.start < to }
        return (stored + derived).sortedBy { it.start }
    }

    /** Mois locaux dont le début est dans `[from, to)`. */
    fun monthly(key: SeriesKey, from: Instant, to: Instant, zone: TimeZone): List<AggRow> {
        val stored = stored(Level.MONTH, key, from, to, zone)
        val tailFrom = stored.lastOrNull()?.start?.localDate(zone)?.firstOfNextMonth()?.start(zone) ?: from
        if (tailFrom >= to) return stored
        val derived = daily(key, maxOf(tailFrom, from), to, zone)
            .groupBy { it.start.localDate(zone).firstOfMonth() }
            .map { (month, rows) -> AggRow(month.start(zone), AggStats.combine(rows.map { it.stats })!!) }
            .filter { it.start >= from && it.start < to }
        return (stored + derived).sortedBy { it.start }
    }

    /** Dernière ligne connue d'une série, tous niveaux confondus, ou `null` si elle n'a jamais reçu de mesure. */
    fun latest(key: SeriesKey, zone: TimeZone): AggRow? {
        val device = key.deviceId.value
        val metric = key.metric.name
        val channel = key.channel.toLong()
        database.minuteQueries.selectLatestMinute(device, metric, channel).executeAsOneOrNull()?.let {
            return AggRow(instantOf(it.bucket_start), AggStats(it.count, it.min, it.max, it.sum, it.last))
        }
        database.hourQueries.selectLatestHour(device, metric, channel).executeAsOneOrNull()?.let {
            return AggRow(instantOf(it.bucket_start), AggStats(it.count, it.min, it.max, it.sum, it.last))
        }
        database.dayQueries.selectLatestDay(device, metric, channel).executeAsOneOrNull()?.let {
            return AggRow(LocalDate.parse(it.day).start(zoneOf(it.tz_id, zone)), AggStats(it.count, it.min, it.max, it.sum, it.last))
        }
        return null
    }

    /**
     * Série d'un graphique sur `[from, to)` : le niveau le plus fin qui reste sous [maxPoints] points
     * (minutes si la plage est encore dans la rétention, puis heures, jours, mois).
     */
    fun series(key: SeriesKey, from: Instant, to: Instant, now: Instant, zone: TimeZone, maxPoints: Int = MAX_CHART_POINTS): Series {
        val level = chooseLevel(from, to, now, maxPoints)
        val rows = when (level) {
            Level.MINUTE -> stored(Level.MINUTE, key, from, to, zone)
            Level.HOUR -> hourly(key, from, to)
            Level.DAY -> daily(key, from, to, zone)
            Level.MONTH -> monthly(key, from, to, zone)
        }
        return Series(level, rows)
    }

    private fun dayKeyRange(from: Instant, to: Instant, zone: TimeZone): Pair<String, String> {
        val first = from.localDate(zone)
        val end = (to - 1.nanoseconds).localDate(zone).nextDay()
        return first.toString() to end.toString()
    }

    /** Résout les fuseaux enregistrés une seule fois par lecture : `TimeZone.of` coûte ~1 ms par appel sur iOS (VOL-27). */
    private fun zoneResolver(fallback: TimeZone): (String) -> TimeZone {
        val resolved = HashMap<String, TimeZone>()
        return { id -> resolved.getOrPut(id) { zoneOf(id, fallback) } }
    }

    /** Fuseau enregistré avec une ligne, ou [fallback] s'il n'est pas reconnu sur ce téléphone. */
    private fun zoneOf(id: String, fallback: TimeZone): TimeZone =
        try {
            TimeZone.of(id)
        } catch (_: Exception) {
            fallback
        }
}

/**
 * Niveau d'agrégat d'un graphique sur `[from, to)` : le plus fin dont le nombre de points ne dépasse pas [maxPoints].
 * Les minutes ne sont proposées que si toute la plage est dans la rétention (au-delà, elles ont été purgées).
 */
fun chooseLevel(from: Instant, to: Instant, now: Instant, maxPoints: Int = MAX_CHART_POINTS): Level {
    val span = (to - from).inWholeSeconds.coerceAtLeast(0)
    val minuteLimit = (now - MINUTE_RETENTION).hourStart()
    if (from.epochSeconds >= minuteLimit && pointCount(span, MINUTE_SECONDS) <= maxPoints) return Level.MINUTE
    if (pointCount(span, HOUR_SECONDS) <= maxPoints) return Level.HOUR
    if (pointCount(span, 24 * HOUR_SECONDS) <= maxPoints) return Level.DAY
    return Level.MONTH
}

/** Nombre maximal de périodes de [step] secondes que touche une plage de [span] secondes (une de plus que la division exacte). */
private fun pointCount(span: Long, step: Long): Long = span / step + 2
