package app.voltdeck.core

import kotlin.test.Test
import kotlin.test.assertFailsWith

class DeviceIdTest {
    @Test
    fun blankIdIsRejected() {
        assertFailsWith<IllegalArgumentException> { DeviceId("") }
        assertFailsWith<IllegalArgumentException> { DeviceId("  ") }
    }
}
