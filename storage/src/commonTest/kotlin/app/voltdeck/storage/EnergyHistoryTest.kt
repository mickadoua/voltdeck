package app.voltdeck.storage

import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Metric
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class EnergyHistoryTest {
    private val db = createTestDatabase()
    private val from = EPOCH_2026 + 10.hours
    private val to = EPOCH_2026 + 12.hours

    @Test
    fun consumptionIsProductionMinusTheBatteryFlowHourByHour() {
        db.addDevice(MPPT, DeviceRole.SOLAR_CHARGER)
        db.addDevice(BMS, DeviceRole.BATTERY)
        db.feed(
            from, to - 1.seconds, 10.seconds,
            mapOf(
                MPPT to { _ -> mapOf(Metric.BATTERY_VOLTAGE to 13.0, Metric.BATTERY_CURRENT to 10.0) },
                BMS to { _ -> mapOf(Metric.BATTERY_VOLTAGE to 13.0, Metric.BATTERY_CURRENT to 4.0) },
            ),
        )
        val buckets = EnergyHistory(db).buckets(Level.HOUR, from, to, UTC)
        assertEquals(2, buckets.size)
        val first = buckets.first()
        assertEquals(130.0, first.producedWh!!, 1.0)
        assertEquals(52.0, first.batteryNetWh!!, 1.0)
        assertEquals(78.0, first.consumedWh!!, 1.0)
    }

    @Test
    fun aLoadShuntIsNotTakenForTheBatteryFlow() {
        db.addDevice(MPPT, DeviceRole.SOLAR_CHARGER)
        db.addDevice(SHUNT, DeviceRole.SHUNT)
        db.feed(
            from, to - 1.seconds, 10.seconds,
            mapOf(
                MPPT to { _ -> mapOf(Metric.BATTERY_VOLTAGE to 13.0, Metric.BATTERY_CURRENT to 10.0) },
                SHUNT to { _ -> mapOf(Metric.BATTERY_VOLTAGE to 13.0, Metric.BATTERY_CURRENT to -3.0) },
            ),
        )
        val withShunt = EnergyHistory(db).buckets(Level.HOUR, from, to, UTC).first()
        assertTrue(withShunt.batteryNetWh!! < 0)
        val loadShunt = EnergyHistory(db) { it != SHUNT }.buckets(Level.HOUR, from, to, UTC).first()
        assertNull(loadShunt.batteryNetWh, "un shunt de circuit ne mesure pas la batterie")
        assertNull(loadShunt.consumedWh)
        assertEquals(130.0, loadShunt.producedWh!!, 1.0)
    }

    @Test
    fun anHourWithoutAnyMeasureIsAGapNotAZero() {
        db.addDevice(BMS, DeviceRole.BATTERY)
        db.feed(from, from + 1.hours - 1.seconds, 10.seconds, mapOf(BMS to { _ -> mapOf(Metric.BATTERY_VOLTAGE to 13.0, Metric.BATTERY_CURRENT to -2.0) }))
        val buckets = EnergyHistory(db).buckets(Level.HOUR, from, to, UTC)
        assertEquals(1, buckets.size)
        assertNull(buckets.single().producedWh)
        assertNull(buckets.single().consumedWh, "sans régulateur, la consommation n'est pas déduite")
    }

    @Test
    fun theUserTurnedLoadShuntIsIgnoredBySystemStatsToo() {
        db.addDevice(SHUNT, DeviceRole.SHUNT)
        db.feed(from, to, 10.seconds, mapOf(SHUNT to { _ -> mapOf(Metric.TIME_TO_GO to 600.0) }))
        assertEquals(Nature.DEVICE_COMPUTED, SystemStats(db).autonomy(to).nature)
        assertEquals(Nature.UNAVAILABLE, SystemStats(db) { it != SHUNT }.autonomy(to).nature)
    }

    private fun dayBucket(date: String, produced: Double?, net: Double?, consumed: Double?, zone: kotlinx.datetime.TimeZone = UTC) =
        EnergyBucket(kotlinx.datetime.LocalDate.parse(date).start(zone), produced, net, consumed)

    @Test
    fun aMonthIsTheSumOfItsDays() {
        val months = monthlyOf(
            listOf(
                dayBucket("2026-02-27", 100.0, 20.0, 80.0), dayBucket("2026-02-28", 200.0, 50.0, 150.0),
                dayBucket("2026-03-01", 300.0, 100.0, 200.0), dayBucket("2026-03-02", 400.0, -10.0, 410.0),
            ),
            UTC,
        )
        assertEquals(2, months.size)
        assertEquals(kotlinx.datetime.LocalDate(2026, 2, 1).start(UTC), months[0].start)
        assertEquals(listOf(300.0, 70.0, 230.0), listOf(months[0].producedWh, months[0].batteryNetWh, months[0].consumedWh))
        assertEquals(listOf(700.0, 90.0, 610.0), listOf(months[1].producedWh, months[1].batteryNetWh, months[1].consumedWh))
    }

    @Test
    fun aMonthWhereTheBatteryFlowWasMissingSomeDaysHasNoConsumption() {
        val months = monthlyOf(
            listOf(
                dayBucket("2026-03-01", 300.0, 100.0, 200.0),
                dayBucket("2026-03-02", 400.0, null, null),
                dayBucket("2026-03-03", 500.0, 100.0, 400.0),
            ),
            UTC,
        )
        assertEquals(1200.0, months.single().producedWh)
        assertEquals(200.0, months.single().batteryNetWh)
        assertNull(months.single().consumedWh, "production d'un mois entier moins flux de quelques jours : consommation gonflée")
    }

    @Test
    fun aMonthWithoutProductionKeepsTheFlowAndNoConsumption() {
        val months = monthlyOf(listOf(dayBucket("2026-03-01", null, 40.0, null), dayBucket("2026-03-02", null, 10.0, null)), UTC)
        assertNull(months.single().producedWh)
        assertEquals(50.0, months.single().batteryNetWh)
        assertNull(months.single().consumedWh)
    }

    @Test
    fun noDaysMakeNoMonths() {
        assertEquals(emptyList(), monthlyOf(emptyList(), UTC))
    }

    @Test
    fun monthsFollowTheLocalCalendarAcrossTheDaylightSavingChange() {
        val march = kotlinx.datetime.LocalDate(2026, 3, 31)
        val april = kotlinx.datetime.LocalDate(2026, 4, 1)
        val months = monthlyOf(listOf(dayBucket("2026-03-31", 10.0, 1.0, 9.0, PARIS), dayBucket("2026-04-01", 20.0, 2.0, 18.0, PARIS)), PARIS)
        assertEquals(listOf(march.firstOfMonth().start(PARIS), april.firstOfMonth().start(PARIS)), months.map { it.start })
        assertEquals(listOf(10.0, 20.0), months.map { it.producedWh })
    }

    @Test
    fun monthlyBucketsAreReadFromTheDaysOfTheDatabase() {
        db.addDevice(MPPT, DeviceRole.SOLAR_CHARGER)
        db.addDevice(BMS, DeviceRole.BATTERY)
        val sources = mapOf(
            MPPT to { _: kotlin.time.Instant -> mapOf(Metric.BATTERY_VOLTAGE to 13.0, Metric.BATTERY_CURRENT to 10.0) },
            BMS to { _: kotlin.time.Instant -> mapOf(Metric.BATTERY_VOLTAGE to 13.0, Metric.BATTERY_CURRENT to 4.0) },
        )
        val february = kotlinx.datetime.LocalDate(2026, 2, 27).start(UTC) + 10.hours
        val march = kotlinx.datetime.LocalDate(2026, 3, 2).start(UTC) + 10.hours
        db.feed(february, february + 1.hours - 1.seconds, 10.seconds, sources)
        db.feed(march, march + 2.hours - 1.seconds, 10.seconds, sources)
        val history = EnergyHistory(db)
        val months = history.buckets(Level.MONTH, kotlinx.datetime.LocalDate(2026, 1, 1).start(UTC), kotlinx.datetime.LocalDate(2027, 1, 1).start(UTC), UTC)
        assertEquals(listOf(kotlinx.datetime.LocalDate(2026, 2, 1).start(UTC), kotlinx.datetime.LocalDate(2026, 3, 1).start(UTC)), months.map { it.start })
        assertEquals(130.0, months[0].producedWh!!, 2.0)
        assertEquals(260.0, months[1].producedWh!!, 2.0)
        assertEquals(78.0, months[0].consumedWh!!, 2.0)
        val days = history.buckets(Level.DAY, kotlinx.datetime.LocalDate(2026, 1, 1).start(UTC), kotlinx.datetime.LocalDate(2027, 1, 1).start(UTC), UTC)
        assertEquals(days.sumOf { it.producedWh!! }, months.sumOf { it.producedWh!! }, 1e-6)
    }
}
