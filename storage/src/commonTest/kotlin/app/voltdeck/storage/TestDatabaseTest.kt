package app.voltdeck.storage

import app.voltdeck.core.DeviceRole
import kotlin.test.Test
import kotlin.test.assertEquals

class TestDatabaseTest {
    @Test
    fun eachTestDatabaseIsPrivate() {
        val first = createTestDatabase()
        first.addDevice(BMS, DeviceRole.BATTERY)
        val second = createTestDatabase()
        assertEquals(0, second.deviceQueries.selectDevices().executeAsList().size, "la seconde base ne doit rien voir de la première")
        second.addDevice(BMS, DeviceRole.BATTERY)
        assertEquals(1, first.deviceQueries.selectDevices().executeAsList().size)
    }
}
