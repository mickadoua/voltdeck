// TEST ONLY — les clés et annonces ci-dessous sont des vecteurs de test publics, pas des secrets.
//
// Sources du format : Victron Energy, « Extra manufacturer Data », version du 2022-12-14,
// https://communityarchive.victronenergy.com/storage/attachments/extra-manufacturer-data-2022-12-14.pdf
// Sources des vecteurs : keshavdv/victron-ble (Unlicense), tests/test_battery_monitor.py, tests/test_solar_charger.py,
// tests/test_dcdc_converter.py ; rajlaud/victron-ble-ha-parser (Apache-2.0), tests/ (mêmes annonces et mêmes clés).
// Les valeurs attendues sont celles que ces projets affirment ; les écarts voulus sont signalés en commentaire.

package app.voltdeck.drivers.victron

import app.voltdeck.ble.Adv
import app.voltdeck.ble.BleTransport
import app.voltdeck.ble.ConnectionPolicy
import app.voltdeck.ble.GattProfile
import app.voltdeck.ble.PlatformDeviceId
import app.voltdeck.ble.ReadOnlyGattLink
import app.voltdeck.ble.ScanFilter
import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Metric
import app.voltdeck.drivers.KnownDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

private class ScriptedTransport(private val advs: List<Adv>) : BleTransport {
    override fun advertisements(filter: ScanFilter): Flow<Adv> = advs.filter { filter.matches(it) }.asFlow()

    override suspend fun connect(id: PlatformDeviceId, profile: GattProfile): ReadOnlyGattLink =
        error("Le pilote Victron ne se connecte jamais")
}

private object FixedClock : Clock {
    override fun now(): Instant = Instant.fromEpochSeconds(1_000)
}

class VictronInstantReadoutDriverTest {
    private val platformId = PlatformDeviceId("test-shunt")
    private val shunt = KnownDevice(DeviceId("shunt-1"), platformId, DeviceRole.SHUNT)

    private fun adv(data: ByteArray, id: PlatformDeviceId = platformId) =
        Adv(id, "SmartShunt", -60, mapOf(ConnectionPolicy.VICTRON_COMPANY_ID to data))

    private fun driver(keys: VictronKeyStore) = VictronInstantReadoutDriver(keys, FixedClock)

    @Test
    fun readsTheKnownDeviceOnly() = runTest {
        val keys = InMemoryVictronKeyStore().apply { put(shunt.id, keyOf(Vectors.BATTERY_MONITOR_KEY)) }
        val other = PlatformDeviceId("test-other")
        val transport = ScriptedTransport(
            listOf(adv(hex(Vectors.BATTERY_MONITOR_ADV), other), adv(hex(Vectors.BATTERY_MONITOR_ADV))),
        )
        val d = driver(keys)
        val readings = d.readings(shunt, transport).toList()
        assertEquals(1, readings.size)
        assertEquals(shunt.id, readings[0].deviceId)
        assertEquals(Instant.fromEpochSeconds(1_000), readings[0].at)
        assertEquals(12.53, readings[0].samples.value(Metric.BATTERY_VOLTAGE))
        assertEquals(VictronLinkStatus.OK, d.diagnostics.status.value[shunt.id])
    }

    @Test
    fun wrongKeyGivesNoReadingAndAStatus() = runTest {
        val keys = InMemoryVictronKeyStore().apply { put(shunt.id, keyOf(Vectors.SOLAR_KEY)) }
        val d = driver(keys)
        val readings = d.readings(shunt, ScriptedTransport(listOf(adv(hex(Vectors.BATTERY_MONITOR_ADV))))).toList()
        assertEquals(emptyList(), readings)
        assertEquals(VictronLinkStatus.WRONG_KEY, d.diagnostics.status.value[shunt.id])
        assertEquals(0, d.diagnostics.truncatedFrames)
    }

    @Test
    fun missingKeyGivesNoReadingAndAStatus() = runTest {
        val d = driver(InMemoryVictronKeyStore())
        val readings = d.readings(shunt, ScriptedTransport(listOf(adv(hex(Vectors.BATTERY_MONITOR_ADV))))).toList()
        assertEquals(emptyList(), readings)
        assertEquals(VictronLinkStatus.NO_KEY, d.diagnostics.status.value[shunt.id])
    }

    @Test
    fun truncatedFramesAreSkippedAndCounted() = runTest {
        val keys = InMemoryVictronKeyStore().apply { put(shunt.id, keyOf(Vectors.BATTERY_MONITOR_KEY)) }
        val good = hex(Vectors.BATTERY_MONITOR_ADV)
        val transport = ScriptedTransport(
            listOf(adv(good.copyOf(5)), adv(good.copyOf(good.size - 1)), adv(good), adv(good.copyOf(12))),
        )
        val d = driver(keys)
        val readings = d.readings(shunt, transport).toList()
        assertEquals(1, readings.size, "seule la trame complète est décodée")
        assertEquals(3, d.diagnostics.truncatedFrames)
        assertEquals(VictronLinkStatus.OK, d.diagnostics.status.value[shunt.id])
    }

    @Test
    fun aKeyThatMatchesAnotherKindOfDeviceDoesNotProduceReadings() = runTest {
        val mppt = KnownDevice(DeviceId("mppt-1"), platformId, DeviceRole.SOLAR_CHARGER)
        val keys = InMemoryVictronKeyStore().apply { put(mppt.id, keyOf(Vectors.BATTERY_MONITOR_KEY)) }
        val readings = driver(keys).readings(mppt, ScriptedTransport(listOf(adv(hex(Vectors.BATTERY_MONITOR_ADV))))).toList()
        assertEquals(emptyList(), readings, "un SmartShunt ne peut pas alimenter un équipement enregistré comme MPPT")
    }

    @Test
    fun identifiesTheThreeSupportedKinds() {
        val d = driver(InMemoryVictronKeyStore())
        val cases = listOf(
            Triple(Vectors.SOLAR_ADV, DeviceRole.SOLAR_CHARGER, "0xA042"),
            Triple(Vectors.BATTERY_MONITOR_ADV, DeviceRole.SHUNT, "0xA389"),
            Triple(Vectors.DCDC_ADV, DeviceRole.DCDC, "0xA3C0"),
        )
        for ((advHex, role, model) in cases) {
            val candidate = assertNotNull(d.identify(adv(hex(advHex))))
            assertEquals("victron.ir", candidate.driverId)
            assertEquals(role, candidate.role)
            assertEquals(model, candidate.model)
        }
    }

    @Test
    fun doesNotIdentifyOtherAdvertisements() {
        val d = driver(InMemoryVictronKeyStore())
        assertNull(d.identify(Adv(platformId, "x", -50, emptyMap())))
        assertNull(d.identify(Adv(platformId, "x", -50, mapOf(0x004C to hex(Vectors.SOLAR_ADV)))))
        assertNull(d.identify(adv(hex(Vectors.SOLAR_ADV).also { it[0] = 0x02 })))
        assertNull(d.identify(adv(hex(Vectors.SOLAR_ADV).also { it[4] = 0x03 })))
        assertNull(d.identify(adv(hex(Vectors.SOLAR_ADV).copyOf(6))))
    }

    @Test
    fun theDriverNeverConnects() = runTest {
        val transport = ScriptedTransport(emptyList())
        assertFailsWith<IllegalStateException> { transport.connect(platformId, GattProfile.Jbd) }
        assertEquals("victron.ir", driver(InMemoryVictronKeyStore()).driverId)
    }
}
