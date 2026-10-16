package app.voltdeck.ble

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScanFilterTest {
    private val adv = Adv(
        id = PlatformDeviceId("SECRET-PLATFORM-ID"),
        name = "SIM-BATT",
        rssi = -60,
        manufacturerData = mapOf(0x02E1 to byteArrayOf(0x10)),
        serviceUuids = listOf("0000ff00-0000-1000-8000-00805f9b34fb"),
    )

    @Test
    fun emptyFilterMatchesEverything() = assertTrue(ScanFilter.Any.matches(adv))

    @Test
    fun filtersCombineWithAnd() {
        assertTrue(ScanFilter(companyId = 0x02E1, namePrefix = "SIM").matches(adv))
        assertFalse(ScanFilter(companyId = 0x02E1, namePrefix = "XXX").matches(adv))
        assertFalse(ScanFilter(companyId = 0x1234).matches(adv))
    }

    @Test
    fun serviceUuidIsCaseInsensitive() =
        assertTrue(ScanFilter(serviceUuid = "0000FF00-0000-1000-8000-00805F9B34FB").matches(adv))

    @Test
    fun logsNeverExposeThePlatformId() {
        assertFalse(adv.toString().contains("SECRET"))
        assertFalse(adv.id.toString().contains("SECRET"))
    }
}
