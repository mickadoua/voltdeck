package app.voltdeck

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DiagnosticTest {
    @Test
    fun agesAreReadable() {
        assertEquals("à l'instant", ageText(300.milliseconds))
        assertEquals("il y a 12 s", ageText(12.seconds))
        assertEquals("il y a 3 min", ageText(3.minutes + 20.seconds))
        assertEquals("il y a 2 h", ageText(2.hours + 5.minutes))
    }
}
