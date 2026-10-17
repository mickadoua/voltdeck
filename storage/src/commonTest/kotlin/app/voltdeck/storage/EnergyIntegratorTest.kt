package app.voltdeck.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class EnergyIntegratorTest {
    private val t0 = Instant.parse("2026-03-10T10:00:00Z")
    private val positive = HashMap<Long, Double>()
    private val negative = HashMap<Long, Double>()
    private val integrator = EnergyIntegrator()

    private fun add(offsetSeconds: Int, value: Double) =
        integrator.add(t0 + offsetSeconds.seconds, value) { minute, p, n ->
            positive[minute] = (positive[minute] ?: 0.0) + p
            negative[minute] = (negative[minute] ?: 0.0) + n
        }

    private val minute0 get() = t0.epochSeconds

    @Test
    fun constantCurrentGivesValueTimesSeconds() {
        add(0, 10.0)
        add(30, 10.0)
        add(60, 10.0)
        assertEquals(600.0, positive.getValue(minute0), 1e-9)
        assertEquals(0.0, negative.getValue(minute0), 1e-9)
    }

    @Test
    fun trapezoidOnARamp() {
        add(0, 0.0)
        add(60, 10.0)
        assertEquals(300.0, positive.getValue(minute0), 1e-9)
    }

    @Test
    fun segmentIsSplitAtMinuteBoundaries() {
        add(30, 10.0)
        add(90, 10.0)
        assertEquals(300.0, positive.getValue(minute0), 1e-9)
        assertEquals(300.0, positive.getValue(minute0 + 60), 1e-9)
    }

    @Test
    fun signChangeIsSplitAtZeroCrossing() {
        add(0, -10.0)
        add(60, 10.0)
        assertEquals(150.0, positive.getValue(minute0), 1e-9)
        assertEquals(150.0, negative.getValue(minute0), 1e-9)
    }

    @Test
    fun gapOverSixtySecondsIsNeverIntegrated() {
        add(0, 10.0)
        add(61, 10.0)
        assertTrue(positive.isEmpty(), "aucune énergie inventée dans un trou de 61 s")
    }

    @Test
    fun gapOfExactlySixtySecondsIsIntegrated() {
        add(0, 10.0)
        add(60, 10.0)
        assertEquals(600.0, positive.getValue(minute0), 1e-9)
    }

    @Test
    fun resetCutsTheChain() {
        add(0, 10.0)
        integrator.reset()
        add(30, 10.0)
        assertTrue(positive.isEmpty())
    }
}
