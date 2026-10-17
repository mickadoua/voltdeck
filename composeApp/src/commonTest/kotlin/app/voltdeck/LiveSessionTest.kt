// TEST ONLY — clés publiques du simulateur uniquement.

package app.voltdeck

import app.voltdeck.ble.PlatformDeviceId
import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.drivers.victron.InMemoryVictronKeyStore
import app.voltdeck.simulator.SimulatedTransport
import app.voltdeck.simulator.SimulatedVictron
import app.voltdeck.simulator.SimulationScenario
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionTest {
    private val noon = Instant.parse("2026-06-21T12:00:00Z")

    private fun TestScope.clock() = object : Clock {
        override fun now() = noon + testScheduler.currentTime.milliseconds
    }

    private fun TestScope.session(registry: DeviceRegistry = InMemoryDeviceRegistry()): LiveSession {
        val clock = clock()
        val transport = SimulatedTransport(seed = 1L, scenario = SimulationScenario.SUNNY_DAY, clock = clock, zone = TimeZone.UTC)
        return LiveSession(transport, InMemoryVictronKeyStore(), registry, backgroundScope, clock)
    }

    private suspend fun LiveSession.waitFor(role: DeviceRole): Found = discover().first { list -> list.any { it.candidate.role == role } }.first { it.candidate.role == role }

    @Test
    fun discoveryRecognisesTheBatteryTheChargerAndTheShunt() = runTest {
        val found = session().discover().first { it.size == 3 }
        assertEquals(setOf(DeviceRole.BATTERY, DeviceRole.SOLAR_CHARGER, DeviceRole.SHUNT), found.map { it.candidate.role }.toSet())
    }

    @Test
    fun aVictronDeviceIsAddedOnlyWithTheRightKey() = runTest {
        val registry = InMemoryDeviceRegistry()
        val s = session(registry)
        val mppt = s.waitFor(DeviceRole.SOLAR_CHARGER)
        val wrong = "0".repeat(32)
        assertIs<AddOutcome.Rejected>(s.addVictron(mppt, "Toit", wrong))
        assertTrue(registry.all().isEmpty())
        assertEquals(AddOutcome.Added, s.addVictron(mppt, "Toit", SimulatedVictron.deviceKeyHex(SimulationScenario.SUNNY_DAY)))
        val added = registry.all().single()
        assertEquals("Toit", added.name)
        assertEquals(DeviceRole.SOLAR_CHARGER, added.role)
    }

    @Test
    fun aJbdBatteryIsConfirmedBeforeBeingAddedAndThenRead() = runTest {
        val registry = InMemoryDeviceRegistry()
        val s = session(registry)
        val battery = s.waitFor(DeviceRole.BATTERY)
        assertEquals(AddOutcome.Added, s.addJbd(battery, "Lithium"))
        val id = registry.all().single().id
        val reading = s.readings().first { it.deviceId == id }
        assertTrue(reading.samples.isNotEmpty())
        assertEquals("Lithium", s.title(id))
    }

    @Test
    fun addedDevicesAreNoLongerOffered() = runTest {
        val s = session()
        val shunt = s.waitFor(DeviceRole.SHUNT)
        s.addVictron(shunt, "Shunt", SimulatedVictron.deviceKeyHex(SimulationScenario.SUNNY_DAY))
        val next = s.discover().first { it.size == 2 }
        assertTrue(next.none { it.candidate.role == DeviceRole.SHUNT })
    }

    @Test
    fun noDeviceMeansNoReading() = runTest {
        assertTrue(session().readings().take(1).toList().isEmpty())
    }

    @Test
    fun theRegistrySurvivesEncodingAndReplacesTheSameEquipment() {
        var text: String? = null
        val registry = TextDeviceRegistry({ text }, { text = it })
        val a = RegisteredDevice(DeviceId("a"), "victron.ir", DeviceRole.SHUNT, PlatformDeviceId("p1"), "Shunt · coffre", "0xA389")
        registry.add(a)
        registry.add(RegisteredDevice(DeviceId("b"), "jbd.gatt", DeviceRole.BATTERY, PlatformDeviceId("p2"), "Batterie", null))
        registry.add(RegisteredDevice(DeviceId("c"), "victron.ir", DeviceRole.SHUNT, PlatformDeviceId("p1"), "Shunt", null))
        assertEquals(listOf("b", "c"), TextDeviceRegistry({ text }, {}).all().map { it.id.value })
        registry.remove(DeviceId("b"))
        assertEquals(listOf("c"), registry.all().map { it.id.value })
        assertEquals(null, RegisteredDevice.decode("abîmé"))
    }
}
