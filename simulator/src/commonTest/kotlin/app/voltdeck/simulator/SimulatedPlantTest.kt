package app.voltdeck.simulator

import app.voltdeck.core.Metric
import app.voltdeck.core.Reading
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SimulatedPlantTest {
    private val start = Instant.parse("2026-06-20T06:00:00Z")

    private fun run(seed: Long, steps: Int = 24): List<Reading> {
        val plant = SimulatedPlant(seed, start, TimeZone.UTC)
        return (0 until steps).flatMap { plant.readingsAt(start + (it * 30).minutes) }
    }

    @Test
    fun sameSeedGivesIdenticalReadings() {
        assertEquals(run(seed = 42), run(seed = 42))
    }

    @Test
    fun differentSeedsGiveDifferentReadings() {
        assertNotEquals(run(seed = 42), run(seed = 43))
    }

    @Test
    fun resultDoesNotDependOnHowOftenItIsSampled() {
        val sparse = SimulatedPlant(7, start, TimeZone.UTC)
        val dense = SimulatedPlant(7, start, TimeZone.UTC)
        (0..120).forEach { dense.readingsAt(start + it.minutes) }
        val at = start + 2.hours
        assertEquals(sparse.readingsAt(at), dense.readingsAt(at))
    }

    @Test
    fun emitsABatteryAnMpptAndAShuntWithSensibleValues() {
        val plant = SimulatedPlant(1, start, TimeZone.UTC)
        val noon = plant.readingsAt(start + 6.hours)
        assertEquals(listOf(SimulatedPlant.BATTERY_ID, SimulatedPlant.MPPT_ID, SimulatedPlant.SHUNT_ID), noon.map { it.deviceId })
        val (battery, mppt) = noon
        fun Reading.value(metric: Metric, channel: Int = 0) =
            samples.firstOrNull { it.metric == metric && it.channel == channel }?.value

        assertTrue(battery.value(Metric.SOC)!! in 0.0..100.0)
        assertTrue(battery.value(Metric.BATTERY_VOLTAGE)!! in 11.0..14.6)
        assertEquals(200.0, battery.value(Metric.NOMINAL_CAPACITY))
        assertEquals(4, battery.samples.count { it.metric == Metric.CELL_VOLTAGE })
        assertTrue(mppt.value(Metric.PV_POWER)!! > 100.0, "plein soleil à midi")
        assertTrue(mppt.value(Metric.BATTERY_CURRENT)!! > 0.0)
        assertNull(mppt.value(Metric.LOAD_CURRENT), "un champ absent est absent, pas à 0")
    }

    @Test
    fun nightMeansNoSolarAndTheBatteryDischarges() {
        val plant = SimulatedPlant(1, start, TimeZone.UTC)
        val night = plant.readingsAt(start + 18.hours)
        val battery = night[0].samples.first { it.metric == Metric.BATTERY_CURRENT }.value
        val pv = night[1].samples.first { it.metric == Metric.PV_POWER }.value
        assertEquals(0.0, pv)
        assertTrue(battery < 0.0, "courant < 0 = la batterie se décharge")
    }

    @Test
    fun socStaysWithinBoundsOverThreeDays() {
        val plant = SimulatedPlant(3, start, TimeZone.UTC)
        for (m in 0 until 3 * 24 * 60 step 17) {
            val soc = plant.readingsAt(start + m.minutes)[0].samples.first { it.metric == Metric.SOC }.value
            assertTrue(soc in 0.0..100.0, "SOC hors bornes : $soc")
        }
    }

    @Test
    fun yieldTodayResetsAtLocalMidnight() {
        val plant = SimulatedPlant(1, start, TimeZone.UTC)
        fun yieldAt(offset: Duration) =
            plant.readingsAt(start + offset)[1].samples.first { it.metric == Metric.YIELD_TODAY }.value

        val evening = yieldAt(15.hours)
        assertTrue(evening > 0.5, "rendement du jour attendu après une journée de soleil : $evening")
        assertTrue(yieldAt(19.hours) < 0.01, "remise à zéro après minuit")
    }

    @Test
    fun readingsFlowEmitsTheThreeDevicesEachTick() = runTest {
        val plant = SimulatedPlant(1, start, TimeZone.UTC)
        val clock = object : Clock {
            override fun now() = start + testScheduler.currentTime.minutes
        }
        val got = plant.readings(clock).take(6).toList()
        val ids = listOf(SimulatedPlant.BATTERY_ID, SimulatedPlant.MPPT_ID, SimulatedPlant.SHUNT_ID)
        assertEquals(ids + ids, got.map { it.deviceId })
        assertTrue(got[3].at > got[0].at)
    }

    @Test
    fun shuntSeesTheBatteryAndReportsConsumedAhAsNegative() {
        val plant = SimulatedPlant(1, start, TimeZone.UTC)
        val (battery, _, shunt) = plant.readingsAt(start + 30.minutes)
        fun Reading.value(m: Metric) = samples.first { it.metric == m }.value
        assertEquals(battery.value(Metric.SOC), shunt.value(Metric.SOC))
        assertEquals(battery.value(Metric.BATTERY_VOLTAGE), shunt.value(Metric.BATTERY_VOLTAGE))
        assertTrue(shunt.value(Metric.CONSUMED_AH) < 0.0, "Ah consommés attendus négatifs")
    }

    @Test
    fun timeToGoIsAbsentWhileChargingAndPresentWhileDischarging() {
        val plant = SimulatedPlant(1, start, TimeZone.UTC)
        val noon = plant.readingsAt(start + 6.hours)[2]
        assertTrue(noon.samples.none { it.metric == Metric.TIME_TO_GO }, "pas de temps restant en charge")
        val night = SimulatedPlant(1, start, TimeZone.UTC).readingsAt(start + 18.hours)[2]
        assertTrue(night.samples.first { it.metric == Metric.TIME_TO_GO }.value > 0.0)
    }
}
