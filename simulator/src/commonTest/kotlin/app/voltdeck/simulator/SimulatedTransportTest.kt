package app.voltdeck.simulator

import app.voltdeck.ble.AllowedCommand
import app.voltdeck.ble.ForbiddenCommandException
import app.voltdeck.ble.GattProfile
import app.voltdeck.ble.ReadAllowlist
import app.voltdeck.ble.ScanFilter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SimulatedTransportTest {
    private val profile = GattProfile(
        serviceUuid = SimulatedTransport.JBD_SERVICE_UUID,
        notifyCharacteristicUuid = "0000ff01-0000-1000-8000-00805f9b34fb",
        requestCharacteristicUuid = "0000ff02-0000-1000-8000-00805f9b34fb",
    )

    private fun frame(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun advertisesABatteryAndAnMppt() = runTest {
        val advs = SimulatedTransport(seed = 5).advertisements(ScanFilter.Any).take(2).toList()
        assertEquals(setOf(SimulatedTransport.BATTERY_ID, SimulatedTransport.MPPT_ID), advs.map { it.id }.toSet())
    }

    @Test
    fun filterSelectsTheVictronAdvertisementOnly() = runTest {
        val advs = SimulatedTransport().advertisements(ScanFilter(companyId = SimulatedTransport.VICTRON_COMPANY_ID))
            .take(3).toList()
        assertTrue(advs.all { it.id == SimulatedTransport.MPPT_ID })
    }

    @Test
    fun advertisementsAreDeterministicForTheSameSeed() = runTest {
        suspend fun rssis(seed: Long) = SimulatedTransport(seed).advertisements(ScanFilter.Any).take(6).toList().map { it.rssi }
        assertEquals(rssis(9), rssis(9))
    }

    @Test
    fun allowedCommandsAreAcceptedInOrder() = runTest {
        val transport = SimulatedTransport()
        val link = transport.connect(SimulatedTransport.BATTERY_ID, profile)
        ReadAllowlist.commands.forEach { link.request(it) }
        assertEquals(3, transport.acceptedFrames.size)
        assertContentEquals(AllowedCommand.JbdBasicInfo.frame(), transport.acceptedFrames[0])
    }

    @Test
    fun forbiddenFramesAreRefusedAndNothingIsRecorded() = runTest {
        val transport = SimulatedTransport()
        val link = transport.connect(SimulatedTransport.BATTERY_ID, profile) as SimulatedTransport.SimulatedGattLink
        listOf(
            frame(0xDD, 0x5A, 0xE1, 0x02, 0x00, 0x03, 0xFF, 0x1A, 0x77),
            frame(0xDD, 0x5A, 0x00, 0x02, 0x56, 0x78, 0xFF, 0x30, 0x77),
            frame(0xFF, 0xAA, 0x01, 0x00),
        ).forEach { assertFailsWith<ForbiddenCommandException> { link.requestFrame(it) } }
        assertTrue(transport.acceptedFrames.isEmpty())
    }

    @Test
    fun neverConnectsToAVictronDevice() = runTest {
        assertFailsWith<IllegalArgumentException> {
            SimulatedTransport().connect(SimulatedTransport.MPPT_ID, profile)
        }
    }
}
