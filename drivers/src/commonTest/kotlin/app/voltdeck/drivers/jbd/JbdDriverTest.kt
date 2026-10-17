package app.voltdeck.drivers.jbd

import app.voltdeck.ble.Adv
import app.voltdeck.ble.AllowedCommand
import app.voltdeck.ble.BleTransport
import app.voltdeck.ble.ForbiddenCommandException
import app.voltdeck.ble.GattProfile
import app.voltdeck.ble.PlatformDeviceId
import app.voltdeck.ble.ReadAllowlist
import app.voltdeck.ble.ReadOnlyGattLink
import app.voltdeck.ble.ScanFilter
import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Metric
import app.voltdeck.core.Reading
import app.voltdeck.drivers.KnownDevice
import app.voltdeck.simulator.JbdScenario
import app.voltdeck.simulator.SimulatedPlant
import app.voltdeck.simulator.SimulatedTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * TEST ONLY : les trames de référence viennent de patman15/aiobmsble (Apache-2.0) et de syssi/esphome-jbd-bms
 * (Apache-2.0), voir NOTICE. Les autres sont fabriquées par le simulateur ou construites ici.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JbdDriverTest {
    private val start = Instant.parse("2026-10-17T12:00:00Z")
    private val deviceId = DeviceId("battery-1")
    private val platformId = SimulatedTransport.BATTERY_ID
    private val device = KnownDevice(deviceId, platformId)

    private val basicFrame = hex(
        "DD03001D0618FEE101F201F4002A2C7C000000000000806403040" +
            "30B8B0B8A0B84F88477",
    )
    private val cellsFrame = hex("DD0400080D660D610D680D59FE3C77")

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun TestScope.virtualClock() = object : Clock {
        override fun now(): Instant = start + currentTime.milliseconds
    }

    private fun Reading.value(metric: Metric, channel: Int = 0): Double? =
        samples.firstOrNull { it.metric == metric && it.channel == channel }?.value

    private fun chunk(frame: ByteArray, size: Int) = frame.toList().chunked(size).map { it.toByteArray() }

    /** Lien scripté : répond à chaque requête avec les notifications données par [answer]. */
    private class ScriptedLink(
        private val answer: (AllowedCommand, Int) -> List<ByteArray>,
    ) : ReadOnlyGattLink {
        val requests = mutableListOf<AllowedCommand>()
        private val bus = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        override val notifications: Flow<ByteArray> = bus

        override suspend fun request(cmd: AllowedCommand) {
            requests += cmd
            answer(cmd, requests.size).forEach { bus.emit(it) }
        }

        override fun close() = Unit
    }

    private class ScriptedTransport(
        private val link: () -> ReadOnlyGattLink = { error("pas de lien") },
        private val failConnect: Boolean = false,
    ) : BleTransport {
        val connectAttempts = mutableListOf<Long>()
        var clockMillis: () -> Long = { 0L }
        override fun advertisements(filter: ScanFilter): Flow<Adv> = emptyFlow()

        override suspend fun connect(id: PlatformDeviceId, profile: GattProfile): ReadOnlyGattLink {
            connectAttempts += clockMillis()
            if (failConnect) throw IllegalStateException("connexion refusée")
            return link()
        }
    }

    private fun scriptedLink(
        basic: (Int) -> List<ByteArray> = { chunk(basicFrame, 20) },
        cells: (Int) -> List<ByteArray> = { chunk(cellsFrame, 20) },
        hardware: (Int) -> List<ByteArray> = { emptyList() },
    ) = ScriptedLink { cmd, n ->
        when (cmd) {
            AllowedCommand.JbdBasicInfo -> basic(n)
            AllowedCommand.JbdCellVoltages -> cells(n)
            AllowedCommand.JbdHardwareVersion -> hardware(n)
        }
    }

    // ---- identification ----

    @Test
    fun identifiesTheJbdServiceAndHintsTheVendorAddress() {
        val driver = JbdDriver()
        val adv = Adv(PlatformDeviceId("A4:C1:37-sim"), "SIM", -60, serviceUuids = listOf(GattProfile.Jbd.serviceUuid))
        val candidate = assertNotNull(driver.identify(adv))
        assertEquals("jbd.gatt", candidate.driverId)
        assertEquals(DeviceRole.BATTERY, candidate.role)
        assertTrue(candidate.addressHint)
    }

    @Test
    fun theAddressPrefixIsOnlyAHintNeverTheIdentification() {
        val driver = JbdDriver()
        assertNull(driver.identify(Adv(PlatformDeviceId("A4:C1:37-sim"), "SIM", -60)))
        val other = Adv(PlatformDeviceId("zz-other"), "UL-1234", -60, serviceUuids = listOf(GattProfile.Jbd.serviceUuid))
        assertFalse(assertNotNull(driver.identify(other)).addressHint)
    }

    @Test
    fun anAdvertisementWithoutTheServiceIsNotACandidate() {
        assertNull(JbdDriver().identify(Adv(PlatformDeviceId("x"), "SIM", -60, serviceUuids = listOf("0000ffe0-0000-1000-8000-00805f9b34fb"))))
    }

    @Test
    fun confirmIdentityReadsRegister05() = runTest {
        val transport = SimulatedTransport(clock = virtualClock())
        assertEquals("SIM-JBD-4S", JbdDriver(virtualClock()).confirmIdentity(platformId, transport))
        assertEquals(1, transport.acceptedFrames.size)
        assertTrue(transport.acceptedFrames.single().contentEquals(AllowedCommand.JbdHardwareVersion.frame()))
    }

    @Test
    fun confirmIdentityRefusesADeviceThatStaysSilent() = runTest {
        val link = scriptedLink()
        val transport = ScriptedTransport({ link })
        assertFailsWith<JbdIdentityException> { JbdDriver(virtualClock()).confirmIdentity(platformId, transport) }
        // 1 essai + 3 réessais, tous sur 0x05.
        assertEquals(4, link.requests.size)
        assertTrue(link.requests.all { it == AllowedCommand.JbdHardwareVersion })
    }

    @Test
    fun confirmIdentityRefusesAnotherProtocolAnswering() = runTest {
        val garbage = ByteArray(20) { 0x41 }
        val transport = ScriptedTransport({ scriptedLink(hardware = { listOf(garbage) }) })
        assertFailsWith<JbdIdentityException> { JbdDriver(virtualClock()).confirmIdentity(platformId, transport) }
    }

    @Test
    fun confirmIdentityRefusesARejectedStatus() = runTest {
        val body = byteArrayOf(0x80.toByte(), 0x00)
        val sum = (0x10000 - body.sumOf { it.toInt() and 0xFF }) and 0xFFFF
        val rejected = byteArrayOf(0xDD.toByte(), 0x05) + body + byteArrayOf((sum shr 8).toByte(), sum.toByte(), 0x77)
        val transport = ScriptedTransport({ scriptedLink(hardware = { listOf(rejected) }) })
        assertFailsWith<JbdIdentityException> { JbdDriver(virtualClock()).confirmIdentity(platformId, transport) }
    }

    @Test
    fun confirmIdentityRefusesAnUnreachableDevice() = runTest {
        val transport = ScriptedTransport(failConnect = true)
        assertFailsWith<JbdIdentityException> { JbdDriver(virtualClock()).confirmIdentity(platformId, transport) }
    }

    // ---- lecture et réassemblage ----

    @Test
    fun theSameResponseInOneTwoOrThreeNotificationsGivesTheSameReading() = runTest {
        val results = (1..3).map { parts ->
            val link = scriptedLink(
                basic = { chunk(basicFrame, (basicFrame.size + parts - 1) / parts).also { assertEquals(parts, it.size) } },
                cells = { chunk(cellsFrame, (cellsFrame.size + parts - 1) / parts) },
            )
            JbdDriver(virtualClock()).readings(device, ScriptedTransport({ link })).take(1).toList().single()
        }
        assertEquals(results[0], results[1])
        assertEquals(results[0], results[2])
        val reading = results[0]
        assertEquals(15.6, reading.value(Metric.BATTERY_VOLTAGE))
        assertEquals(-2.87, reading.value(Metric.BATTERY_CURRENT))
        assertEquals(100.0, reading.value(Metric.SOC))
        assertEquals(42.0, reading.value(Metric.CYCLES))
        assertEquals(3.430, reading.value(Metric.CELL_VOLTAGE, 0))
        assertEquals(3.417, reading.value(Metric.CELL_VOLTAGE, 3))
        assertEquals(22.4, reading.value(Metric.TEMPERATURE, 0))
        assertEquals(deviceId, reading.deviceId)
    }

    @Test
    fun aWrongChecksumGivesNoReadingAndIsCounted() = runTest {
        val bad = basicFrame.copyOf().also { it[it.size - 2] = (it[it.size - 2].toInt() xor 0x01).toByte() }
        val driver = JbdDriver(virtualClock())
        val got = mutableListOf<Reading>()
        driver.readings(device, ScriptedTransport({ scriptedLink(basic = { listOf(bad) }) })).onEach { got += it }.launchIn(backgroundScope)
        advanceTimeBy(60.seconds)
        assertTrue(got.isEmpty())
        val counters = assertNotNull(driver.counters.value[deviceId])
        assertTrue(counters.rejectedFrames >= 1)
        assertEquals(0, counters.readings)
    }

    @Test
    fun aRejectedStatusGivesNoReadingAndIsCounted() = runTest {
        val body = byteArrayOf(0x80.toByte(), 0x00)
        val sum = (0x10000 - body.sumOf { it.toInt() and 0xFF }) and 0xFFFF
        val rejected = byteArrayOf(0xDD.toByte(), 0x03) + body + byteArrayOf((sum shr 8).toByte(), sum.toByte(), 0x77)
        val driver = JbdDriver(virtualClock())
        val got = mutableListOf<Reading>()
        driver.readings(device, ScriptedTransport({ scriptedLink(basic = { listOf(rejected) }) })).onEach { got += it }.launchIn(backgroundScope)
        advanceTimeBy(60.seconds)
        assertTrue(got.isEmpty())
        assertTrue(assertNotNull(driver.counters.value[deviceId]).rejectedFrames >= 1)
    }

    @Test
    fun anAllZeroBasicInfoFrameGivesNoReadingAndIsCounted() = runTest {
        val body = byteArrayOf(0x00, 23) + ByteArray(23)
        val sum = (0x10000 - body.sumOf { it.toInt() and 0xFF }) and 0xFFFF
        val zeros = byteArrayOf(0xDD.toByte(), 0x03) + body + byteArrayOf((sum shr 8).toByte(), sum.toByte(), 0x77)
        val driver = JbdDriver(virtualClock())
        val got = mutableListOf<Reading>()
        driver.readings(device, ScriptedTransport({ scriptedLink(basic = { listOf(zeros) }) })).onEach { got += it }.launchIn(backgroundScope)
        advanceTimeBy(60.seconds)
        assertTrue(got.isEmpty())
        assertTrue(assertNotNull(driver.counters.value[deviceId]).rejectedFrames >= 1)
    }

    @Test
    fun aLateFrameForAnotherRegisterIsIgnored() = runTest {
        val link = scriptedLink(basic = { chunk(cellsFrame, 20) + chunk(basicFrame, 20) })
        val reading = JbdDriver(virtualClock()).readings(device, ScriptedTransport({ link })).take(1).toList().single()
        assertEquals(15.6, reading.value(Metric.BATTERY_VOLTAGE))
    }

    @Test
    fun withoutCellsAnswerTheReadingKeepsThePackValuesAndOmitsTheCells() = runTest {
        val link = scriptedLink(cells = { emptyList() })
        val reading = JbdDriver(virtualClock()).readings(device, ScriptedTransport({ link })).take(1).toList().single()
        assertEquals(15.6, reading.value(Metric.BATTERY_VOLTAGE))
        assertTrue(reading.samples.none { it.metric == Metric.CELL_VOLTAGE })
    }

    @Test
    fun theDriverPollsEveryFiveSecondsByDefault() = runTest {
        val clock = virtualClock()
        val got = mutableListOf<Reading>()
        val link = scriptedLink()
        JbdDriver(clock).readings(device, ScriptedTransport({ link })).onEach { got += it }.launchIn(backgroundScope)
        advanceTimeBy(21.seconds)
        runCurrent()
        assertEquals(listOf(0L, 5L, 10L, 15L, 20L), got.map { (it.at - start).inWholeSeconds })
    }

    @Test
    fun onlyTheThreeAllowedCommandsAreEverRequested() = runTest {
        val link = scriptedLink()
        JbdDriver(virtualClock()).readings(device, ScriptedTransport({ link })).take(3).toList()
        assertTrue(link.requests.isNotEmpty())
        assertTrue(link.requests.all { it in ReadAllowlist.commands })
        assertEquals(setOf(AllowedCommand.JbdBasicInfo, AllowedCommand.JbdCellVoltages), link.requests.toSet())
    }

    // ---- lecture seule ----

    @Test
    fun theSimulatorOnlyEverSeesAllowlistedFrames() = runTest {
        val transport = SimulatedTransport(clock = virtualClock())
        JbdDriver(virtualClock()).readings(device, transport).take(3).toList()
        assertTrue(transport.acceptedFrames.isNotEmpty())
        assertTrue(transport.acceptedFrames.all { ReadAllowlist.isAllowed(it) })
    }

    @Test
    fun anyDd5aFrameIsRefusedByTheAllowlist() {
        // DD 5A = écriture de registre JBD : jamais émise. Sources : jbdtool, esphome-jbd-bms.
        val write = hex("DD5A00020000FFFE77")
        assertFailsWith<ForbiddenCommandException> { ReadAllowlist.requireAllowed(write) }
        assertFailsWith<ForbiddenCommandException> { ReadAllowlist.requireAllowed(hex("DD5A0102001EFFE077")) }
        assertFailsWith<ForbiddenCommandException> { ReadAllowlist.requireAllowed(hex("DD5AE20002000AFE1477")) }
    }

    // ---- première écriture ----

    @Test
    fun theFirstWriteIsRetriedAndSucceeds() = runTest {
        for (failures in 1..3) {
            val driver = JbdDriver(virtualClock())
            val transport = SimulatedTransport(jbdScenario = JbdScenario.FirstWriteFails(failures), clock = virtualClock())
            val reading = driver.readings(device, transport).take(1).toList().single()
            assertEquals(4, reading.samples.count { it.metric == Metric.CELL_VOLTAGE })
            assertEquals(failures, driver.counters.value[deviceId]?.writeRetries)
            assertEquals(0, driver.counters.value[deviceId]?.reconnections)
        }
    }

    @Test
    fun theFirstWriteRetriesAreSpacedFiveHundredMilliseconds() = runTest {
        val before = currentTime
        JbdDriver(virtualClock()).readings(device, SimulatedTransport(jbdScenario = JbdScenario.FirstWriteFails(3), clock = virtualClock())).take(1).toList()
        assertEquals(1500L, currentTime - before)
    }

    @Test
    fun afterFourFailedWritesTheDriverReconnectsInsteadOfLooping() = runTest {
        val driver = JbdDriver(virtualClock())
        val transport = SimulatedTransport(jbdScenario = JbdScenario.FirstWriteFails(4), clock = virtualClock())
        val got = mutableListOf<Reading>()
        driver.readings(device, transport).onEach { got += it }.launchIn(backgroundScope)
        advanceTimeBy(20.seconds)
        assertTrue(got.isEmpty())
        assertTrue(assertNotNull(driver.counters.value[deviceId]).reconnections >= 2)
    }

    @Test
    fun onlyTheFirstWriteOfAConnectionIsRetried() = runTest {
        var failNext = false
        val link = scriptedLink()
        val flaky = object : ReadOnlyGattLink by link {
            override suspend fun request(cmd: AllowedCommand) {
                if (failNext) {
                    failNext = false
                    throw IllegalStateException("écriture en échec")
                }
                link.request(cmd)
            }
        }
        val driver = JbdDriver(virtualClock())
        val got = mutableListOf<Reading>()
        driver.readings(device, ScriptedTransport({ flaky })).onEach { got += it }.launchIn(backgroundScope)
        advanceTimeBy(1.seconds)
        assertEquals(1, got.size)
        failNext = true
        advanceTimeBy(10.seconds)
        assertEquals(1, driver.counters.value[deviceId]?.reconnections)
        assertEquals(0, driver.counters.value[deviceId]?.writeRetries)
    }

    // ---- reconnexion ----

    @Test
    fun aThreeMinuteDisconnectionReconnectsAndInventsNothing() = runTest {
        val clock = virtualClock()
        val driver = JbdDriver(clock)
        val transport = SimulatedTransport(jbdScenario = JbdScenario.BmsDisconnects(after = 30.seconds, outage = 3.minutes), clock = clock)
        val got = mutableListOf<Reading>()
        driver.readings(device, transport).onEach { got += it }.launchIn(backgroundScope)
        advanceTimeBy(8.minutes)
        runCurrent()
        val outageStart = start + 30.seconds
        val outageEnd = outageStart + 3.minutes
        assertTrue(got.any { it.at < outageStart }, "des mesures avant la coupure")
        assertTrue(got.none { it.at >= outageStart && it.at < outageEnd }, "aucune mesure pendant la coupure")
        val afterOutage = got.filter { it.at >= outageEnd }
        assertTrue(afterOutage.size >= 3, "les mesures reprennent")
        assertTrue(afterOutage.first().at - outageEnd <= 61.seconds)
        assertTrue(assertNotNull(driver.counters.value[deviceId]).reconnections >= 1)
        // Le trou est visible : jamais deux mesures voisines de moins de 5 s, et un grand écart autour de la coupure.
        val gaps = got.zipWithNext { a, b -> b.at - a.at }
        assertTrue(gaps.all { it >= 5.seconds })
        assertTrue(gaps.max() >= 3.minutes)
    }

    @Test
    fun theReconnectionBackoffDoublesFromTwoToSixtySeconds() = runTest {
        val transport = ScriptedTransport(failConnect = true).also { it.clockMillis = { currentTime } }
        JbdDriver(virtualClock()).readings(device, transport).launchIn(backgroundScope)
        advanceTimeBy(400.seconds)
        val deltas = transport.connectAttempts.zipWithNext { a, b -> (b - a) / 1000 }
        assertEquals(listOf(2L, 4L, 8L, 16L, 32L, 60L, 60L, 60L), deltas.take(8))
    }

    @Test
    fun theBackoffRestartsAtTwoSecondsAfterASuccessfulSession() = runTest {
        val clock = virtualClock()
        val simulated = SimulatedTransport(jbdScenario = JbdScenario.BmsDisconnects(after = 30.seconds, outage = 3.minutes), clock = clock)
        val attempts = mutableListOf<Long>()
        val recording = object : BleTransport by simulated {
            override suspend fun connect(id: PlatformDeviceId, profile: GattProfile): ReadOnlyGattLink {
                attempts += currentTime
                return simulated.connect(id, profile)
            }
        }
        JbdDriver(clock).readings(device, recording).launchIn(backgroundScope)
        advanceTimeBy(5.minutes)
        val deltas = attempts.zipWithNext { a, b -> (b - a) / 1000 }
        // 30 s de session + 2 s de reprise, puis le délai double à chaque refus jusqu'à 60 s.
        assertEquals(listOf(32L, 4L, 8L, 16L, 32L, 60L), deltas.take(6))
    }

    @Test
    fun cancellingTheCollectorStopsPolling() = runTest {
        val link = scriptedLink()
        val job = JbdDriver(virtualClock()).readings(device, ScriptedTransport({ link })).launchIn(backgroundScope)
        advanceTimeBy(12.seconds)
        job.cancel()
        runCurrent()
        val count = link.requests.size
        advanceTimeBy(30.seconds)
        assertEquals(count, link.requests.size)
    }

    // ---- scénarios ----

    @Test
    fun aCorruptedFrameCostsOneReadingNotTheSession() = runTest {
        val clock = virtualClock()
        val driver = JbdDriver(clock)
        val transport = SimulatedTransport(jbdScenario = JbdScenario.CorruptedFrame(every = 3), clock = clock)
        val got = mutableListOf<Reading>()
        driver.readings(device, transport).onEach { got += it }.launchIn(backgroundScope)
        advanceTimeBy(60.seconds)
        runCurrent()
        val counters = assertNotNull(driver.counters.value[deviceId])
        assertTrue(counters.rejectedFrames >= 3)
        assertEquals(0, counters.reconnections)
        assertTrue(got.size >= 6)
        assertTrue(got.all { it.value(Metric.BATTERY_VOLTAGE)!! in 12.0..15.0 })
        assertEquals(counters.readings, got.size)
    }

    @Test
    fun theProtectionAlarmShowsUpInTheFlagsAndTheChargeFet() = runTest {
        val clock = virtualClock()
        val transport = SimulatedTransport(jbdScenario = JbdScenario.BmsProtectionAlarm(after = 10.seconds), clock = clock)
        val got = mutableListOf<Reading>()
        JbdDriver(clock).readings(device, transport).onEach { got += it }.launchIn(backgroundScope)
        advanceTimeBy(31.seconds)
        runCurrent()
        val before = got.filter { it.at < start + 10.seconds }
        val after = got.filter { it.at >= start + 10.seconds }
        assertTrue(before.isNotEmpty() && after.isNotEmpty())
        assertTrue(before.all { it.value(Metric.PROTECTION_FLAGS) == 0.0 && it.value(Metric.FET_STATE) == 3.0 })
        assertTrue(after.all { it.value(Metric.PROTECTION_FLAGS) == 1.0 })
        assertTrue(after.all { it.value(Metric.FET_STATE) == 2.0 })
        assertTrue(after.all { it.value(Metric.CELL_VOLTAGE, 1)!! >= 3.65 })
    }

    @Test
    fun aNightDischargeReportsANegativeCurrent() = runTest {
        val clock = virtualClock()
        val transport = SimulatedTransport(jbdScenario = JbdScenario.NightDischarge, clock = clock)
        val got = JbdDriver(clock).readings(device, transport).take(3).toList()
        assertTrue(got.all { it.value(Metric.BATTERY_CURRENT)!! < 0.0 })
    }

    @Test
    fun aNominalDaySimulatedBatteryShowsEverythingTheScreenNeeds() = runTest {
        val clock = virtualClock()
        val reading = JbdDriver(clock).readings(device, SimulatedTransport(clock = clock)).take(1).toList().single()
        assertEquals(4, reading.samples.count { it.metric == Metric.CELL_VOLTAGE })
        assertEquals(2, reading.samples.count { it.metric == Metric.TEMPERATURE })
        for (metric in listOf(Metric.BATTERY_VOLTAGE, Metric.BATTERY_CURRENT, Metric.SOC, Metric.CYCLES, Metric.PROTECTION_FLAGS)) {
            assertNotNull(reading.value(metric), metric.name)
        }
    }
}
