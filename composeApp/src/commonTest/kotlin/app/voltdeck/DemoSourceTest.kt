package app.voltdeck

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DemoSourceTest {
    @Test
    fun demoStartsAt0630LocalOnTheCurrentDay() {
        val now = Instant.parse("2026-10-16T14:12:00Z")
        assertEquals(Instant.parse("2026-10-16T06:30:00Z"), demoStart(now, TimeZone.UTC))
        assertEquals(Instant.parse("2026-10-16T04:30:00Z"), demoStart(now, TimeZone.of("Europe/Paris")))
    }

    @Test
    fun demoTimeRunsSixtyTimesFasterThanRealTime() {
        var realNow = Instant.parse("2026-10-16T14:12:00Z")
        val real = object : Clock {
            override fun now() = realNow
        }
        val clock = demoClock(real, TimeZone.UTC)
        assertEquals(Instant.parse("2026-10-16T06:30:00Z"), clock.now())
        realNow += 1.seconds
        assertEquals(Instant.parse("2026-10-16T06:31:00Z"), clock.now())
        assertEquals(60.0, DEMO_SPEED)
    }
}
