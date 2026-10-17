package app.voltdeck.storage

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

internal const val MINUTE_SECONDS = 60L
internal const val HOUR_SECONDS = 3600L

/** Durée de conservation des minutes (VOL-1 §5.4). Les niveaux supérieurs sont conservés sans limite. */
val MINUTE_RETENTION = 30.days

enum class Level { MINUTE, HOUR, DAY, MONTH }

internal fun floorTo(epochSeconds: Long, step: Long): Long = epochSeconds.floorDiv(step) * step

internal fun Instant.minuteStart(): Long = floorTo(epochSeconds, MINUTE_SECONDS)

internal fun Instant.hourStart(): Long = floorTo(epochSeconds, HOUR_SECONDS)

internal fun instantOf(epochSeconds: Long): Instant = Instant.fromEpochSeconds(epochSeconds)

internal fun Instant.localDate(zone: TimeZone): LocalDate = toLocalDateTime(zone).date

internal fun LocalDate.nextDay(): LocalDate = plus(1, DateTimeUnit.DAY)

internal fun LocalDate.firstOfMonth(): LocalDate = LocalDate(year, month, 1)

internal fun LocalDate.firstOfNextMonth(): LocalDate = firstOfMonth().plus(1, DateTimeUnit.MONTH)

/** Début du jour local : une journée de 23 h ou 25 h (changement d'heure) a bien ses bornes réelles. */
internal fun LocalDate.start(zone: TimeZone): Instant = atStartOfDayIn(zone)

internal fun monthKey(date: LocalDate): String =
    "${date.year.toString().padStart(4, '0')}-${date.month.ordinal.plus(1).toString().padStart(2, '0')}"

internal fun parseMonthKey(key: String): LocalDate = LocalDate.parse("$key-01")

