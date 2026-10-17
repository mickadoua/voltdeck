package app.voltdeck

import app.voltdeck.storage.AggRow
import app.voltdeck.storage.AggStats
import app.voltdeck.storage.EnergyBucket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class ChartModelTest {
    private val t0 = Instant.fromEpochSeconds(1_800_000_000)

    private fun row(hour: Int, mean: Double) = AggRow(t0 + hour.hours, AggStats(1, mean, mean, mean, mean))

    @Test
    fun aMissingHourCutsTheCurveInsteadOfBridgingIt() {
        val segments = segmentsOf(listOf(row(0, 50.0), row(1, 52.0), row(3, 40.0), row(4, 41.0)), t0)
        assertEquals(2, segments.size)
        assertEquals(listOf(0.0, 1.0), segments[0].x)
        assertEquals(listOf(3.0, 4.0), segments[1].x)
        assertEquals(listOf(40.0, 41.0), segments[1].y)
    }

    @Test
    fun aContinuousSeriesStaysInOneSegment() {
        val segments = segmentsOf((0..5).map { row(it, 10.0 + it) }, t0)
        assertEquals(1, segments.size)
        assertEquals(6, segments[0].y.size)
    }

    @Test
    fun anIsolatedPointIsKeptAsItsOwnSegment() {
        val segments = segmentsOf(listOf(row(0, 50.0), row(1, 51.0), row(5, 30.0)), t0)
        assertEquals(2, segments.size)
        assertEquals(listOf(30.0), segments[1].y)
    }

    @Test
    fun noRowsMeansNoSegment() {
        assertTrue(segmentsOf(emptyList(), t0).isEmpty())
    }

    @Test
    fun xIsMeasuredInHoursFromThePeriodStart() {
        val segments = segmentsOf(listOf(row(2, 1.0)), t0)
        assertEquals(2.0, segments.single().x.single())
    }

    @Test
    fun energyColumnsKeepEverySlotAndLeaveUnmeasuredOnesEmpty() {
        val slots = (0..2).map { t0 + it.hours }
        val columns = energyColumns(slots, listOf(EnergyBucket(slots[1], 120.0, null, 80.0)))
        assertEquals(listOf(0.0, 120.0, 0.0), columns.produced)
        assertEquals(listOf(0.0, 80.0, 0.0), columns.consumed)
    }

    @Test
    fun aNegativeCalculatedConsumptionIsNotDrawnBelowTheAxis() {
        val slots = listOf(t0)
        val columns = energyColumns(slots, listOf(EnergyBucket(t0, 10.0, null, -5.0)))
        assertEquals(listOf(0.0), columns.consumed)
    }
}
