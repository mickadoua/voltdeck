package app.voltdeck

import kotlin.test.Test
import app.voltdeck.storage.MinuteCoverage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
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

    private val t0 = Instant.parse("2026-06-21T10:00:00Z")

    @Test
    fun ratePerMinuteCountsOnlyTheLastMinute() {
        var times = emptyList<Instant>()
        (0 until 120).forEach { times = ReadingRate.record(times, t0 + it.seconds) }
        assertEquals(60, ReadingRate.perMinute(times, t0 + 119.seconds))
        assertEquals(30, ReadingRate.perMinute(times, t0 + 149.seconds), "la cadence retombe quand les annonces cessent")
        assertEquals(0, ReadingRate.perMinute(times, t0 + 3.minutes))
        assertTrue(times.size <= 61, "la liste ne grossit pas sans limite : ${times.size}")
    }

    @Test
    fun coverageIsShownAsPercentAndMinutes() {
        assertEquals("97 % (1397 sur 1440 min)", coverageText(MinuteCoverage(1397, 1440)))
        assertEquals("94 % (94 sur 100 min)", coverageText(MinuteCoverage(94, 100)), "jamais arrondi vers le haut au-dessus de l'objectif")
        assertEquals("pas encore de mesure", coverageText(MinuteCoverage(0, 0)))
        assertEquals("pas encore de mesure", coverageText(null))
    }

    @Test
    fun coverageUnderTheTargetIsFlaggedOnlyOnceItMeansSomething() {
        assertTrue(coverageBelowTarget(MinuteCoverage(940, 1000)))
        assertFalse(coverageBelowTarget(MinuteCoverage(950, 1000)), "95 % pile atteint l'objectif")
        assertFalse(coverageBelowTarget(MinuteCoverage(1, 5)), "5 minutes de démarrage ne sont pas un trou")
        assertFalse(coverageBelowTarget(null))
    }
}
