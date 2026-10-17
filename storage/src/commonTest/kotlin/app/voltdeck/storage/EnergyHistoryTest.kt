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
}
