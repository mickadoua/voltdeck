package app.voltdeck.ble

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ConnectionPolicyTest {
    private val id = PlatformDeviceId("test-device")

    @Test
    fun victronAdvertisementIsNeverConnectable() {
        val victron = Adv(id, null, -60, mapOf(ConnectionPolicy.VICTRON_COMPANY_ID to byteArrayOf(1, 2, 3)))
        assertFailsWith<ForbiddenConnectionException> { ConnectionPolicy.requireConnectable(victron) }
    }

    @Test
    fun jbdAdvertisementIsConnectable() {
        ConnectionPolicy.requireConnectable(Adv(id, null, -60, serviceUuids = listOf(GattProfile.Jbd.serviceUuid)))
    }

    @Test
    fun jbdProfileIsTheFixedFf00Triplet() {
        assertEquals("0000ff00-0000-1000-8000-00805f9b34fb", GattProfile.Jbd.serviceUuid)
        assertEquals("0000ff01-0000-1000-8000-00805f9b34fb", GattProfile.Jbd.notifyCharacteristicUuid)
        assertEquals("0000ff02-0000-1000-8000-00805f9b34fb", GattProfile.Jbd.requestCharacteristicUuid)
    }

    @Test
    fun advertisementCopiesItsBytesAndHidesTheName() {
        val source = byteArrayOf(1, 2, 3)
        val adv = Adv(id, "SERIAL-HQ2345", -60, mapOf(0x1234 to source))
        source.fill(9)
        assertContentEquals(byteArrayOf(1, 2, 3), adv.manufacturerData.getValue(0x1234))
        assertFalse("SERIAL-HQ2345" in adv.toString())
    }
}
