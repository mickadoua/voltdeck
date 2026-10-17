package app.voltdeck.storage

import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Metric
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class RetentionTest {
    private val db = createTestDatabase().also { it.addDevice(BMS, DeviceRole.BATTERY) }
    private val start = EPOCH_2026

    private fun feedDays(count: Int) =
        db.feed(start, start + count.days - 1.minutes, 5.minutes, mapOf(BMS to { mapOf(Metric.SOC to 50.0) }))

    @Test
    fun purgeAtDayThirtyOneDropsMinutesAndKeepsHours() {
        feedDays(1)
        Maintenance(db).run(start + 31.days, UTC)
        assertEquals(0L, db.minuteQueries.countMinutes().executeAsOne(), "tout est antérieur à J-30")
        assertEquals(24L, db.hourQueries.countHours().executeAsOne())
        assertEquals(1L, db.dayQueries.countDays().executeAsOne())
    }

    @Test
    fun minutesInsideTheWindowAreKept() {
        feedDays(2)
        Maintenance(db).run(start + 31.days + 30.minutes, UTC)
        val remaining = db.stored(Level.MINUTE, BMS, Metric.SOC)
        assertTrue(remaining.isNotEmpty())
        assertTrue(remaining.all { it.start >= start + 1.days }, "la coupure est à J-30, alignée sur l'heure")
        assertEquals(48L, db.hourQueries.countHours().executeAsOne())
    }

    @Test
    fun nothingIsPurgedBeforeItsHourIsRolledUp() {
        feedDays(1)
        val purged = Retention(db).purge(start + 40.days)
        assertEquals(0L, purged)
        assertEquals(288L, db.minuteQueries.countMinutes().executeAsOne())
    }

    @Test
    fun purgingTwiceIsHarmless() {
        feedDays(1)
        val maintenance = Maintenance(db)
        maintenance.run(start + 31.days, UTC)
        val after = db.snapshot(listOf(BMS))
        maintenance.run(start + 31.days, UTC)
        assertEquals(after, db.snapshot(listOf(BMS)))
    }

    @Test
    fun hourlyChartsStillWorkAfterThePurge() {
        feedDays(1)
        val now = start + 40.days
        Maintenance(db).run(now, UTC)
        val series = AggregateReader(db).series(SeriesKey(BMS, Metric.SOC), start, start + 1.days, now, UTC)
        assertEquals(Level.HOUR, series.level)
        assertEquals(24, series.rows.size)
    }
}
