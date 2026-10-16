package app.voltdeck.ble

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GuardedGattLinkTest {
    private class RecordingSink : FrameSink {
        val received = mutableListOf<ByteArray>()

        override suspend fun write(frame: ByteArray) {
            received += frame
        }
    }

    private fun link(sink: FrameSink, onClose: () -> Unit = {}) = GuardedGattLink(emptyFlow(), sink, onClose)

    @Test
    fun everyAllowedCommandIsSentByteForByte() = runTest {
        val sink = RecordingSink()
        val link = link(sink)
        ReadAllowlist.commands.forEach { link.request(it) }
        assertEquals(ReadAllowlist.commands.size, sink.received.size)
        ReadAllowlist.commands.zip(sink.received).forEach { (cmd, sent) -> assertContentEquals(cmd.frame(), sent) }
    }

    @Test
    fun forbiddenFramesThrowAndNothingReachesTheSink() = runTest {
        val sink = RecordingSink()
        val link = link(sink)
        val forbidden = listOf(
            hex(0xDD, 0x5A, 0xE1, 0x02, 0x00, 0x03, 0xFF, 0x1A, 0x77),
            hex(0xDD, 0x5A, 0x00, 0x02, 0x56, 0x78, 0xFF, 0x30, 0x77),
            hex(0xFF, 0xAA, 0x01, 0x00),
        )
        for (frame in forbidden) {
            assertFailsWith<ForbiddenCommandException> { link.requestFrame(frame) }
        }
        assertTrue(sink.received.isEmpty(), "aucune trame hors liste blanche ne doit atteindre le transport")
    }

    @Test
    fun closeIsForwarded() {
        var closed = false
        link(RecordingSink()) { closed = true }.close()
        assertTrue(closed)
    }
}
