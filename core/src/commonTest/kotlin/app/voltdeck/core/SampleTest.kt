package app.voltdeck.core

import kotlin.test.Test
import kotlin.test.assertFailsWith

class SampleTest {
    @Test
    fun nonFiniteValuesAreRejected() {
        val metric = Metric.entries.first()
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertFailsWith<IllegalArgumentException> { Sample(metric, value = it) }
        }
    }
}
