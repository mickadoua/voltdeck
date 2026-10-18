package app.voltdeck

import app.voltdeck.ble.PlatformDeviceId
import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Metric
import app.voltdeck.core.Reading
import app.voltdeck.core.Sample
import app.voltdeck.drivers.KnownDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class OverviewTest {
    private val at = Instant.parse("2026-10-17T12:00:00Z")
    private val bms = KnownDevice(DeviceId("bms"), PlatformDeviceId("p-bms"), DeviceRole.BATTERY)
    private val mppt = KnownDevice(DeviceId("mppt"), PlatformDeviceId("p-mppt"), DeviceRole.SOLAR_CHARGER)
    private val shunt = KnownDevice(DeviceId("shunt"), PlatformDeviceId("p-shunt"), DeviceRole.SHUNT)
    private val dcdc = KnownDevice(DeviceId("dcdc"), PlatformDeviceId("p-dcdc"), DeviceRole.DCDC)

    private fun reading(device: KnownDevice, vararg samples: Pair<Metric, Double>) =
        device.deviceId to Reading(device.deviceId, at, samples.map { Sample(it.first, value = it.second) })

    private val bmsReading = reading(
        bms,
        Metric.BATTERY_VOLTAGE to 13.2, Metric.BATTERY_CURRENT to 5.0, Metric.SOC to 70.0,
        Metric.REMAINING_CAPACITY to 140.0, Metric.NOMINAL_CAPACITY to 200.0, Metric.CYCLES to 37.0,
    ).let { (id, r) ->
        id to r.copy(samples = r.samples + Sample(Metric.CELL_VOLTAGE, 0, 3.30) + Sample(Metric.CELL_VOLTAGE, 1, 3.32))
    }
    private val mpptReading = reading(mppt, Metric.PV_POWER to 200.0, Metric.BATTERY_VOLTAGE to 13.0, Metric.BATTERY_CURRENT to 12.0, Metric.DEVICE_STATE to 3.0)

    private fun setups(vararg pairs: Pair<KnownDevice, ShuntSetup>): (DeviceId) -> ShuntSetup {
        val map = pairs.associate { it.first.deviceId to it.second }
        return { map[it] ?: ShuntSetup.DEFAULT }
    }

    @Test
    fun consumptionIsSolarOnTheBatterySideMinusTheBatteryFlow() {
        val o = buildOverview(listOf(bms, mppt), mapOf(bmsReading, mpptReading), setups())
        assertEquals(13.0 * 12.0 - 13.2 * 5.0, o.consumption!!.watts, 1e-9)
        assertFalse(o.consumption!!.incomplete)
        assertEquals(BatterySource.BMS, o.battery!!.source)
        assertEquals(BatteryTrend.CHARGING, o.battery!!.trend)
        assertEquals(AutonomyGap.CHARGING, o.autonomy.gap)
        assertEquals(3.30, o.battery!!.cellMin)
        assertEquals(3.32, o.battery!!.cellMax)
    }

    @Test
    fun aMissingMetricStaysMissing() {
        val o = buildOverview(listOf(bms, mppt), mapOf(bmsReading, mpptReading), setups())
        assertNull(o.battery!!.temperature)
        assertNull(o.dcdc)
        assertNull(o.solar!!.yieldTodayKwh)
    }

    @Test
    fun withoutBatteryMeasureTheConsumptionIsHidden() {
        val o = buildOverview(listOf(mppt, shunt), mapOf(mpptReading), setups(shunt to ShuntSetup(ShuntPlacement.IGNORED)))
        assertNull(o.battery)
        assertNull(o.consumption)
    }

    @Test
    fun aBatteryShuntDrivesTheBatteryTileAndTheAutonomy() {
        val shuntReading = reading(shunt, Metric.BATTERY_VOLTAGE to 13.1, Metric.BATTERY_CURRENT to -4.0, Metric.SOC to 65.0, Metric.TIME_TO_GO to 600.0)
        val o = buildOverview(listOf(bms, shunt), mapOf(bmsReading, shuntReading), setups())
        val b = o.battery!!
        assertEquals(BatterySource.SHUNT, b.source)
        assertEquals(65.0, b.soc)
        assertEquals(-4.0, b.current)
        assertEquals(37.0, b.cycles)
        assertEquals(600.0, o.autonomy.minutes)
        assertTrue(o.loads.isEmpty())
    }

    @Test
    fun aLoadShuntBecomesAMeasuredLineAndLeavesTheBatteryToTheBms() {
        val shuntReading = reading(shunt, Metric.BATTERY_VOLTAGE to 13.0, Metric.BATTERY_CURRENT to -3.0, Metric.SOC to 99.0)
        val o = buildOverview(listOf(bms, shunt), mapOf(bmsReading, shuntReading), setups(shunt to ShuntSetup(ShuntPlacement.LOAD, Circuit.FRIDGE)))
        assertEquals(BatterySource.BMS, o.battery!!.source)
        assertEquals(70.0, o.battery!!.soc)
        val load = o.loads.single()
        assertEquals("Frigo", load.name)
        assertEquals(39.0, load.powerW!!, 1e-9)
        assertEquals(AutonomyGap.CHARGING, o.autonomy.gap)
    }

    @Test
    fun aSourceShuntAddsItsProductionToTheConsumption() {
        val shuntReading = reading(shunt, Metric.BATTERY_VOLTAGE to 14.0, Metric.BATTERY_CURRENT to 10.0)
        val o = buildOverview(
            listOf(bms, shunt), mapOf(bmsReading, shuntReading),
            setups(shunt to ShuntSetup(ShuntPlacement.SOURCE, Circuit.ALTERNATOR, "Moteur")),
        )
        assertEquals("Moteur", o.sources.single().name)
        assertEquals(140.0 - 13.2 * 5.0, o.consumption!!.watts, 1e-9)
    }

    @Test
    fun aRunningDcdcMakesTheConsumptionIncomplete() {
        val dcdcReading = reading(dcdc, Metric.INPUT_VOLTAGE to 14.1, Metric.OUTPUT_VOLTAGE to 13.4, Metric.DEVICE_STATE to 3.0)
        val o = buildOverview(listOf(bms, mppt, dcdc), mapOf(bmsReading, mpptReading, dcdcReading), setups())
        assertTrue(o.dcdc!!.running)
        assertTrue(o.consumption!!.incomplete)
    }

    @Test
    fun aRegisteredChargerWithoutReadingMakesTheConsumptionIncomplete() {
        val o = buildOverview(listOf(bms, mppt), mapOf(bmsReading), setups())
        assertNull(o.solar)
        assertTrue(o.consumption!!.incomplete)
    }

    @Test
    fun consumptionNeverGoesNegative() {
        val o = buildOverview(listOf(bms), mapOf(bmsReading), setups())
        assertEquals(0.0, o.consumption!!.watts)
    }

    @Test
    fun withoutAShuntTheAutonomyIsTheHistoryEstimateLabelledAsSuch() {
        val discharging = reading(bms, Metric.BATTERY_VOLTAGE to 13.0, Metric.BATTERY_CURRENT to -4.0, Metric.SOC to 60.0)
        val o = buildOverview(listOf(bms), mapOf(discharging), setups(), estimatedAutonomyHours = 20.5)
        assertEquals(1230.0, o.autonomy.minutes)
        assertTrue(o.autonomy.estimated)
        val charging = buildOverview(listOf(bms), mapOf(bmsReading), setups(), estimatedAutonomyHours = 20.5)
        assertEquals(AutonomyGap.CHARGING, charging.autonomy.gap)
    }

    private val capacitySetup = ShuntSetup(ShuntPlacement.BATTERY, capacityAh = 200.0)

    @Test
    fun aShuntOnlyBatteryShowsEstimatedCyclesOnceACapacityIsEntered() {
        val reading = reading(shunt, Metric.BATTERY_VOLTAGE to 12.8, Metric.BATTERY_CURRENT to -3.0, Metric.SOC to 80.0)
        val o = buildOverview(listOf(shunt), mapOf(reading), setups(shunt to capacitySetup), estimatedCycles = 2.4)
        assertEquals(2.4, o.battery!!.cycles)
        assertTrue(o.battery!!.cyclesEstimated)
    }

    @Test
    fun withoutCapacityNoCyclesAreShown() {
        val reading = reading(shunt, Metric.BATTERY_VOLTAGE to 12.8, Metric.BATTERY_CURRENT to -3.0, Metric.SOC to 80.0)
        assertNull(nominalCapacityAh(listOf(shunt), setups()))
        val o = buildOverview(listOf(shunt), mapOf(reading), setups(), estimatedCycles = null)
        assertNull(o.battery!!.cycles)
        assertFalse(o.battery!!.cyclesEstimated)
    }

    @Test
    fun theBmsCounterHasPriorityOverTheEstimate() {
        val shuntReading = reading(shunt, Metric.BATTERY_VOLTAGE to 12.8, Metric.BATTERY_CURRENT to -3.0, Metric.SOC to 80.0)
        val o = buildOverview(listOf(bms, shunt), mapOf(bmsReading, shuntReading), setups(shunt to capacitySetup), estimatedCycles = 2.4)
        assertEquals(37.0, o.battery!!.cycles)
        assertFalse(o.battery!!.cyclesEstimated)
    }

    @Test
    fun theCapacityComesFromTheShuntOnTheBatteryNegativeOnly() {
        assertEquals(200.0, nominalCapacityAh(listOf(shunt), setups(shunt to capacitySetup)))
        assertNull(nominalCapacityAh(listOf(bms), setups()))
        assertNull(nominalCapacityAh(listOf(shunt), setups(shunt to ShuntSetup(ShuntPlacement.LOAD, Circuit.FRIDGE))))
    }

    @Test
    fun anEstimateKeepsOneDecimalUnderTenCycles() {
        assertEquals("0,4 cycles", cyclesLabel(0.4, estimated = true))
        assertEquals("37 cycles", cyclesLabel(37.0, estimated = false))
        assertEquals("12 cycles", cyclesLabel(12.3, estimated = true))
        assertEquals("5 cycles", cyclesLabel(5.0, estimated = false))
    }
}
