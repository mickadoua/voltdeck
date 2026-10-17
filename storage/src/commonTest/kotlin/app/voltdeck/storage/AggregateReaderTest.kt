package app.voltdeck.storage

import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Metric
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class AggregateReaderTest {
    private val now = EPOCH_2026 + 400.days

    @Test
    fun chartLevelNeverExceedsFifteenHundredPoints() {
        val spans = listOf(1.minutes, 1.hours, 12.hours, 24.hours, 25.hours, 3.days, 10.days, 30.days, 60.days, 62.days, 63.days, 180.days, 365.days, 1000.days, 2000.days, 5000.days, 20000.days)
        for (ago in listOf(0.days, 5.days, 29.days, 200.days)) {
            for (span in spans) {
                val to = now - ago
                val from = to - span
                val level = chooseLevel(from, to, now)
                val step = when (level) {
                    Level.MINUTE -> 60L
                    Level.HOUR -> 3600L
                    Level.DAY -> 86400L
                    Level.MONTH -> 28 * 86400L
                }
                if (level != Level.MONTH) assertTrue(span.inWholeSeconds / step + 2 <= MAX_CHART_POINTS, "$level sur $span (il y a $ago)")
                else assertTrue(span.inWholeSeconds / step + 2 <= MAX_CHART_POINTS || span > 1000.days)
            }
        }
    }

    @Test
    fun levelFollowsTheSpan() {
        assertEquals(Level.MINUTE, chooseLevel(now - 1.days, now, now))
        assertEquals(Level.HOUR, chooseLevel(now - 10.days, now, now))
        assertEquals(Level.DAY, chooseLevel(now - 365.days, now, now))
        assertEquals(Level.MONTH, chooseLevel(now - 5000.days, now, now))
    }

    @Test
    fun minutesAreOnlyOfferedInsideTheRetention() {
        assertEquals(Level.HOUR, chooseLevel(now - 40.days, now - 40.days + 2.hours, now), "des minutes purgées ne se lisent pas")
        assertEquals(Level.MINUTE, chooseLevel(now - 5.days, now - 5.days + 2.hours, now))
    }

    private val db = createTestDatabase().also { it.addDevice(BMS, DeviceRole.BATTERY) }
    private val key = SeriesKey(BMS, Metric.SOC)

    @Test
    fun hourlyViewCombinesStoredHoursAndTheUnconsolidatedTail() {
        db.feed(EPOCH_2026, EPOCH_2026 + 5.hours - 1.minutes, 1.minutes, mapOf(BMS to { mapOf(Metric.SOC to 1.0) }))
        Rollup(db).run(EPOCH_2026 + 3.hours + 20.minutes, UTC)
        assertEquals(3L, db.hourQueries.countHours().executeAsOne())
        val rows = AggregateReader(db).hourly(key, EPOCH_2026, EPOCH_2026 + 6.hours)
        assertEquals((0..4).map { EPOCH_2026 + it.hours }, rows.map { it.start })
        assertTrue(rows.all { it.stats.count == 60L })
    }

    @Test
    fun missingHoursAreNotInterpolated() {
        db.feed(EPOCH_2026, EPOCH_2026 + 10.minutes, 1.minutes, mapOf(BMS to { mapOf(Metric.SOC to 1.0) }))
        db.feed(EPOCH_2026 + 3.hours, EPOCH_2026 + 3.hours + 10.minutes, 1.minutes, mapOf(BMS to { mapOf(Metric.SOC to 1.0) }))
        val rows = AggregateReader(db).hourly(key, EPOCH_2026, EPOCH_2026 + 5.hours)
        assertEquals(listOf(EPOCH_2026, EPOCH_2026 + 3.hours), rows.map { it.start })
    }

    @Test
    fun dailyViewCombinesStoredDaysAndTheOpenDay() {
        db.feed(EPOCH_2026, EPOCH_2026 + 2.days + 6.hours, 5.minutes, mapOf(BMS to { mapOf(Metric.SOC to 2.0) }))
        Rollup(db).run(EPOCH_2026 + 2.days + 6.hours, UTC)
        assertEquals(2L, db.dayQueries.countDays().executeAsOne())
        val rows = AggregateReader(db).daily(key, EPOCH_2026, EPOCH_2026 + 3.days, UTC)
        assertEquals(listOf(EPOCH_2026, EPOCH_2026 + 1.days, EPOCH_2026 + 2.days), rows.map { it.start })
        assertEquals(288L, rows[0].stats.count)
        assertEquals(73L, rows[2].stats.count)
    }

    @Test
    fun latestIsNullForASeriesThatNeverReceivedAnything() {
        assertNull(AggregateReader(db).latest(key, UTC))
    }

    @Test
    fun twoDeviceFilesAreSeparate() {
        val demo = createTestDatabase()
        demo.addDevice(BMS, DeviceRole.BATTERY)
        demo.feed(EPOCH_2026, EPOCH_2026 + 5.minutes, 1.minutes, mapOf(BMS to { mapOf(Metric.SOC to 1.0) }))
        assertEquals(0L, db.minuteQueries.countMinutes().executeAsOne())
        assertTrue(StorageFiles.nameFor(demo = true) != StorageFiles.nameFor(demo = false))
        assertEquals("voltdeck-demo.db", StorageFiles.DEMO)
    }
}
