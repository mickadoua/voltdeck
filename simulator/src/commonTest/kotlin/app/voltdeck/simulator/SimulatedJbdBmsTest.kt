package app.voltdeck.simulator

import app.voltdeck.ble.AllowedCommand
import app.voltdeck.ble.GattProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SimulatedJbdBmsTest {
    private val start = Instant.parse("2026-10-17T12:00:00Z")

    private class ManualClock(var current: Instant) : Clock {
        override fun now(): Instant = current
        operator fun plusAssign(d: Duration) { current += d }
    }

    private fun bms(scenario: JbdScenario, clock: Clock) =
        SimulatedJbdBms(SimulatedPlant(1L, scenario.plantStart(clock.now())), scenario, clock)

    private fun List<ByteArray>.joined() = fold(ByteArray(0)) { acc, part -> acc + part }

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun assertWellFormed(frame: ByteArray, register: Int) {
        assertEquals(0xDD, frame[0].toInt() and 0xFF)
        assertEquals(register, frame[1].toInt() and 0xFF)
        assertEquals(0, frame[2].toInt())
        val length = frame[3].toInt() and 0xFF
        assertEquals(length + 7, frame.size)
        assertEquals(0x77, frame.last().toInt() and 0xFF)
        val sum = (2 until 4 + length).sumOf { frame[it].toInt() and 0xFF }
        assertEquals(0x10000, (u16(frame, 4 + length) + sum))
    }

    @Test
    fun everyNotificationFitsInTwentyBytesAndTheFramesAreWellFormed() {
        val model = bms(JbdScenario.Nominal, ManualClock(start))
        for ((cmd, register) in listOf(AllowedCommand.JbdBasicInfo to 0x03, AllowedCommand.JbdCellVoltages to 0x04, AllowedCommand.JbdHardwareVersion to 0x05)) {
            val parts = model.respond(cmd)
            assertTrue(parts.all { it.size in 1..20 })
            assertWellFormed(parts.joined(), register)
        }
        assertEquals(2, model.respond(AllowedCommand.JbdBasicInfo).size)
    }

    @Test
    fun basicInfoCarriesTheBatteryOfThePlant() {
        val clock = ManualClock(start)
        val plant = SimulatedPlant(1L, start)
        val battery = plant.readingsAt(start).first { it.deviceId == SimulatedPlant.BATTERY_ID }
        val data = bms(JbdScenario.Nominal, clock).respond(AllowedCommand.JbdBasicInfo).joined()
        val soc = battery.samples.first { it.metric == app.voltdeck.core.Metric.SOC }.value
        assertEquals(soc.toInt(), data[4 + 19].toInt() and 0xFF)
        assertEquals(4, data[4 + 21].toInt())
        assertEquals(2, data[4 + 22].toInt())
        val cells = bms(JbdScenario.Nominal, clock).respond(AllowedCommand.JbdCellVoltages).joined()
        assertEquals(8, cells[3].toInt())
        val mv = (0 until 4).map { u16(cells, 4 + 2 * it) }
        assertTrue(mv.all { it in 2500..3700 })
    }

    @Test
    fun theCorruptedFrameScenarioBreaksTheChecksumOfEveryNthBasicInfoOnly() {
        val model = bms(JbdScenario.CorruptedFrame(every = 3), ManualClock(start))
        val results = (1..6).map { runCatching { assertWellFormed(model.respond(AllowedCommand.JbdBasicInfo).joined(), 0x03) }.isSuccess }
        assertEquals(listOf(true, true, false, true, true, false), results)
        assertWellFormed(model.respond(AllowedCommand.JbdCellVoltages).joined(), 0x04)
    }

    @Test
    fun nightDischargeStartsAtTenInTheEvening() {
        val data = bms(JbdScenario.NightDischarge, ManualClock(start)).respond(AllowedCommand.JbdBasicInfo).joined()
        val current = u16(data, 6).let { if (it >= 0x8000) it - 0x10000 else it }
        assertTrue(current < 0)
    }

    @Test
    fun theProtectionAlarmRaisesBitZeroAndOpensTheChargeFet() {
        val clock = ManualClock(start)
        val model = bms(JbdScenario.BmsProtectionAlarm(after = 10.seconds), clock)
        model.markConnectAttempt()
        val calm = model.respond(AllowedCommand.JbdBasicInfo).joined()
        assertEquals(0, u16(calm, 4 + 16))
        assertEquals(0x03, calm[4 + 20].toInt())
        clock += 11.seconds
        val alarm = model.respond(AllowedCommand.JbdBasicInfo).joined()
        assertEquals(1, u16(alarm, 4 + 16))
        assertEquals(0x02, alarm[4 + 20].toInt())
        assertEquals(3700, u16(model.respond(AllowedCommand.JbdCellVoltages).joined(), 4 + 2))
    }

    @Test
    fun theBmsDisappearsThenComesBack() {
        val clock = ManualClock(start)
        val model = bms(JbdScenario.BmsDisconnects(after = 30.seconds, outage = 3.minutes), clock)
        model.markConnectAttempt()
        assertTrue(!model.isOffline())
        clock += 30.seconds
        assertTrue(model.isOffline())
        clock += 3.minutes - 1.seconds
        assertTrue(model.isOffline())
        clock += 1.seconds
        assertTrue(!model.isOffline())
    }

    @Test
    fun theTransportRefusesConnectionsDuringTheOutageAndKillsTheOpenLink() = runTest {
        val clock = ManualClock(start)
        val transport = SimulatedTransport(jbdScenario = JbdScenario.BmsDisconnects(after = 30.seconds, outage = 3.minutes), clock = clock)
        val link = transport.connect(SimulatedTransport.BATTERY_ID, GattProfile.Jbd)
        link.request(AllowedCommand.JbdBasicInfo)
        assertTrue(link.notifications.first().isNotEmpty())
        clock += 31.seconds
        assertFailsWith<SimulatedLinkException> { link.request(AllowedCommand.JbdBasicInfo) }
        assertFailsWith<SimulatedLinkException> { transport.connect(SimulatedTransport.BATTERY_ID, GattProfile.Jbd) }
        clock += 3.minutes
        transport.connect(SimulatedTransport.BATTERY_ID, GattProfile.Jbd).request(AllowedCommand.JbdBasicInfo)
    }

    @Test
    fun firstWriteFailuresCountPerConnection() = runTest {
        val transport = SimulatedTransport(jbdScenario = JbdScenario.FirstWriteFails(2), clock = ManualClock(start))
        repeat(2) {
            val link = transport.connect(SimulatedTransport.BATTERY_ID, GattProfile.Jbd)
            assertFailsWith<SimulatedLinkException> { link.request(AllowedCommand.JbdBasicInfo) }
            assertFailsWith<SimulatedLinkException> { link.request(AllowedCommand.JbdBasicInfo) }
            link.request(AllowedCommand.JbdBasicInfo)
        }
        assertEquals(2, transport.acceptedFrames.size)
    }

    @Test
    fun scenarioIdsAreStable() {
        assertEquals(
            listOf("night_discharge", "bms_disconnects", "corrupted_frame", "bms_protection_alarm", "first_write_fails"),
            listOf(JbdScenario.NightDischarge, JbdScenario.BmsDisconnects(), JbdScenario.CorruptedFrame(), JbdScenario.BmsProtectionAlarm(), JbdScenario.FirstWriteFails()).map { it.id },
        )
    }

    @Test
    fun invalidScenarioParametersAreRefused() {
        assertFailsWith<IllegalArgumentException> { JbdScenario.CorruptedFrame(every = 1) }
        assertFailsWith<IllegalArgumentException> { JbdScenario.FirstWriteFails(failures = -1) }
    }
}
