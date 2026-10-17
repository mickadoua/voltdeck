// TEST ONLY — clés de vecteurs de test publics.

package app.voltdeck.drivers.victron

import app.voltdeck.ble.Adv
import app.voltdeck.ble.ConnectionPolicy
import app.voltdeck.ble.PlatformDeviceId
import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.drivers.KnownDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private class CountingStore : VictronKeyStore {
    val inner = InMemoryVictronKeyStore()
    var gets = 0
    var failPut = false
    var onGet: (() -> Unit)? = null

    override fun get(device: DeviceId): VictronKey? {
        gets++
        val value = inner.get(device)
        onGet?.invoke()
        return value
    }

    override fun put(device: DeviceId, key: VictronKey) {
        if (failPut) error("coffre indisponible")
        inner.put(device, key)
    }

    override fun remove(device: DeviceId) = inner.remove(device)
}

class VictronKeyStoreCacheTest {
    private val device = DeviceId("d1")
    private val keyA = keyOf(Vectors.SOLAR_KEY)
    private val keyB = keyOf(Vectors.BATTERY_MONITOR_KEY)

    @Test
    fun theVaultIsReadOncePerDevice() {
        val vault = CountingStore().apply { inner.put(device, keyA) }
        val cache = CachingVictronKeyStore(vault)
        repeat(50) { assertEquals(keyA, cache.get(device)) }
        assertEquals(1, vault.gets)
    }

    @Test
    fun putAndRemoveInvalidateTheCache() {
        val vault = CountingStore().apply { inner.put(device, keyA) }
        val cache = CachingVictronKeyStore(vault)
        cache.get(device)
        cache.put(device, keyB)
        assertEquals(keyB, cache.get(device))
        assertEquals(keyB, vault.inner.get(device))
        cache.remove(device)
        assertNull(cache.get(device))
        assertNull(vault.inner.get(device))
    }

    @Test
    fun anAbsentKeyIsNeverCached() {
        val vault = CountingStore()
        val cache = CachingVictronKeyStore(vault)
        assertNull(cache.get(device))
        vault.inner.put(device, keyA) // clé apparue après une lecture ratée (panne transitoire du coffre)
        assertEquals(keyA, cache.get(device))
    }

    @Test
    fun aFailedPutDoesNotLeaveTheOldKeyInTheCache() {
        val vault = CountingStore().apply { inner.put(device, keyA) }
        val cache = CachingVictronKeyStore(vault)
        cache.get(device)
        vault.failPut = true
        assertFailsWith<IllegalStateException> { cache.put(device, keyB) }
        vault.gets = 0
        cache.get(device)
        assertEquals(1, vault.gets, "le cache est invalidé : la vérité est relue dans le coffre")
    }

    @Test
    fun aReadInFlightDuringARemoveDoesNotResurrectTheKey() {
        val vault = CountingStore().apply { inner.put(device, keyA) }
        val cache = CachingVictronKeyStore(vault)
        vault.onGet = { vault.onGet = null; cache.remove(device) }
        cache.get(device)
        assertNull(cache.get(device), "la clé supprimée pendant la lecture ne revient pas par le cache")
    }

    @Test
    fun theDriverReadsTheVaultOncePerDeviceNotOncePerAdvertisement() = runTest {
        val vault = CountingStore()
        val platformId = PlatformDeviceId("cache-shunt")
        val shunt = KnownDevice(DeviceId("shunt-1"), platformId, DeviceRole.SHUNT)
        vault.inner.put(shunt.deviceId, keyOf(Vectors.BATTERY_MONITOR_KEY))
        val driver = VictronInstantReadoutDriver(CachingVictronKeyStore(vault), FixedClock)
        val adv = Adv(platformId, "x", -60, mapOf(ConnectionPolicy.VICTRON_COMPANY_ID to hex(Vectors.BATTERY_MONITOR_ADV)))
        val readings = driver.readings(shunt, ScriptedTransport(List(20) { adv })).toList()
        assertEquals(20, readings.size)
        assertEquals(1, vault.gets)
    }

    @Test
    fun diagnosticsCountersSurviveConcurrentWriters() = runTest {
        val diagnostics = VictronDiagnostics()
        withContext(Dispatchers.Default) {
            (1..8).map { async { repeat(5_000) { diagnostics.frameTruncated() } } }.awaitAll()
        }
        assertEquals(40_000, diagnostics.truncatedFrames)
    }

    @Test
    fun diagnosticsStatusesOfTwoDevicesDoNotOverwriteEachOther() = runTest {
        repeat(300) {
            val diagnostics = VictronDiagnostics()
            val devices = (1..8).map { DeviceId("d$it") }
            withContext(Dispatchers.Default) {
                devices.map { id -> async { diagnostics.setStatus(id, VictronLinkStatus.OK) } }.awaitAll()
            }
            assertEquals(devices.toSet(), diagnostics.status.value.keys)
        }
    }
}
