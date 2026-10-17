package app.voltdeck.storage

import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Metric
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class RollupTest {
    private val db = createTestDatabase().also { it.addDevice(BMS, DeviceRole.BATTERY) }
    private val rollup = Rollup(db)

    /** Une mesure par minute, de valeur égale au numéro de la minute depuis [from] (somme connue). */
    private fun feedMinutes(from: Instant, minutesCount: Int) {
        db.feed(from, from + (minutesCount - 1).minutes, 1.minutes, mapOf(BMS to { t -> mapOf(Metric.SOC to ((t - from).inWholeMinutes).toDouble()) }))
    }

    private fun days(zone: TimeZone) = db.stored(Level.DAY, BMS, Metric.SOC, zone)

    @Test
    fun replayingTheRollupTwiceGivesTheSameRows() {
        feedMinutes(Instant.parse("2026-03-10T00:00:00Z"), 3 * 24 * 60)
        val now = Instant.parse("2026-03-13T06:30:00Z")
        rollup.run(now, UTC)
        val first = db.snapshot(listOf(BMS))
        rollup.run(now, UTC)
        assertEquals(first, db.snapshot(listOf(BMS)))
        rollup.rebuild(now, UTC)
        assertEquals(first, db.snapshot(listOf(BMS)))
        assertTrue(first.any { it.startsWith("HOUR") } && first.any { it.startsWith("DAY") })
    }

    @Test
    fun hourAggregatesAreRecomputedFromMinutes() {
        feedMinutes(Instant.parse("2026-03-10T00:00:00Z"), 180)
        rollup.run(Instant.parse("2026-03-10T03:30:00Z"), UTC)
        val hours = db.stored(Level.HOUR, BMS, Metric.SOC)
        assertEquals(3, hours.size)
        assertEquals(AggStats(60, 60.0, 119.0, (60..119).sumOf { it }.toDouble(), 119.0), hours[1].stats)
    }

    @Test
    fun onlyClosedPeriodsAreRolled() {
        feedMinutes(Instant.parse("2026-03-10T00:00:00Z"), 11 * 60)
        rollup.run(Instant.parse("2026-03-10T10:30:00Z"), UTC)
        assertEquals(10, db.stored(Level.HOUR, BMS, Metric.SOC).size, "l'heure en cours (10 h) n'est pas consolidée")
        assertTrue(days(UTC).isEmpty(), "le jour n'est pas terminé")
        assertTrue(db.stored(Level.MONTH, BMS, Metric.SOC).isEmpty())
    }

    @Test
    fun dayAndMonthCloseWhenTheirEndIsReached() {
        feedMinutes(Instant.parse("2026-02-27T00:00:00Z"), 4 * 24 * 60)
        rollup.run(Instant.parse("2026-03-03T06:00:00Z"), UTC)
        val daysRolled = days(UTC)
        assertEquals(4, daysRolled.size)
        assertTrue(daysRolled.all { it.stats.count == 1440L })
        val month = db.stored(Level.MONTH, BMS, Metric.SOC).single()
        assertEquals(Instant.parse("2026-02-01T00:00:00Z"), month.start)
        assertEquals(2L * 24 * 60, month.stats.count, "février : le 27 et le 28 seulement")
        assertEquals(daysRolled.take(2).sumOf { it.stats.sum }, month.stats.sum, 1e-6)
    }

    @Test
    fun theTwentyFiveHourDayHasItsTrueBoundsAndTotal() {
        val start = Instant.parse("2026-10-24T22:00:00Z")
        feedMinutes(start, 25 * 60)
        rollup.run(Instant.parse("2026-10-26T01:30:00Z"), PARIS)
        val day = days(PARIS).single()
        assertEquals(Instant.parse("2026-10-24T22:00:00Z"), day.start)
        assertEquals(25L * 60, day.stats.count)
        assertEquals((0 until 25 * 60).sumOf { it }.toDouble(), day.stats.sum, 1e-6)
        assertEquals(LocalDate(2026, 10, 25), day.start.localDate(PARIS))
        val oneDay = AggregateReader(db).daily(SeriesKey(BMS, Metric.SOC), start, Instant.parse("2026-10-25T23:00:00Z"), PARIS)
        assertEquals(listOf(day), oneDay)
    }

    @Test
    fun theTwentyThreeHourDayIsNotPaddedTo24() {
        val start = Instant.parse("2026-03-28T23:00:00Z")
        feedMinutes(start, 23 * 60)
        rollup.run(Instant.parse("2026-03-30T01:30:00Z"), PARIS)
        assertEquals(23L * 60, days(PARIS).single().stats.count)
    }

    @Test
    fun theDayRowRecordsItsTimezone() {
        feedMinutes(Instant.parse("2026-03-10T00:00:00Z"), 2 * 24 * 60)
        rollup.run(Instant.parse("2026-03-12T01:00:00Z"), UTC)
        val reader = AggregateReader(db)
        assertEquals(2, reader.stored(Level.DAY, SeriesKey(BMS, Metric.SOC), Instant.fromEpochSeconds(0), Instant.parse("2100-01-01T00:00:00Z"), UTC).size)
        assertEquals(setOf("UTC"), db.dayQueries.selectDay(BMS.value, Metric.SOC.name, 0, "2000-01-01", "2100-01-01").executeAsList().map { it.tz_id }.toSet())
    }

    @Test
    fun aTimezoneChangeNeverCountsAnHourTwice() {
        val start = Instant.parse("2026-01-10T05:00:00Z")
        feedMinutes(start, 3 * 24 * 60)
        val now = Instant.parse("2026-01-14T12:00:00Z")
        val newYork = TimeZone.of("America/New_York")
        val total = 3L * 24 * 60

        rollup.run(now, PARIS)
        assertEquals(total, days(PARIS).sumOf { it.stats.count })

        rollup.rebuild(now, newYork)
        val rebuilt = days(newYork)
        assertEquals(listOf(LocalDate(2026, 1, 10), LocalDate(2026, 1, 11), LocalDate(2026, 1, 12)), rebuilt.map { it.start.localDate(newYork) })
        assertTrue(rebuilt.all { it.stats.count == 1440L })
        assertEquals(total, rebuilt.sumOf { it.stats.count })
    }

    @Test
    fun incrementalRunPicksUpWhereItStoppedAndMatchesAFullRebuild() {
        feedMinutes(Instant.parse("2026-03-10T00:00:00Z"), 2 * 24 * 60)
        rollup.run(Instant.parse("2026-03-11T05:00:00Z"), UTC)
        rollup.run(Instant.parse("2026-03-12T05:00:00Z"), UTC)
        val incremental = db.snapshot(listOf(BMS))
        rollup.rebuild(Instant.parse("2026-03-12T05:00:00Z"), UTC)
        assertEquals(incremental, db.snapshot(listOf(BMS)))
    }
}
