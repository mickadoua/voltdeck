// TEST ONLY — clés publiques du simulateur uniquement.

package app.voltdeck

import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Metric
import app.voltdeck.core.Reading
import app.voltdeck.core.Sample
import app.voltdeck.drivers.victron.InMemoryVictronKeyStore
import app.voltdeck.drivers.victron.VictronLinkStatus
import app.voltdeck.simulator.SimulatedPlant
import app.voltdeck.simulator.SimulatedVictron
import app.voltdeck.simulator.SimulationScenario
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DemoSessionTest {
    private val realStart = Instant.parse("2026-10-16T14:12:00Z")

    private fun TestScope.virtualClock() = object : Clock {
        override fun now() = realStart + testScheduler.currentTime.milliseconds
    }

    private fun TestScope.session(scenario: SimulationScenario) =
        DemoSession(scenario, InMemoryVictronKeyStore(), virtualClock(), TimeZone.UTC)

    private fun List<Sample>.value(metric: Metric) = first { it.metric == metric }.value

    @Test
    fun sunnyDayShowsTheMpptAndTheShuntWithValuesMatchingTheModel() = runTest {
        val session = session(SimulationScenario.SUNNY_DAY)
        val truthPlant = SimulatedPlant(seed = 1L, start = demoStart(realStart, TimeZone.UTC), zone = TimeZone.UTC)
        val truth = HashMap<Instant, List<Reading>>()

        val got = session.readings().take(3 * 420).toList()

        val mppt = got.filter { it.deviceId == SimulatedPlant.MPPT_ID }
        val shunt = got.filter { it.deviceId == SimulatedPlant.SHUNT_ID }
        assertEquals(420, mppt.size)
        assertEquals(420, shunt.size)
        assertTrue(mppt.any { it.samples.value(Metric.PV_POWER) > 100.0 }, "du soleil doit apparaître avant 13 h simulées")
        (mppt + shunt).sortedBy { it.at }.forEach { r ->
            val model = truth.getOrPut(r.at) { truthPlant.readingsAt(r.at) }
            if (r.deviceId == SimulatedPlant.MPPT_ID) {
                val expected = model[1].samples
                assertEquals(expected.value(Metric.PV_POWER), r.samples.value(Metric.PV_POWER))
                assertEquals(expected.value(Metric.BATTERY_VOLTAGE), r.samples.value(Metric.BATTERY_VOLTAGE), 0.0051)
                assertEquals(expected.value(Metric.BATTERY_CURRENT), r.samples.value(Metric.BATTERY_CURRENT), 0.051)
                assertEquals(expected.value(Metric.YIELD_TODAY), r.samples.value(Metric.YIELD_TODAY), 0.0051)
                assertEquals(expected.value(Metric.DEVICE_STATE), r.samples.value(Metric.DEVICE_STATE))
            } else {
                val expected = model[2].samples
                assertEquals(expected.value(Metric.SOC), r.samples.value(Metric.SOC), 0.051)
                assertEquals(expected.value(Metric.CONSUMED_AH), r.samples.value(Metric.CONSUMED_AH), 0.051)
                assertEquals(expected.value(Metric.BATTERY_CURRENT), r.samples.value(Metric.BATTERY_CURRENT), 0.0011)
                assertEquals(expected.firstOrNull { it.metric == Metric.TIME_TO_GO }?.value, r.samples.firstOrNull { it.metric == Metric.TIME_TO_GO }?.value)
            }
        }
        assertEquals(VictronLinkStatus.OK, session.driver.diagnostics.status.value[session.mppt.deviceId])
        assertEquals(VictronLinkStatus.OK, session.driver.diagnostics.status.value[session.shunt.deviceId])
    }

    @Test
    fun theBatteryCardStillComesFromTheModel() = runTest {
        val got = session(SimulationScenario.SUNNY_DAY).readings().take(6).toList()
        assertTrue(got.any { it.deviceId == SimulatedPlant.BATTERY_ID })
    }

    @Test
    fun wrongKeyScenarioShowsNoVictronValuesUntilTheDeviceKeyIsEntered() = runTest {
        val session = session(SimulationScenario.WRONG_VICTRON_KEY)
        val got = mutableListOf<Reading>()
        backgroundScope.launch { session.readings().collect { got += it } }

        advanceTimeBy(3.seconds)
        runCurrent()
        assertTrue(got.none { it.deviceId == SimulatedPlant.MPPT_ID || it.deviceId == SimulatedPlant.SHUNT_ID })
        assertEquals(VictronLinkStatus.WRONG_KEY, session.driver.diagnostics.status.value[session.mppt.deviceId])
        assertEquals(VictronLinkStatus.WRONG_KEY, session.driver.diagnostics.status.value[session.shunt.deviceId])

        // La clé de test publique est refusée : ce n'est pas celle de l'appareil de ce scénario.
        val refused = session.submitKey(session.mppt, SimulatedVictron.deviceKeyHex(SimulationScenario.SUNNY_DAY))
        assertTrue(refused is KeyEntryOutcome.Rejected)

        assertEquals(KeyEntryOutcome.Saved, session.submitKey(session.mppt, session.deviceKeyHex))
        advanceTimeBy(3.seconds)
        runCurrent()
        assertTrue(got.any { it.deviceId == SimulatedPlant.MPPT_ID }, "la clé de l'appareil débloque le MPPT")
        assertTrue(got.none { it.deviceId == SimulatedPlant.SHUNT_ID }, "le SmartShunt a toujours la mauvaise clé")
        assertEquals(VictronLinkStatus.OK, session.driver.diagnostics.status.value[session.mppt.deviceId])
    }

    @Test
    fun victronDevicesHaveTheRightRoles() = runTest {
        val session = session(SimulationScenario.SUNNY_DAY)
        assertEquals(DeviceRole.SOLAR_CHARGER, session.mppt.role)
        assertEquals(DeviceRole.SHUNT, session.shunt.role)
    }
}
