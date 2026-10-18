package app.voltdeck

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OneLineFitTest {
    private fun settle(sizeSp: Float, hasShort: Boolean, fits: (OneLineFit) -> Boolean): OneLineFit {
        var fit = OneLineFit()
        repeat(60) { fit = nextOneLineFit(fit, overflow = !fits(fit), sizeSp = sizeSp, hasShort = hasShort) }
        return fit
    }

    @Test
    fun aLabelThatFitsIsLeftAlone() {
        assertEquals(OneLineFit(), settle(14f, true) { true })
    }

    @Test
    fun aLabelThatOverflowsShrinksBeforeAnythingElse() {
        val fit = settle(14f, true) { it.shrink <= 0.9f && !it.abbreviated }
        assertTrue(fit.shrink < 1f)
        assertFalse(fit.abbreviated)
    }

    @Test
    fun shrinkingNeverGoesBelowTheFloor() {
        val fit = settle(14f, false) { false }
        assertTrue(14f * fit.shrink >= MIN_ONE_LINE_SP)
        assertFalse(fit.abbreviated)
    }

    @Test
    fun atTheFloorTheAbbreviationTakesOverAtFullSize() {
        var fit = OneLineFit()
        repeat(60) { fit = nextOneLineFit(fit, overflow = !fit.abbreviated, sizeSp = 14f, hasShort = true) }
        assertEquals(OneLineFit(shrink = 1f, abbreviated = true), fit)
    }

    @Test
    fun anAbbreviationThatStillOverflowsShrinksToTheFloorThenStops() {
        val fit = settle(14f, true) { false }
        assertTrue(fit.abbreviated)
        assertTrue(14f * fit.shrink >= MIN_ONE_LINE_SP)
    }

    @Test
    fun overflowIsMeasuredAgainstTheAvailableWidth() {
        assertFalse(oneLineOverflows(intrinsicWidth = 80f, availableWidth = 120))
        assertTrue(oneLineOverflows(intrinsicWidth = 130f, availableWidth = 120))
        assertTrue(oneLineOverflows(intrinsicWidth = 119f, availableWidth = 120))
    }

    @Test
    fun anUnboundedSlotNeverOverflows() {
        assertFalse(oneLineOverflows(intrinsicWidth = 5000f, availableWidth = Int.MAX_VALUE))
    }
}
