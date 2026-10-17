package app.voltdeck

import app.voltdeck.core.Metric
import app.voltdeck.simulator.JbdScenario
import app.voltdeck.simulator.SimulatedPlant
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DemoSourceTest {
    @Test
    fun demoStartsAt0630LocalOnTheCurrentDay() {
        val now = Instant.parse("2026-10-16T14:12:00Z")
        assertEquals(Instant.parse("2026-10-16T06:30:00Z"), demoStart(now, TimeZone.UTC))
        assertEquals(Instant.parse("2026-10-16T04:30:00Z"), demoStart(now, TimeZone.of("Europe/Paris")))
    }

    private fun TestScope.realClock() = object : Clock {
        override fun now() = Instant.parse("2026-10-16T14:12:00Z") + testScheduler.currentTime.milliseconds
    }

    @Test
    fun demoTimeRunsSixtyTimesFasterThanRealTime() = runTest {
        val got = demoReadings(realClock(), TimeZone.UTC).take(4).toList()
        assertEquals(setOf(SimulatedPlant.BATTERY_ID, SimulatedPlant.MPPT_ID), got.take(2).map { it.deviceId }.toSet())
        val batteries = got.filter { it.deviceId == SimulatedPlant.BATTERY_ID }
        assertEquals(1.seconds * DEMO_SPEED.toInt(), batteries[1].at - batteries[0].at)
        assertTrue(batteries[0].at == Instant.parse("2026-10-16T06:30:00Z"))
    }

    @Test
    fun theBatteryComesFromTheJbdDriverWithCellsTemperaturesAndAlarms() = runTest {
        val battery = demoReadings(realClock(), TimeZone.UTC).take(6).toList().first { it.deviceId == SimulatedPlant.BATTERY_ID }
        assertEquals(4, battery.samples.count { it.metric == Metric.CELL_VOLTAGE })
        assertEquals(2, battery.samples.count { it.metric == Metric.TEMPERATURE })
        for (metric in listOf(Metric.BATTERY_VOLTAGE, Metric.BATTERY_CURRENT, Metric.SOC, Metric.CYCLES, Metric.PROTECTION_FLAGS)) {
            assertTrue(battery.samples.any { it.metric == metric }, metric.name)
        }
        assertEquals(0.0, battery.samples.first { it.metric == Metric.PROTECTION_FLAGS }.value)
    }

    @Test
    fun theMpptStillComesFromThePlant() = runTest {
        val mppt = demoReadings(realClock(), TimeZone.UTC).take(6).toList().filter { it.deviceId == SimulatedPlant.MPPT_ID }
        assertTrue(mppt.size >= 2)
        assertTrue(mppt.all { reading -> reading.samples.any { it.metric == Metric.PV_POWER } })
    }

    @Test
    fun theProtectionAlarmScenarioReachesTheScreenData() = runTest {
        val got = demoReadings(realClock(), TimeZone.UTC, JbdScenario.BmsProtectionAlarm(after = 2.minutes)).take(12).toList()
        val flags = got.filter { it.deviceId == SimulatedPlant.BATTERY_ID }.map { r -> r.samples.first { it.metric == Metric.PROTECTION_FLAGS }.value }
        assertEquals(0.0, flags.first())
        assertEquals(1.0, flags.last())
    }
}
