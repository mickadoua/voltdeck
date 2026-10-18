package app.voltdeck

import app.voltdeck.storage.AggRow
import app.voltdeck.storage.AggStats
import app.voltdeck.storage.EnergyBucket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ChartModelTest {
    private val t0 = Instant.fromEpochSeconds(1_800_000_000)

    private fun row(hour: Int, mean: Double) = AggRow(t0 + hour.hours, AggStats(1, mean, mean, mean, mean))

    @Test
    fun aMissingHourCutsTheCurveInsteadOfBridgingIt() {
        val segments = segmentsOf(listOf(row(0, 50.0), row(1, 52.0), row(3, 40.0), row(4, 41.0)), t0)
        assertEquals(2, segments.size)
        assertEquals(listOf(0.0, 60.0), segments[0].x)
        assertEquals(listOf(180.0, 240.0), segments[1].x)
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
    fun xIsMeasuredInWholeMinutesFromTheAxisOrigin() {
        val segments = segmentsOf(listOf(row(2, 1.0)), t0)
        assertEquals(120.0, segments.single().x.single())
        assertEquals(t0 + 2.hours, instantAt(t0, 120.0))
    }

    private fun minuteRow(minute: Int, mean: Double) = AggRow(t0 + minute.minutes, AggStats(1, mean, mean, mean, mean))

    @Test
    fun minuteRowsReachVicoWithoutTheXPrecisionError() {
        // Origin à 6 h 07 min 30 s avant la première mesure : l'ancien x (secondes / 3600) donnait 6,5333…
        val origin = t0 - 6.hours - 7.minutes - 30.seconds
        val rows = (0 until 90).map { minuteRow(it, 50.0 + it % 7) }
        val segments = segmentsOf(rows, origin)
        val model = LineCartesianLayerModel(segments.map { s -> s.x.zip(s.y) { x, y -> LineCartesianLayerModel.Entry(x, y) } })
        assertEquals(1.0, model.getXDeltaGcd())
        assertTrue(segments.single().x.all { it == it.toLong().toDouble() })
    }

    @Test
    fun aGapBetweenMinuteSegmentsStillGivesAnExactStep() {
        val rows = (0 until 30).map { minuteRow(it, 40.0) } + (200 until 230).map { minuteRow(it, 41.0) }
        val segments = segmentsOf(rows, t0 - 13.minutes)
        assertEquals(2, segments.size)
        val model = LineCartesianLayerModel(segments.map { s -> s.x.zip(s.y) { x, y -> LineCartesianLayerModel.Entry(x, y) } })
        assertEquals(1.0, model.getXDeltaGcd())
    }

    @Test
    fun hourlyRowsKeepAnHourStepAndTheAxisOriginSitsOnAWholeHour() {
        val zone = TimeZone.of("Europe/Paris")
        val origin = startOfHour(t0 + 17.minutes + 40.seconds, zone)
        assertEquals(0, origin.toLocalDateTime(zone).minute)
        assertEquals(0, origin.toLocalDateTime(zone).second)
        val segments = segmentsOf((0 until 24).map { row(it, 1.0 * it) }, origin)
        val model = LineCartesianLayerModel(segments.map { s -> s.x.zip(s.y) { x, y -> LineCartesianLayerModel.Entry(x, y) } })
        assertEquals(60.0, model.getXDeltaGcd())
    }

    @Test
    fun anUnmeasuredSlotStaysNullForTheReadoutAndIsDrawnEmpty() {
        val slots = (0..2).map { t0 + it.hours }
        val columns = energyColumns(slots, listOf(EnergyBucket(slots[1], 120.0, null, 80.0)))
        assertEquals(listOf(null, 120.0, null), columns.produced)
        assertEquals(listOf(null, 80.0, null), columns.consumed)
        assertEquals(listOf(0.0, 120.0, 0.0), columns.producedDrawn)
        assertEquals(listOf(0.0, 80.0, 0.0), columns.consumedDrawn)
    }

    @Test
    fun aSystemWithoutSolarNeverReadsZeroProduced() {
        val slots = listOf(t0)
        val columns = energyColumns(slots, listOf(EnergyBucket(t0, null, null, 40.0)))
        assertNull(columns.produced.single())
        assertEquals(40.0, columns.consumed.single())
    }

    @Test
    fun aNegativeCalculatedConsumptionIsReportedAsIsButNotDrawnBelowTheAxis() {
        val slots = listOf(t0)
        val columns = energyColumns(slots, listOf(EnergyBucket(t0, 10.0, null, -5.0)))
        assertEquals(-5.0, columns.consumed.single())
        assertEquals(listOf(0.0), columns.consumedDrawn)
    }

    @Test
    fun theHistoryWindowFollowsItsEndInsteadOfFreezingAtOpening() {
        val opened = HistoryWindow(t0)
        val anHourLater = HistoryWindow(t0 + 1.hours)
        assertEquals(t0 - 24.hours, opened.from)
        assertEquals(t0 + 1.hours - 24.hours, anHourLater.from)
        assertEquals(t0 + 1.hours, anHourLater.to)
    }
}
