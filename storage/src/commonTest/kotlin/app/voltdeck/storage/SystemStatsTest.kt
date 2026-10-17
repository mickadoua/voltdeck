package app.voltdeck.storage

import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Metric
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SystemStatsTest {
    private val db = createTestDatabase()
    private val stats = SystemStats(db)
    private val day = LocalDate(2026, 3, 10)
    private val from = EPOCH_2026 + 10.hours
    private val to = EPOCH_2026 + 12.hours
    private val now = EPOCH_2026 + 1.days + 1.hours
    private val step = 30.seconds

    private fun constant(vararg values: Pair<Metric, Double>): (Instant) -> Map<Metric, Double>? = { values.toMap() }

    private fun unavailable(stat: Stat, absence: Absence) {
        assertEquals(Nature.UNAVAILABLE, stat.nature, "attendu : absent ($absence), obtenu $stat")
        assertEquals(absence, stat.absence)
        assertNull(stat.value)
    }

    // --- Production solaire ---

    @Test
    fun solarProductionIsTheLastDeviceYieldOfTheDay() {
        db.addDevice(MPPT, DeviceRole.SOLAR_CHARGER)
        db.feed(from, to, 1.minutes, mapOf(MPPT to { t -> mapOf(Metric.YIELD_TODAY to (t - from).inWholeMinutes / 80.0, Metric.PV_POWER to 100.0) }))
        val result = stats.solarProduction(day, UTC)
        assertEquals(Nature.DEVICE_COMPUTED, result.nature)
        assertEquals(1.5, result.value!!, 1e-9)
        assertEquals("kWh", result.unit)
        assertEquals(1.0, result.coverage)
    }

    @Test
    fun solarProductionFallsBackOnTheIntegralOfPvPower() {
        db.addDevice(MPPT, DeviceRole.SOLAR_CHARGER)
        db.feed(from, to, step, mapOf(MPPT to constant(Metric.PV_POWER to 500.0)))
        val result = stats.solarProduction(day, UTC)
        assertEquals(Nature.CALCULATED, result.nature)
        assertEquals(1.0, result.value!!, 1e-9)
    }

    @Test
    fun solarProductionSumsSeveralChargers() {
        val second = app.voltdeck.core.DeviceId("mppt-2")
        db.addDevice(MPPT, DeviceRole.SOLAR_CHARGER)
        db.addDevice(second, DeviceRole.SOLAR_CHARGER)
        db.feed(from, to, 1.minutes, mapOf(MPPT to constant(Metric.YIELD_TODAY to 1.25), second to constant(Metric.YIELD_TODAY to 0.75)))
        assertEquals(2.0, stats.solarProduction(day, UTC).value!!, 1e-9)
    }

    @Test
    fun solarProductionIsAbsentWithoutAChargerOrWithoutData() {
        unavailable(stats.solarProduction(day, UTC), Absence.NO_SOURCE)
        db.addDevice(MPPT, DeviceRole.SOLAR_CHARGER)
        unavailable(stats.solarProduction(day, UTC), Absence.NO_DATA)
    }

    @Test
    fun aChargerRegisteredAfterTheDayIsNotExpectedToHaveData() {
        db.addDevice(MPPT, DeviceRole.SOLAR_CHARGER, createdAt = EPOCH_2026 + 5.days)
        unavailable(stats.solarProduction(day, UTC), Absence.NO_SOURCE)
    }

    // --- Consommation ---

    private fun consumptionSetup(batteryAmps: Double = 3.0) {
        db.addDevice(MPPT, DeviceRole.SOLAR_CHARGER)
        db.addDevice(BMS, DeviceRole.BATTERY)
        db.feed(
            from, to, step,
            mapOf(
                MPPT to constant(Metric.BATTERY_VOLTAGE to 12.0, Metric.BATTERY_CURRENT to 5.0, Metric.PV_POWER to 1000.0),
                BMS to constant(Metric.BATTERY_VOLTAGE to 12.0, Metric.BATTERY_CURRENT to batteryAmps),
            ),
        )
    }

    @Test
    fun consumptionIsMpptBatterySideMinusNetBatteryFlow() {
        consumptionSetup()
        val result = stats.consumption(day, UTC, now)
        assertEquals(Nature.CALCULATED, result.nature)
        assertEquals((5.0 - 3.0) * 12.0 * 2, result.value!!, 1e-6)
        assertEquals(1.0, result.coverage)
    }

    @Test
    fun consumptionNeverUsesPvPower() {
        consumptionSetup()
        assertEquals(48.0, stats.consumption(day, UTC, now).value!!, 1e-6)
    }

    @Test
    fun theShuntBeatsTheBmsForTheBatteryFlow() {
        consumptionSetup(batteryAmps = 100.0)
        db.addDevice(SHUNT, DeviceRole.SHUNT)
        db.feed(from, to, step, mapOf(SHUNT to constant(Metric.BATTERY_VOLTAGE to 12.0, Metric.BATTERY_CURRENT to 3.0)))
        assertEquals(48.0, stats.consumption(day, UTC, now).value!!, 1e-6)
    }

    @Test
    fun aDcDcInChargeMakesItsMinutesIncompleteAndTheCoverageShowsIt() {
        consumptionSetup()
        db.addDevice(DCDC, DeviceRole.DCDC)
        db.feed(from, to, step, mapOf(DCDC to { t -> mapOf(Metric.DEVICE_STATE to if (t - from < 30.minutes) 3.0 else 0.0) }))
        val result = stats.consumption(day, UTC, now)
        assertEquals(Nature.CALCULATED, result.nature)
        assertEquals(0.75, result.coverage!!, 1e-9)
        assertEquals(36.0, result.value!!, 1e-6)
    }

    @Test
    fun aDcDcWithoutAnyStateMasksEverything() {
        consumptionSetup()
        db.addDevice(DCDC, DeviceRole.DCDC)
        unavailable(stats.consumption(day, UTC, now), Absence.INCOMPLETE)
    }

    @Test
    fun aDcDcThatStaysOffOrFaultedIsComplete() {
        consumptionSetup()
        db.addDevice(DCDC, DeviceRole.DCDC)
        db.feed(from, to, step, mapOf(DCDC to { t -> mapOf(Metric.DEVICE_STATE to if ((t - from).inWholeMinutes % 2 == 0L) 0.0 else 2.0) }))
        assertEquals(1.0, stats.consumption(day, UTC, now).coverage)
    }

    @Test
    fun aMissingMpptMasksTheMinutesItMissed() {
        db.addDevice(MPPT, DeviceRole.SOLAR_CHARGER)
        db.addDevice(BMS, DeviceRole.BATTERY)
        db.feed(
            from, to, step,
            mapOf(
                MPPT to { t -> if (t - from in 30.minutes..<60.minutes) null else mapOf(Metric.BATTERY_VOLTAGE to 12.0, Metric.BATTERY_CURRENT to 5.0) },
                BMS to constant(Metric.BATTERY_VOLTAGE to 12.0, Metric.BATTERY_CURRENT to 3.0),
            ),
        )
        val result = stats.consumption(day, UTC, now)
        assertEquals(0.75, result.coverage!!, 0.02)
        assertEquals(true, result.value!! < 48.0 && result.value!! > 30.0)
    }

    @Test
    fun consumptionIsAbsentWithoutABatteryFlowSensor() {
        db.addDevice(MPPT, DeviceRole.SOLAR_CHARGER)
        db.feed(from, to, step, mapOf(MPPT to constant(Metric.BATTERY_VOLTAGE to 12.0, Metric.BATTERY_CURRENT to 5.0)))
        unavailable(stats.consumption(day, UTC, now), Absence.NO_FLOW_SENSOR)
    }

    @Test
    fun consumptionIsAbsentWithoutAnyMeasuredSource() {
        db.addDevice(BMS, DeviceRole.BATTERY)
        db.feed(from, to, step, mapOf(BMS to constant(Metric.BATTERY_VOLTAGE to 12.0, Metric.BATTERY_CURRENT to -3.0)))
        unavailable(stats.consumption(day, UTC, now), Absence.NO_SOURCE)
    }

    @Test
    fun aNegativeConsumptionIsRefusedNotClamped() {
        consumptionSetup(batteryAmps = 9.0)
        unavailable(stats.consumption(day, UTC, now), Absence.INCONSISTENT)
    }

    @Test
    fun consumptionAfterTheMinuteRetentionIsComputedFromHours() {
        consumptionSetup()
        val later = now + 60.days
        Maintenance(db).run(later, UTC)
        assertEquals(0L, db.minuteQueries.countMinutes().executeAsOne())
        val result = stats.consumption(day, UTC, later)
        assertEquals(48.0, result.value!!, 1e-6)
        assertEquals(1.0, result.coverage)
    }

    // --- Autonomie ---

    private val longFrom = EPOCH_2026
    private val longTo = EPOCH_2026 + 25.hours + 30.minutes
    private val nowLong = longTo

    private fun battery(amps: Double, until: Instant = longTo, remaining: Double = 100.0) {
        db.addDevice(BMS, DeviceRole.BATTERY)
        db.feed(longFrom, until, 1.minutes, mapOf(BMS to constant(Metric.BATTERY_VOLTAGE to 12.0, Metric.BATTERY_CURRENT to amps, Metric.REMAINING_CAPACITY to remaining)))
    }

    @Test
    fun autonomyIsTheShuntTimeToGoWhenItExists() {
        battery(-5.0)
        db.addDevice(SHUNT, DeviceRole.SHUNT)
        db.feed(nowLong - 5.minutes, nowLong, 1.minutes, mapOf(SHUNT to constant(Metric.TIME_TO_GO to 600.0)))
        val result = stats.autonomy(nowLong)
        assertEquals(Nature.DEVICE_COMPUTED, result.nature)
        assertEquals(10.0, result.value!!, 1e-9)
    }

    @Test
    fun autonomyIsEstimatedFromRemainingCapacityAndTheDayAverage() {
        battery(-5.0)
        val result = stats.autonomy(nowLong)
        assertEquals(Nature.ESTIMATED, result.nature)
        assertEquals(20.0, result.value!!, 1e-6)
        assertEquals("h", result.unit)
    }

    @Test
    fun aStaleShuntTimeToGoFallsBackOnTheEstimate() {
        battery(-5.0)
        db.addDevice(SHUNT, DeviceRole.SHUNT)
        db.feed(nowLong - 3.hours, nowLong - 2.hours, 1.minutes, mapOf(SHUNT to constant(Metric.TIME_TO_GO to 600.0)))
        assertEquals(Nature.ESTIMATED, stats.autonomy(nowLong).nature)
    }

    @Test
    fun autonomyIsMaskedUnderSixHoursOfData() {
        battery(-5.0, until = EPOCH_2026 + 5.hours + 30.minutes)
        unavailable(stats.autonomy(EPOCH_2026 + 5.hours + 30.minutes), Absence.INSUFFICIENT_HISTORY)
    }

    @Test
    fun autonomyIsMaskedInNetCharge() {
        battery(+5.0)
        unavailable(stats.autonomy(nowLong), Absence.NET_CHARGE)
    }

    @Test
    fun autonomyIsMaskedWithoutRemainingCapacity() {
        db.addDevice(BMS, DeviceRole.BATTERY)
        db.feed(longFrom, longTo, 1.minutes, mapOf(BMS to constant(Metric.BATTERY_VOLTAGE to 12.0, Metric.BATTERY_CURRENT to -5.0)))
        unavailable(stats.autonomy(nowLong), Absence.NO_DATA)
    }

    @Test
    fun autonomyIsMaskedWhenTheRemainingCapacityIsStale() {
        battery(-5.0, until = longTo - 2.hours)
        unavailable(stats.autonomy(nowLong), Absence.STALE)
    }

    // --- Cycles ---

    @Test
    fun cyclesAreTheBmsCounterAsIs() {
        db.addDevice(BMS, DeviceRole.BATTERY)
        db.feed(from, from + 5.minutes, 1.minutes, mapOf(BMS to constant(Metric.CYCLES to 37.0)))
        val result = stats.cycles(null, now, UTC)
        assertEquals(Nature.DEVICE_COMPUTED, result.nature)
        assertEquals(37.0, result.value!!)
    }

    @Test
    fun cyclesFromTheShuntAreEquivalentCyclesAndEstimated() {
        db.addDevice(SHUNT, DeviceRole.SHUNT, createdAt = EPOCH_2026 - 1.hours)
        db.feed(from, from + 10.hours, 30.seconds, mapOf(SHUNT to constant(Metric.BATTERY_VOLTAGE to 12.0, Metric.BATTERY_CURRENT to -10.0)))
        val result = stats.cycles(200.0, now, UTC)
        assertEquals(Nature.ESTIMATED, result.nature)
        assertEquals(100.0 / 200.0, result.value!!, 1e-6)
    }

    @Test
    fun cyclesFromTheShuntAreMaskedWithoutAnEnteredCapacity() {
        db.addDevice(SHUNT, DeviceRole.SHUNT)
        db.feed(from, from + 1.hours, 30.seconds, mapOf(SHUNT to constant(Metric.BATTERY_VOLTAGE to 12.0, Metric.BATTERY_CURRENT to -10.0)))
        unavailable(stats.cycles(null, now, UTC), Absence.NO_CAPACITY)
        unavailable(stats.cycles(0.0, now, UTC), Absence.NO_CAPACITY)
    }

    @Test
    fun cyclesAreAbsentWithoutAnyDevice() {
        unavailable(stats.cycles(200.0, now, UTC), Absence.NO_FLOW_SENSOR)
    }

    // --- Mesures brutes ---

    @Test
    fun aMeasuredValueIsTheLatestOneAndGoesStale() {
        db.addDevice(BMS, DeviceRole.BATTERY)
        db.feed(from, from + 5.minutes, 1.minutes, mapOf(BMS to constant(Metric.SOC to 80.0)))
        val fresh = stats.measured(BMS, Metric.SOC, from + 6.minutes)
        assertEquals(Nature.MEASURED, fresh.nature)
        assertEquals(80.0, fresh.value)
        unavailable(stats.measured(BMS, Metric.SOC, from + 1.hours), Absence.STALE)
        unavailable(stats.measured(BMS, Metric.BATTERY_VOLTAGE, from + 6.minutes), Absence.NO_DATA)
    }
}
