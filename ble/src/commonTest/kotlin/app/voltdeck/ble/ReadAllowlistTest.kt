package app.voltdeck.ble

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun hex(vararg bytes: Int): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }

class ReadAllowlistTest {
    @Test
    fun allowlistContainsExactlyTheThreeDocumentedJbdReads() {
        val frames = ReadAllowlist.commands.map { it.frame() }
        assertEquals(3, frames.size)
        assertContentEquals(hex(0xDD, 0xA5, 0x03, 0x00, 0xFF, 0xFD, 0x77), AllowedCommand.JbdBasicInfo.frame())
        assertContentEquals(hex(0xDD, 0xA5, 0x04, 0x00, 0xFF, 0xFC, 0x77), AllowedCommand.JbdCellVoltages.frame())
        assertContentEquals(hex(0xDD, 0xA5, 0x05, 0x00, 0xFF, 0xFB, 0x77), AllowedCommand.JbdHardwareVersion.frame())
    }

    @Test
    fun everyAllowedFrameIsAReadRequestWithAValidChecksum() {
        for (cmd in ReadAllowlist.commands) {
            val f = cmd.frame()
            assertEquals(0xDD, f[0].toInt() and 0xFF, "${cmd.label} : début de trame")
            assertEquals(0xA5, f[1].toInt() and 0xFF, "${cmd.label} : A5 = lecture")
            assertEquals(0x77, f[f.size - 1].toInt() and 0xFF, "${cmd.label} : fin de trame")
            assertEquals(0, f[3].toInt(), "${cmd.label} : aucune donnée variable")
            val checksum = ((f[4].toInt() and 0xFF) shl 8) or (f[5].toInt() and 0xFF)
            val expected = (0x10000 - ((f[2].toInt() and 0xFF) + (f[3].toInt() and 0xFF))) and 0xFFFF
            assertEquals(expected, checksum, "${cmd.label} : somme de contrôle")
        }
    }

    @Test
    fun frameReturnsACopyThatCannotAlterTheAllowedBytes() {
        val copy = AllowedCommand.JbdBasicInfo.frame()
        copy[2] = 0x7F
        assertContentEquals(hex(0xDD, 0xA5, 0x03, 0x00, 0xFF, 0xFD, 0x77), AllowedCommand.JbdBasicInfo.frame())
        assertTrue(ReadAllowlist.isAllowed(hex(0xDD, 0xA5, 0x03, 0x00, 0xFF, 0xFD, 0x77)))
    }

    @Test
    fun forbiddenFramesAreRejected() {
        val mosfetControl = hex(0xDD, 0x5A, 0xE1, 0x02, 0x00, 0x03, 0xFF, 0x1A, 0x77)
        val factoryMode = hex(0xDD, 0x5A, 0x00, 0x02, 0x56, 0x78, 0xFF, 0x30, 0x77)
        val auth = hex(0xFF, 0xAA, 0x01, 0x00)
        for (frame in listOf(mosfetControl, factoryMode, auth, hex(), hex(0xDD, 0xA5, 0x03, 0x00, 0xFF, 0xFD))) {
            assertFalse(ReadAllowlist.isAllowed(frame))
            assertFailsWith<ForbiddenCommandException> { ReadAllowlist.requireAllowed(frame) }
        }
    }

    @Test
    fun readOfAnotherRegisterIsRejectedEvenWithTheReadOpcode() {
        assertFailsWith<ForbiddenCommandException> {
            ReadAllowlist.requireAllowed(hex(0xDD, 0xA5, 0x10, 0x00, 0xFF, 0xF0, 0x77))
        }
    }
}
