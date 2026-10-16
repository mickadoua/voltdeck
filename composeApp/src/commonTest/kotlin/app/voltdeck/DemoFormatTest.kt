package app.voltdeck

import app.voltdeck.core.Metric
import app.voltdeck.core.Sample
import kotlin.test.Test
import kotlin.test.assertEquals

class DemoFormatTest {
    @Test
    fun formatsWithFixedDecimals() {
        assertEquals("13.31", formatDecimal(13.309, 2))
        assertEquals("0.05", formatDecimal(0.05, 2))
        assertEquals("-2.50", formatDecimal(-2.5, 2))
        assertEquals("380", formatDecimal(379.6, 0))
        assertEquals("3.301", formatDecimal(3.3014, 3))
    }

    @Test
    fun negativeZeroHasNoSign() {
        assertEquals("0.00", formatDecimal(-0.001, 2))
    }

    @Test
    fun sampleTextUsesTheMetricUnit() {
        assertEquals("57.5 %", sampleText(Sample(Metric.SOC, value = 57.46)))
        assertEquals("-3.20 A", sampleText(Sample(Metric.BATTERY_CURRENT, value = -3.2)))
        assertEquals("3.302 V", sampleText(Sample(Metric.CELL_VOLTAGE, 2, 3.3021)))
    }

    @Test
    fun indexedMetricsAreNumberedFromOne() {
        assertEquals("Cellule 3", sampleLabel(Sample(Metric.CELL_VOLTAGE, 2, 3.3)))
    }
}
