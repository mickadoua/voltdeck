package app.voltdeck

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatsModelTest {
    private val friday = LocalDate(2026, 10, 16)

    @Test
    fun periodsStartAndEndOnTheirCalendarBoundaries() {
        assertEquals(LocalDate(2026, 10, 12), StatsPeriod(PeriodKind.WEEK, friday).first)
        assertEquals(LocalDate(2026, 10, 19), StatsPeriod(PeriodKind.WEEK, friday).endExclusive)
        assertEquals(LocalDate(2026, 10, 1), StatsPeriod(PeriodKind.MONTH, friday).first)
        assertEquals(31, StatsPeriod(PeriodKind.MONTH, friday).days.size)
        assertEquals(LocalDate(2026, 9, 1), StatsPeriod(PeriodKind.MONTH, friday).previous().first)
        assertEquals(LocalDate(2026, 10, 17), StatsPeriod(PeriodKind.DAY, friday).next().first)
    }

    @Test
    fun labelsAreFrench() {
        assertEquals("vendredi 16 octobre", StatsPeriod(PeriodKind.DAY, friday).label)
        assertEquals("12 → 18 octobre", StatsPeriod(PeriodKind.WEEK, friday).label)
        assertEquals("28 sept. → 4 oct.", StatsPeriod(PeriodKind.WEEK, LocalDate(2026, 10, 1)).label)
        assertEquals("octobre 2026", StatsPeriod(PeriodKind.MONTH, friday).label)
    }

    @Test
    fun theCurrentPeriodContainsToday() {
        assertTrue(StatsPeriod(PeriodKind.WEEK, friday).contains(LocalDate(2026, 10, 18)))
        assertFalse(StatsPeriod(PeriodKind.WEEK, friday).contains(LocalDate(2026, 10, 19)))
    }

    @Test
    fun variationNeedsAKnownNonZeroPreviousPeriod() {
        assertEquals(50.0, variation(EnergyTotal(3.0, false, null), EnergyTotal(2.0, false, null)))
        assertNull(variation(EnergyTotal(3.0, false, null), null))
        assertNull(variation(EnergyTotal(3.0, false, null), EnergyTotal(0.0, false, null)))
    }

    @Test
    fun axisCeilingsAreRound() {
        assertEquals(250.0, niceCeiling(212.0))
        assertEquals(1000.0, niceCeiling(510.0))
        assertEquals(1.0, niceCeiling(0.0))
        assertEquals(20.0, niceCeiling(17.0))
    }
}
