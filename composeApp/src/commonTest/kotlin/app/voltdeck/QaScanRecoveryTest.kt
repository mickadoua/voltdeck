package app.voltdeck

import app.voltdeck.ble.Adv
import app.voltdeck.ble.BleTransport
import app.voltdeck.ble.GattProfile
import app.voltdeck.ble.PlatformDeviceId
import app.voltdeck.ble.ReadOnlyGattLink
import app.voltdeck.ble.ScanFilter
import app.voltdeck.simulator.SimulatedTransport
import app.voltdeck.simulator.SimulationScenario
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class QaScanRecoveryTest {
    /** Bluetooth coupé / permission retirée = le scan lève ; rallumé / accordé = il repart. */
    private class Switchable(private val inner: BleTransport, var failure: Throwable?) : BleTransport {
        override fun advertisements(filter: ScanFilter): Flow<Adv> = flow {
            failure?.let { throw it }
            inner.advertisements(filter).collect {
                failure?.let { f -> throw f }
                emit(it)
            }
        }
        override suspend fun connect(id: PlatformDeviceId, profile: GattProfile): ReadOnlyGattLink = inner.connect(id, profile)
    }

    private fun scenario(initial: Throwable?, body: suspend kotlinx.coroutines.test.TestScope.(Switchable, SharedScanTransport, () -> Int) -> Unit) = runTest {
        val noon = Instant.parse("2026-06-21T12:00:00Z")
        val clock = object : Clock { override fun now() = noon + testScheduler.currentTime.milliseconds }
        val sim = SimulatedTransport(seed = 1L, scenario = SimulationScenario.SUNNY_DAY, clock = clock, zone = TimeZone.UTC)
        val sw = Switchable(sim, initial)
        val shared = SharedScanTransport(sw, backgroundScope)
        var count = 0
        backgroundScope.launch { shared.advertisements(ScanFilter.Any).collect { count++ } }
        runCurrent()
        body(sw, shared) { count }
    }

    @Test
    fun bluetoothCutThenRestoredResumesWithoutCrash() = scenario(null) { sw, shared, count ->
        advanceTimeBy(10.seconds); runCurrent()
        val before = count()
        assertTrue(before > 0, "annonces reçues Bluetooth allumé: $before")
        assertNull(shared.scanError.value)

        sw.failure = IllegalStateException("Bluetooth is off")
        advanceTimeBy(10.seconds); runCurrent()
        assertEquals(SharedScanTransport.SCAN_ERROR_MESSAGE, shared.scanError.value, "alerte affichée Bluetooth coupé")
        val during = count()

        sw.failure = null
        advanceTimeBy(40.seconds); runCurrent()
        assertNull(shared.scanError.value, "alerte effacée au rallumage")
        assertTrue(count() > during, "les annonces reprennent: $during -> ${count()}")
    }

    @Test
    fun permissionDeniedFromTheStartShowsAnErrorThenRecoversOnceGranted() = scenario(IllegalStateException("BLUETOOTH_SCAN denied")) { sw, shared, count ->
        advanceTimeBy(5.seconds); runCurrent()
        assertNotNull(shared.scanError.value)
        assertEquals(0, count())
        sw.failure = null
        advanceTimeBy(40.seconds); runCurrent()
        assertNull(shared.scanError.value)
        assertTrue(count() > 0)
    }
}
