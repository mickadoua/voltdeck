package app.voltdeck.storage

import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Metric
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class CoverageTest {
    private val db = createTestDatabase().also { it.addDevice(BMS, DeviceRole.BATTERY); it.addDevice(MPPT, DeviceRole.SOLAR_CHARGER) }
    private val t0 = Instant.parse("2026-03-10T00:00:00Z")
    private val reader = CoverageReader(db)

    private fun record(vararg minutes: Int, id: app.voltdeck.core.DeviceId = BMS, metric: Metric = Metric.SOC) {
        val aggregator = MinuteAggregator(db)
        minutes.forEach { aggregator.add(reading(id, t0 + it.minutes + 5.seconds, metric to 50.0)) }
        aggregator.flushAll()
    }

    @Test
    fun noMeasureMeansNothingExpected() {
        val coverage = reader.minutes(BMS, t0 + 1.hours)
        assertEquals(MinuteCoverage(0, 0), coverage)
        assertNull(coverage.ratio)
    }

    @Test
    fun aGapInTheMiddleIsCountedAsMissingMinutes() {
        record(*((0..9) + (20..29)).toIntArray())
        val coverage = reader.minutes(BMS, t0 + 30.minutes + 90.seconds)
        assertEquals(MinuteCoverage(covered = 20, expected = 30), coverage)
        assertEquals(20.0 / 30, coverage.ratio)
    }

    @Test
    fun theWindowStartsAtTheFirstMeasureNotAt24HoursAgo() {
        record(*(120..179).toList().toIntArray())
        val coverage = reader.minutes(BMS, t0 + 3.hours + 90.seconds)
        assertEquals(MinuteCoverage(60, 60), coverage)
    }

    @Test
    fun theWindowIsCappedTo24Hours() {
        val aggregator = MinuteAggregator(db, Level.MINUTE)
        (0 until 26 * 60).forEach { aggregator.add(reading(BMS, t0 + it.minutes + 1.seconds, Metric.SOC to 50.0)) }
        aggregator.flushAll()
        val coverage = reader.minutes(BMS, t0 + 26.hours + 90.seconds)
        assertEquals(MinuteCoverage(1440, 1440), coverage)
    }

    @Test
    fun minutesNotYetWrittenAreNotCountedAsGaps() {
        record(0, 1, 2)
        val coverage = reader.minutes(BMS, t0 + 3.minutes + 20.seconds)
        assertEquals(MinuteCoverage(covered = 1, expected = 1), coverage, "les minutes 1 et 2 ne sont pas encore garanties écrites")
    }

    @Test
    fun aDerivedRowAloneIsNotAMeasure() {
        record(0, 1, 3)
        db.minuteQueries.upsertMinute(BMS.value, Metric.ENERGY_IN_WH.name, 0, (t0 + 2.minutes).epochSeconds, 1, 0.1, 0.1, 0.1, 0.1)
        assertEquals(MinuteCoverage(covered = 3, expected = 4), reader.minutes(BMS, t0 + 4.minutes + 90.seconds))
    }

    @Test
    fun eachDeviceHasItsOwnCoverage() {
        record(0, 1, 2, 3)
        record(0, 1, id = MPPT, metric = Metric.PV_POWER)
        val now = t0 + 4.minutes + 90.seconds
        assertEquals(MinuteCoverage(covered = 4, expected = 4), reader.minutes(BMS, now))
        assertEquals(MinuteCoverage(covered = 2, expected = 4), reader.minutes(MPPT, now))
    }
}
