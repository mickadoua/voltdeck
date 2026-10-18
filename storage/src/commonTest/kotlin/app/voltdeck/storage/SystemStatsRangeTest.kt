package app.voltdeck.storage

import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Metric
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Les versions « plusieurs jours » de [SystemStats] doivent donner exactement ce que donne chaque jour pris seul. */
class SystemStatsRangeTest {
    private val db = createTestDatabase()
    private val stats = SystemStats(db)
    private val zone: TimeZone = PARIS
    private val first = LocalDate(2026, 3, 20)
    private val dates = (0 until 20).map { first.plus(it, DateTimeUnit.DAY) }
    private val now = Instant.parse("2026-04-08T09:00:00Z")

    private fun feedDay(day: LocalDate, hours: IntRange, amps: Double, dcdcState: Double? = null, yield: Double? = null, bucket: Level = Level.HOUR) {
        val start = day.start(zone)
        val sources = mutableMapOf(
            MPPT to { _: Instant ->
                buildMap {
                    put(Metric.BATTERY_VOLTAGE, 12.0); put(Metric.BATTERY_CURRENT, 6.0); put(Metric.PV_POWER, 90.0)
                    if (yield != null) put(Metric.YIELD_TODAY, yield)
                }
            },
            BMS to { _: Instant -> mapOf(Metric.BATTERY_VOLTAGE to 12.0, Metric.BATTERY_CURRENT to amps) },
        )
        if (dcdcState != null) sources[DCDC] = { _: Instant -> mapOf(Metric.DEVICE_STATE to dcdcState) }
        db.feed(start + hours.first.hours, start + hours.last.hours, 1.minutes, sources, bucket)
    }

    private fun populate() {
        db.addDevice(MPPT, DeviceRole.SOLAR_CHARGER)
        db.addDevice(BMS, DeviceRole.BATTERY)
        db.addDevice(DCDC, DeviceRole.DCDC)
        dates.forEachIndexed { index, day ->
            when {
                index == 3 || index == 4 -> Unit
                index == 6 -> { feedDay(day, 6..9, 2.0, dcdcState = 3.0); feedDay(day, 10..14, 2.0, dcdcState = 0.0) }
                index == 7 -> feedDay(day, 0..23, 2.0, dcdcState = 2.0)
                index == 9 -> feedDay(day, 10..12, 4.0, yield = 0.75, dcdcState = 0.0)
                index >= 17 -> feedDay(day, 0..23, 1.0, dcdcState = 0.0, bucket = Level.MINUTE)
                else -> feedDay(day, 0..23, 1.0 + index % 3, dcdcState = 0.0)
            }
        }
    }

    private fun assertSameAsDayByDay() {
        val consumption = stats.consumption(dates, zone, now)
        val solar = stats.solarProduction(dates, zone)
        assertEquals(dates.toSet(), consumption.keys)
        assertEquals(dates.toSet(), solar.keys)
        for (day in dates) {
            assertEquals(stats.consumption(listOf(day), zone, now).getValue(day), consumption.getValue(day), "consommation du $day")
            assertEquals(stats.solarProduction(listOf(day), zone).getValue(day), solar.getValue(day), "production du $day")
        }
        assertTrue(consumption.values.count { it.value != null } >= 12, "le jeu d'essai doit comporter des jours mesurés")
        assertTrue(consumption.values.count { it.value == null } >= 2, "le jeu d'essai doit comporter des jours sans mesure")
        assertTrue(consumption.values.any { (it.coverage ?: 1.0) < 1.0 }, "le jeu d'essai doit comporter un jour à couverture partielle")
        assertTrue(solar.values.any { it.nature == Nature.DEVICE_COMPUTED } && solar.values.any { it.nature == Nature.CALCULATED })
    }

    @Test
    fun aRangeGivesWhatEachDayGivesAlone() {
        populate()
        assertSameAsDayByDay()
    }

    @Test
    fun aRangeGivesWhatEachDayGivesAloneOnceConsolidated() {
        populate()
        Rollup(db).rebuild(now, zone)
        assertSameAsDayByDay()
    }

    @Test
    fun aRangeOverTheDaylightSavingChangeKeepsEachLocalDayApart() {
        populate()
        val change = LocalDate(2026, 3, 29)
        assertTrue(change in dates)
        val whole = stats.consumption(dates, zone, now).getValue(change)
        assertEquals(stats.consumption(listOf(change), zone, now).getValue(change), whole)
    }

    @Test
    fun noDaysGiveNothing() {
        populate()
        assertEquals(emptyMap(), stats.consumption(emptyList(), zone, now))
        assertEquals(emptyMap(), stats.solarProduction(emptyList(), zone))
    }

    @Test
    fun theDaysOfOneRangeAreNotMixedWithTheirNeighbours() {
        populate()
        val middle = dates[10]
        val alone = stats.consumption(listOf(middle), zone, now).getValue(middle)
        val inRange = stats.consumption(dates.subList(8, 14), zone, now).getValue(middle)
        assertEquals(alone, inRange)
        assertTrue(alone.value != null)
    }
}
