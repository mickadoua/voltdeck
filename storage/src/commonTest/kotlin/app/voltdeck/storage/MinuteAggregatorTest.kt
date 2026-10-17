package app.voltdeck.storage

import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Metric
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MinuteAggregatorTest {
    private val db = createTestDatabase().also { it.addDevice(BMS, DeviceRole.BATTERY) }
    private val t0 = Instant.parse("2026-03-10T10:00:00Z")

    private fun minutes(metric: Metric) = db.stored(Level.MINUTE, BMS, metric)

    @Test
    fun keepsCountMinMaxSumLastPerMinute() {
        val aggregator = MinuteAggregator(db)
        listOf(12.0, 14.0, 13.0).forEachIndexed { i, v ->
            aggregator.add(reading(BMS, t0 + (10 * (i + 1)).seconds, Metric.BATTERY_VOLTAGE to v))
        }
        aggregator.flushAll()
        val row = minutes(Metric.BATTERY_VOLTAGE).single()
        assertEquals(t0, row.start)
        assertEquals(AggStats(3, 12.0, 14.0, 39.0, 13.0), row.stats)
    }

    @Test
    fun writesAMinuteOnlyOnceItIsClosed() {
        val aggregator = MinuteAggregator(db)
        aggregator.add(reading(BMS, t0 + 10.seconds, Metric.SOC to 50.0))
        assertTrue(minutes(Metric.SOC).isEmpty(), "la minute en cours reste en mémoire")
        aggregator.add(reading(BMS, t0 + 70.seconds, Metric.SOC to 51.0))
        assertEquals(1, minutes(Metric.SOC).size)
        aggregator.flushClosed(t0 + 130.seconds)
        assertEquals(2, minutes(Metric.SOC).size)
    }

    @Test
    fun stopMidMinuteThenResumeNeverDoublesTheSum() {
        val first = MinuteAggregator(db)
        first.add(reading(BMS, t0 + 5.seconds, Metric.BATTERY_VOLTAGE to 10.0))
        first.add(reading(BMS, t0 + 15.seconds, Metric.BATTERY_VOLTAGE to 20.0))
        first.flushAll()
        first.flushAll()
        val second = MinuteAggregator(db)
        second.add(reading(BMS, t0 + 25.seconds, Metric.BATTERY_VOLTAGE to 30.0))
        second.add(reading(BMS, t0 + 35.seconds, Metric.BATTERY_VOLTAGE to 40.0))
        second.flushAll()
        second.flushAll()
        val stats = minutes(Metric.BATTERY_VOLTAGE).single().stats
        assertEquals(AggStats(4, 10.0, 40.0, 100.0, 40.0), stats)
    }

    @Test
    fun aFailedWriteLosesNothingAndDoublesNothing() {
        val stranger = DeviceId("not-registered")
        val aggregator = MinuteAggregator(db)
        aggregator.add(reading(BMS, t0 + 5.seconds, Metric.SOC to 50.0))
        aggregator.add(reading(stranger, t0 + 6.seconds, Metric.SOC to 99.0))
        assertFails { aggregator.flushAll() }
        assertTrue(minutes(Metric.SOC).isEmpty(), "la transaction de la minute est annulée en bloc")
        assertEquals(2, aggregator.pendingCount)

        db.addDevice(stranger, DeviceRole.BATTERY)
        aggregator.flushAll()
        assertEquals(0, aggregator.pendingCount)
        assertEquals(1L, minutes(Metric.SOC).single().stats.count)
        assertEquals(1L, db.stored(Level.MINUTE, stranger, Metric.SOC).single().stats.count)
    }

    @Test
    fun constantPowerIntegratesToWattHoursAndAmpHours() {
        val aggregator = MinuteAggregator(db)
        for (s in 0..60 step 10) {
            aggregator.add(reading(BMS, t0 + s.seconds, Metric.BATTERY_VOLTAGE to 12.0, Metric.BATTERY_CURRENT to 10.0))
        }
        aggregator.flushAll()
        assertEquals(2.0, minutes(Metric.ENERGY_IN_WH).sumOf { it.stats.sum }, 1e-9)
        assertEquals(10.0 * 60 / 3600, minutes(Metric.CHARGE_IN_AH).sumOf { it.stats.sum }, 1e-9)
        assertEquals(0.0, minutes(Metric.ENERGY_OUT_WH).sumOf { it.stats.sum }, 1e-9)
    }

    @Test
    fun dischargeGoesToTheOutColumns() {
        val aggregator = MinuteAggregator(db)
        for (s in 0..60 step 20) {
            aggregator.add(reading(BMS, t0 + s.seconds, Metric.BATTERY_VOLTAGE to 12.0, Metric.BATTERY_CURRENT to -5.0))
        }
        aggregator.flushAll()
        assertEquals(1.0, minutes(Metric.ENERGY_OUT_WH).sumOf { it.stats.sum }, 1e-9)
        assertEquals(5.0 / 60, minutes(Metric.CHARGE_OUT_AH).sumOf { it.stats.sum }, 1e-9)
        assertEquals(0.0, minutes(Metric.ENERGY_IN_WH).sumOf { it.stats.sum }, 1e-9)
    }

    @Test
    fun tenMinuteGapInventsNoEnergy() {
        val aggregator = MinuteAggregator(db)
        aggregator.add(reading(BMS, t0, Metric.BATTERY_VOLTAGE to 12.0, Metric.BATTERY_CURRENT to 10.0))
        aggregator.add(reading(BMS, t0 + 10.minutes, Metric.BATTERY_VOLTAGE to 12.0, Metric.BATTERY_CURRENT to 10.0))
        aggregator.flushAll()
        assertTrue(minutes(Metric.CHARGE_IN_AH).isEmpty())
        assertTrue(minutes(Metric.ENERGY_IN_WH).isEmpty())
        assertEquals(2, minutes(Metric.BATTERY_CURRENT).size, "les mesures brutes, elles, sont bien enregistrées")
    }

    @Test
    fun photovoltaicEnergyComesFromPvPower() {
        val aggregator = MinuteAggregator(db)
        for (s in 0..120 step 30) aggregator.add(reading(BMS, t0 + s.seconds, Metric.PV_POWER to 360.0))
        aggregator.flushAll()
        assertEquals(360.0 * 120 / 3600, minutes(Metric.PV_ENERGY_WH).sumOf { it.stats.sum }, 1e-9)
    }

    @Test
    fun aDriverCannotInjectDerivedMetrics() {
        val aggregator = MinuteAggregator(db)
        aggregator.add(reading(BMS, t0, Metric.ENERGY_IN_WH to 1e6, Metric.SOC to 50.0))
        aggregator.flushAll()
        assertNull(minutes(Metric.ENERGY_IN_WH).firstOrNull())
        assertEquals(1, minutes(Metric.SOC).size)
    }

    @Test
    fun aMissingCurrentCutsTheIntegrationChain() {
        val aggregator = MinuteAggregator(db)
        aggregator.add(reading(BMS, t0, Metric.BATTERY_CURRENT to 10.0))
        aggregator.add(reading(BMS, t0 + 20.seconds, Metric.SOC to 50.0))
        aggregator.add(reading(BMS, t0 + 40.seconds, Metric.BATTERY_CURRENT to 10.0))
        aggregator.flushAll()
        assertTrue(minutes(Metric.CHARGE_IN_AH).isEmpty())
    }
}
