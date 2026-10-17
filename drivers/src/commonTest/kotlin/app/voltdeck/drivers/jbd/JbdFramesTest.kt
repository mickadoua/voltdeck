package app.voltdeck.drivers.jbd

import app.voltdeck.core.Metric
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TEST ONLY : vecteurs publics. Les trames de référence (registres 0x03, 0x04 et 0x05) viennent de
 * patman15/aiobmsble (Apache-2.0), tests/bms/test_jbd_bms.py, `MockJBDBleakClient.RESP`. Les autres trames sont
 * construites ici avec une somme de contrôle recalculée indépendamment du code testé.
 */
class JbdFramesTest {
    private val basicFrame = hex(
        "DD03001D0618FEE101F201F4002A2C7C000000000000806403040" +
            "30B8B0B8A0B84F88477",
    )
    private val cellsFrame = hex("DD0400080D660D610D680D59FE3C77")
    private val hardwareFrame = hex("DD05000A30313233343536373839FDE977")

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** Trame de réponse construite à la main : somme = 0x10000 − Σ(statut, longueur, données). */
    private fun frame(register: Int, status: Int, data: ByteArray): ByteArray {
        val body = byteArrayOf(status.toByte(), data.size.toByte()) + data
        val sum = (0x10000 - body.sumOf { it.toInt() and 0xFF }) and 0xFFFF
        return byteArrayOf(0xDD.toByte(), register.toByte()) + body +
            byteArrayOf((sum shr 8).toByte(), sum.toByte(), 0x77)
    }

    private fun chunks(frame: ByteArray, size: Int) = frame.toList().chunked(size).map { it.toByteArray() }

    private fun feedAll(assembler: JbdFrameAssembler, parts: List<ByteArray>) = parts.flatMap { assembler.feed(it) }

    @Test
    fun referenceBasicInfoFrameDecodesEveryValue() {
        val response = JbdFrameAssembler().feed(basicFrame).single()
        assertEquals(0x03, response.register)
        assertTrue(response.isOk)
        val info = JbdBasicInfo.decode(response.data)
        assertEquals(15.6, info.voltageV)
        assertEquals(-2.87, info.currentA)
        assertEquals(4.98, info.remainingAh)
        assertEquals(5.0, info.nominalAh)
        assertEquals(42, info.cycles)
        assertEquals(0L, info.balanceMask)
        assertEquals(0, info.protectionMask)
        assertEquals(100, info.socPercent)
        assertEquals(0x03, info.fetMask)
        assertEquals(4, info.cellCount)
        assertEquals(listOf(22.4, 22.3, 21.7), info.temperaturesC)
    }

    @Test
    fun bmsNeverEmitsTheChargerDeviceState() {
        val data = JbdFrameAssembler().feed(basicFrame).single().data
        assertTrue(JbdBasicInfo.decode(data).toSamples().none { it.metric == Metric.DEVICE_STATE })
    }

    @Test
    fun referenceBasicInfoFrameMapsToTheCommonMetrics() {
        val data = JbdFrameAssembler().feed(basicFrame).single().data
        val samples = JbdBasicInfo.decode(data).toSamples()
        fun value(metric: Metric, channel: Int = 0) = samples.single { it.metric == metric && it.channel == channel }.value
        assertEquals(15.6, value(Metric.BATTERY_VOLTAGE))
        assertEquals(-2.87, value(Metric.BATTERY_CURRENT))
        assertEquals(100.0, value(Metric.SOC))
        assertEquals(4.98, value(Metric.REMAINING_CAPACITY))
        assertEquals(5.0, value(Metric.NOMINAL_CAPACITY))
        assertEquals(42.0, value(Metric.CYCLES))
        assertEquals(0.0, value(Metric.PROTECTION_FLAGS))
        assertEquals(3.0, value(Metric.FET_STATE))
        assertEquals(22.4, value(Metric.TEMPERATURE, 0))
        assertEquals(22.3, value(Metric.TEMPERATURE, 1))
        assertEquals(21.7, value(Metric.TEMPERATURE, 2))
    }

    @Test
    fun referenceCellFrameDecodesFourCells() {
        val response = JbdFrameAssembler().feed(cellsFrame).single()
        assertEquals(0x04, response.register)
        assertEquals(listOf(3.430, 3.425, 3.432, 3.417), JbdCellVoltages.decode(response.data, expectedCells = 4))
    }

    @Test
    fun referenceHardwareFrameDecodesTheAsciiVersion() {
        val response = JbdFrameAssembler().feed(hardwareFrame).single()
        assertEquals("0123456789", JbdHardwareVersion.decode(response.data))
    }

    @Test
    fun theSameResponseInOneTwoOrThreeNotificationsGivesTheSameResult() {
        val expected = JbdFrameAssembler().feed(basicFrame).single()
        for (parts in 1..3) {
            val size = (basicFrame.size + parts - 1) / parts
            val pieces = chunks(basicFrame, size)
            assertEquals(parts, pieces.size)
            val got = feedAll(JbdFrameAssembler(), pieces).single()
            assertEquals(expected.register, got.register)
            assertContentEquals(expected.data, got.data)
        }
    }

    @Test
    fun theTwentyBytePacketsOfARealBleLinkReassemble() {
        val assembler = JbdFrameAssembler()
        val got = feedAll(assembler, chunks(basicFrame, 20)).single()
        assertContentEquals(JbdFrameAssembler().feed(basicFrame).single().data, got.data)
        assertEquals(0, assembler.rejectedFrames)
    }

    @Test
    fun aPartialFrameProducesNothingUntilTheEndArrives() {
        val assembler = JbdFrameAssembler()
        assertTrue(assembler.feed(basicFrame.copyOfRange(0, 10)).isEmpty())
        assertTrue(assembler.feed(basicFrame.copyOfRange(10, basicFrame.size - 1)).isEmpty())
        assertEquals(1, assembler.feed(basicFrame.copyOfRange(basicFrame.size - 1, basicFrame.size)).size)
    }

    @Test
    fun aWrongChecksumIsRejectedAndCounted() {
        val bad = basicFrame.copyOf().also { it[it.size - 2] = (it[it.size - 2].toInt() xor 0x01).toByte() }
        val assembler = JbdFrameAssembler()
        assertTrue(assembler.feed(bad).isEmpty())
        assertEquals(1, assembler.rejectedFrames)
    }

    @Test
    fun aCorruptedDataByteIsRejectedByTheChecksum() {
        val bad = basicFrame.copyOf().also { it[6] = (it[6].toInt() xor 0x40).toByte() }
        val assembler = JbdFrameAssembler()
        assertTrue(assembler.feed(bad).isEmpty())
        assertEquals(1, assembler.rejectedFrames)
    }

    @Test
    fun aWrongEndMarkerIsRejected() {
        val bad = basicFrame.copyOf().also { it[it.size - 1] = 0x76 }
        val assembler = JbdFrameAssembler()
        assertTrue(assembler.feed(bad).isEmpty())
        assertEquals(1, assembler.rejectedFrames)
    }

    @Test
    fun theFrameAfterACorruptedOneIsStillRead() {
        val bad = basicFrame.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 0x10).toByte() }
        val assembler = JbdFrameAssembler()
        val got = assembler.feed(bad + cellsFrame)
        assertEquals(listOf(0x04), got.map { it.register })
        assertEquals(1, assembler.rejectedFrames)
    }

    @Test
    fun garbageBeforeAFrameIsSkippedAndCounted() {
        val assembler = JbdFrameAssembler()
        val got = assembler.feed(byteArrayOf(0x01, 0x02, 0x03) + cellsFrame)
        assertEquals(1, got.size)
        assertEquals(1, assembler.rejectedFrames)
    }

    @Test
    fun aNonZeroStatusIsReportedNotOk() {
        val response = JbdFrameAssembler().feed(frame(0x03, 0x80, ByteArray(0))).single()
        assertFalse(response.isOk)
        assertEquals(0x80, response.status)
    }

    @Test
    fun resetForgetsAPartialFrame() {
        val assembler = JbdFrameAssembler()
        assembler.feed(basicFrame.copyOfRange(0, 12))
        assembler.reset()
        assertEquals(1, assembler.feed(cellsFrame).size)
    }

    @Test
    fun checksumOfRequestsMatchesTheAllowlistFrames() {
        // La requête lue (DD A5 reg 00 chk chk 77) a la même somme 0x10000 − (reg + len) que les réponses.
        for ((reg, expected) in listOf(0x03 to 0xFFFD, 0x04 to 0xFFFC, 0x05 to 0xFFFB)) {
            assertEquals(expected, JbdProtocol.checksum(byteArrayOf(reg.toByte(), 0), 0, 2))
        }
    }

    @Test
    fun basicInfoTooShortOrTruncatedIsRefused() {
        val data = JbdFrameAssembler().feed(basicFrame).single().data
        assertFailsWith<JbdDecodeException> { JbdBasicInfo.decode(data.copyOf(10)) }
        assertFailsWith<JbdDecodeException> { JbdBasicInfo.decode(data.copyOf(data.size - 2)) }
    }

    @Test
    fun implausibleValuesAreOmittedNeverZeroed() {
        val data = JbdFrameAssembler().feed(basicFrame).single().data.copyOf()
        data[19] = 101.toByte()
        data[23] = 0
        data[24] = 0
        val samples = JbdBasicInfo.decode(data).toSamples()
        assertNull(samples.firstOrNull { it.metric == Metric.SOC })
        assertEquals(listOf(1, 2), samples.filter { it.metric == Metric.TEMPERATURE }.map { it.channel })
    }

    @Test
    fun cellAtZeroMillivoltKeepsTheOtherCellNumbers() {
        val data = byteArrayOf(0x0D, 0x66, 0x00, 0x00, 0x0D, 0x68, 0x0D, 0x59)
        val cells = JbdCellVoltages.decode(data, 4)
        assertEquals(listOf(3.430, null, 3.432, 3.417), cells)
        assertEquals(listOf(0, 2, 3), JbdCellVoltages.toSamples(cells).map { it.channel })
    }

    @Test
    fun cellCountMismatchIsRefused() {
        assertFailsWith<JbdDecodeException> { JbdCellVoltages.decode(ByteArray(6), expectedCells = 4) }
        assertFailsWith<JbdDecodeException> { JbdCellVoltages.decode(ByteArray(0), expectedCells = 0) }
    }

    @Test
    fun hardwareVersionMustBePrintableAscii() {
        assertFailsWith<JbdDecodeException> { JbdHardwareVersion.decode(byteArrayOf(0x01, 0x02)) }
        assertFailsWith<JbdDecodeException> { JbdHardwareVersion.decode(ByteArray(0)) }
        assertFailsWith<JbdDecodeException> { JbdHardwareVersion.decode(byteArrayOf(0x20, 0x20)) }
    }

    @Test
    fun protectionBitsAreNamed() {
        val data = JbdFrameAssembler().feed(basicFrame).single().data.copyOf()
        data[16] = 0x04
        data[17] = 0x05
        val info = JbdBasicInfo.decode(data)
        assertEquals(0x0405, info.protectionMask)
        assertEquals(
            listOf(JbdProtectionFlag.CELL_OVERVOLTAGE, JbdProtectionFlag.PACK_OVERVOLTAGE, JbdProtectionFlag.SHORT_CIRCUIT),
            JbdProtectionFlag.active(info.protectionMask),
        )
    }

    @Test
    fun balanceMaskReadsTheLowWordFirst() {
        val data = JbdFrameAssembler().feed(basicFrame).single().data.copyOf()
        data[12] = 0x00; data[13] = 0x05; data[14] = 0x00; data[15] = 0x01
        assertEquals((1L shl 16) or 5L, JbdBasicInfo.decode(data).balanceMask)
    }

    @Test
    fun currentIsSignedAndPositiveWhenCharging() {
        val data = JbdFrameAssembler().feed(basicFrame).single().data.copyOf()
        data[2] = 0x01; data[3] = 0x2C
        assertEquals(3.0, JbdBasicInfo.decode(data).currentA)
    }

    @Test
    fun strayStartByteBeforeAValidFrameDoesNotLoseIt() {
        val assembler = JbdFrameAssembler()
        val responses = assembler.feed(byteArrayOf(0xDD.toByte(), 0x00) + cellsFrame)
        assertEquals(1, responses.size)
        assertEquals(0x04, responses.single().register)
        assertTrue(responses.single().isOk)
        assertTrue(assembler.rejectedFrames > 0)
    }

    @Test
    fun strayStartByteWithALongBogusLengthResyncsOnceEnoughBytesArrive() {
        val assembler = JbdFrameAssembler()
        val noise = byteArrayOf(0xDD.toByte(), 0x00, 0x00, 0x08)
        val responses = feedAll(assembler, chunks(noise + cellsFrame, 5))
        assertEquals(listOf(0x04), responses.map { it.register })
    }

    @Test
    fun allZeroBasicInfoFrameIsRejectedInsteadOfDecodedAsADeadBattery() {
        val zeros = frame(0x03, 0, ByteArray(23))
        val response = JbdFrameAssembler().feed(zeros).single()
        assertFailsWith<JbdDecodeException> { JbdBasicInfo.decode(response.data) }
    }

    @Test
    fun basicInfoWithZeroVoltageOrZeroCellsIsRejected() {
        val base = JbdFrameAssembler().feed(basicFrame).single().data
        val noVoltage = base.copyOf().also { it[0] = 0; it[1] = 0 }
        val noCells = base.copyOf().also { it[21] = 0 }
        assertFailsWith<JbdDecodeException> { JbdBasicInfo.decode(noVoltage) }
        assertFailsWith<JbdDecodeException> { JbdBasicInfo.decode(noCells) }
    }
}
