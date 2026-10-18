package app.voltdeck

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Metric
import app.voltdeck.core.Reading
import app.voltdeck.core.Sample
import app.voltdeck.storage.AggregateReader
import app.voltdeck.storage.DeviceStore
import app.voltdeck.storage.EnergyHistory
import app.voltdeck.storage.MinuteAggregator
import app.voltdeck.storage.SystemStats
import app.voltdeck.storage.db.VoltdeckDatabase
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class LoadStatsTest {
    private val zone = TimeZone.UTC
    private val mppt = DeviceId("mppt-1")
    private val bms = DeviceId("bms-1")
    private val db: VoltdeckDatabase = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY + "?foreign_keys=true").let {
        VoltdeckDatabase.Schema.create(it)
        VoltdeckDatabase(it)
    }
    private val october = StatsPeriod(PeriodKind.MONTH, LocalDate(2026, 10, 10))
    private val now = Instant.parse("2026-10-06T12:00:00Z")

    init {
        DeviceStore(db).register(mppt, "test", DeviceRole.SOLAR_CHARGER, "mppt", "a-mppt", null, Instant.parse("2020-01-01T00:00:00Z"))
        DeviceStore(db).register(bms, "test", DeviceRole.BATTERY, "bms", "a-bms", null, Instant.parse("2020-01-01T00:00:00Z"))
    }

    private fun collect(day: LocalDate, hours: Int, startHour: Int = 0, watts: Double = 100.0, mpptHours: Int = hours, mpptStartHour: Int = startHour) {
        val aggregator = MinuteAggregator(db)
        var t = day.atStartOfDayIn(zone) + startHour.hours
        val end = t + hours.hours
        val mpptFrom = day.atStartOfDayIn(zone) + mpptStartHour.hours
        val mpptTo = mpptFrom + mpptHours.hours
        while (t < end) {
            if (t >= mpptFrom && t < mpptTo) {
                aggregator.add(Reading(mppt, t, listOf(Sample(Metric.PV_POWER, 0, watts), Sample(Metric.BATTERY_VOLTAGE, 0, 13.0), Sample(Metric.BATTERY_CURRENT, 0, watts / 13.0))))
            }
            aggregator.add(Reading(bms, t, listOf(Sample(Metric.BATTERY_VOLTAGE, 0, 13.0), Sample(Metric.BATTERY_CURRENT, 0, 2.0))))
            t += 60.seconds
        }
        aggregator.flushAll()
    }

    private fun load() = loadStats(SystemStats(db), EnergyHistory(db), AggregateReader(db), bms, october, zone, now)

    @Test
    fun aPartiallyCollectedDayIsNotPairedWithAFullOne() {
        // septembre : 1er jour collecté 2 h seulement ; octobre : journées entières
        collect(LocalDate(2026, 9, 1), hours = 2, startHour = 22)
        for (d in 1..5) collect(LocalDate(2026, 10, d), hours = 24)
        assertNull(load().producedComparison, "2 h de septembre face à 24 h d'octobre ne se comparent pas")
        assertNull(load().consumedComparison)
    }

    @Test
    fun wellMeasuredDaysOfBothSidesAreCompared() {
        for (d in 1..3) collect(LocalDate(2026, 9, d), hours = 24, watts = 50.0)
        collect(LocalDate(2026, 9, 4), hours = 2, startHour = 22, watts = 50.0)
        for (d in 1..5) collect(LocalDate(2026, 10, d), hours = 24, watts = 100.0)
        val comparison = assertNotNull(load().producedComparison)
        assertEquals(3, comparison.days)
        assertEquals(ComparisonBasis.COMMON_DAYS, comparison.basis)
        assertEquals(100.0, comparison.percent, 1.0)
    }

    @Test
    fun consumptionIsNotComparedWhenTheSourceWasMissedForPartOfTheDay() {
        // le BMS est lu 24 h, le MPPT seulement 8 h en octobre : la consommation calculée ne couvre que ces 8 h
        for (d in 1..3) collect(LocalDate(2026, 9, d), hours = 24, watts = 100.0)
        for (d in 1..3) collect(LocalDate(2026, 10, d), hours = 24, watts = 100.0, mpptHours = 8, mpptStartHour = 8)
        assertNull(load().consumedComparison, "8 h de sources face à 24 h ne se comparent pas")
    }

    @Test
    fun consumptionIsNotComparedWhenAConverterWasDeliveringForPartOfTheDay() {
        val dcdc = DeviceId("dcdc-1")
        DeviceStore(db).register(dcdc, "test", DeviceRole.DCDC, "orion", "a-dcdc", null, Instant.parse("2020-01-01T00:00:00Z"))

        fun converterStates(day: LocalDate, chargingFrom: Int?, chargingHours: Int) {
            val aggregator = MinuteAggregator(db)
            var t = day.atStartOfDayIn(zone)
            val end = t + 24.hours
            while (t < end) {
                val charging = chargingFrom != null && t >= day.atStartOfDayIn(zone) + chargingFrom.hours && t < day.atStartOfDayIn(zone) + (chargingFrom + chargingHours).hours
                aggregator.add(Reading(dcdc, t, listOf(Sample(Metric.DEVICE_STATE, 0, if (charging) 3.0 else 0.0))))
                t += 60.seconds
            }
            aggregator.flushAll()
        }
        // même consommation réelle chaque jour ; en octobre le DC-DC débite 10 h par jour, donc seules 14 h sont calculables
        for (d in 1..3) { collect(LocalDate(2026, 9, d), hours = 24); converterStates(LocalDate(2026, 9, d), null, 0) }
        for (d in 1..3) { collect(LocalDate(2026, 10, d), hours = 24); converterStates(LocalDate(2026, 10, d), 6, 10) }
        val data = load()
        assertNotNull(data.producedComparison, "la collecte est complète des deux côtés")
        assertNull(data.consumedComparison, "14 h calculables sur 24 face à une journée entière ne se comparent pas")
    }
}
