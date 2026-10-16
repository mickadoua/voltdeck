package app.voltdeck

import app.voltdeck.simulator.SimulatedPlant
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
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
    fun demoTimeRunsSixtyTimesFasterThanRealTime() = runTest {
        val real = object : Clock {
            override fun now() = Instant.parse("2026-10-16T14:12:00Z") + testScheduler.currentTime.milliseconds
        }
        val got = demoReadings(real, TimeZone.UTC).take(4).toList()
        assertEquals(listOf(SimulatedPlant.BATTERY_ID, SimulatedPlant.MPPT_ID), got.take(2).map { it.deviceId })
        assertEquals(1.seconds * DEMO_SPEED.toInt(), got[2].at - got[0].at)
        assertTrue(got[0].at == Instant.parse("2026-10-16T06:30:00Z"))
    }
}
