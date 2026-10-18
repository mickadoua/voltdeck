package app.voltdeck

import app.voltdeck.simulator.AcceleratedClock
import app.voltdeck.storage.EnergyBucket
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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
    fun aYearRunsFromJanuaryFirstToTheNextOne() {
        val year = StatsPeriod(PeriodKind.YEAR, friday)
        assertEquals(LocalDate(2026, 1, 1), year.first)
        assertEquals(LocalDate(2027, 1, 1), year.endExclusive)
        assertEquals(365, year.days.size)
        assertEquals(LocalDate(2025, 1, 1), year.previous().first)
        assertEquals(LocalDate(2027, 1, 1), year.next().first)
        assertEquals(366, StatsPeriod(PeriodKind.YEAR, LocalDate(2028, 6, 1)).days.size)
        assertEquals("2026", year.label)
        assertTrue(year.contains(LocalDate(2026, 12, 31)))
        assertFalse(year.contains(LocalDate(2027, 1, 1)))
    }

    @Test
    fun theYearViewIsTheFourthTab() {
        assertEquals(listOf("Jour", "Semaine", "Mois", "Année"), PeriodKind.entries.map { it.label })
    }

    @Test
    fun aYearInProgressIsComparedOnItsFinishedDaysAgainstTheSameDaysOfLastYear() {
        val (current, previous) = comparableDays(StatsPeriod(PeriodKind.YEAR, friday), today = friday)
        assertEquals(LocalDate(2026, 1, 1), current.first())
        assertEquals(friday.minus(1, DateTimeUnit.DAY), current.last())
        assertEquals(current.size, previous.size)
        assertEquals(LocalDate(2025, 1, 1), previous.first())
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

    @Test
    fun aLongerCompletedMonthIsComparedOnTheDaysTheShorterOneHas() {
        val october = StatsPeriod(PeriodKind.MONTH, LocalDate(2026, 10, 10))
        val (current, previous) = comparableDays(october, today = LocalDate(2026, 11, 3))
        assertEquals(30, current.size)
        assertEquals(30, previous.size)
        assertEquals(LocalDate(2026, 9, 1), previous.first())
        assertEquals(LocalDate(2026, 10, 30), current.last())
    }

    @Test
    fun anUnfinishedPeriodIsComparedOnItsFinishedDaysOnly() {
        val october = StatsPeriod(PeriodKind.MONTH, friday)
        val (current, previous) = comparableDays(october, today = friday)
        assertEquals(15, current.size)
        assertEquals(LocalDate(2026, 10, 15), current.last())
        assertEquals(15, previous.size)
        assertEquals(LocalDate(2026, 9, 15), previous.last())
    }

    @Test
    fun theDayInProgressHasNothingToCompare() {
        val (current, previous) = comparableDays(StatsPeriod(PeriodKind.DAY, friday), today = friday)
        assertTrue(current.isEmpty())
        assertTrue(previous.isEmpty())
    }

    @Test
    fun aShorterPreviousMonthLimitsBothSides() {
        val march = StatsPeriod(PeriodKind.MONTH, LocalDate(2026, 3, 31))
        val (current, previous) = comparableDays(march, today = LocalDate(2026, 3, 31))
        assertEquals(28, previous.size)
        assertEquals(28, current.size)
    }

    @Test
    fun aHalfCollectedPreviousPeriodIsComparedOnTheCommonDaysOnly() {
        val current = List(14) { 10.0 }
        val previous = List(14) { if (it >= 11) 10.0 else null }
        val c = compareEnergy(current, previous, wholePeriods = false)!!
        assertEquals(0.0, c.percent)
        assertEquals(3, c.days)
        assertEquals(ComparisonBasis.COMMON_DAYS, c.basis)
    }

    @Test
    fun aCollectionGapOnTheCurrentSideDoesNotSkewTheVariationEither() {
        val current = listOf(10.0, null, 10.0, 10.0)
        val previous = listOf(10.0, 10.0, 20.0, null)
        val c = compareEnergy(current, previous, wholePeriods = false)!!
        assertEquals(2, c.days)
        assertEquals(-33.3, c.percent, 0.1)
    }

    @Test
    fun noCommonMeasuredDayMeansNoComparison() {
        assertNull(compareEnergy(listOf(5.0, null), listOf(null, 5.0), wholePeriods = false))
        assertNull(compareEnergy(emptyList(), emptyList(), wholePeriods = true))
    }

    @Test
    fun fullyMeasuredPeriodsKeepTheirBasis() {
        assertEquals(ComparisonBasis.WHOLE, compareEnergy(listOf(3.0, 3.0), listOf(2.0, 2.0), wholePeriods = true)!!.basis)
        assertEquals(ComparisonBasis.FIRST_DAYS, compareEnergy(listOf(3.0, 3.0), listOf(2.0, 2.0), wholePeriods = false)!!.basis)
        assertEquals(50.0, compareEnergy(listOf(3.0, 3.0), listOf(2.0, 2.0), wholePeriods = true)!!.percent)
    }

    private fun hoursOn(day: String, count: Int, produced: Double? = 5.0, net: Double? = 1.0): List<EnergyBucket> {
        val start = Instant.parse("${day}T00:00:00Z")
        return List(count) { EnergyBucket(start + it.hours, produced, net, null) }
    }

    @Test
    fun aDayWithOnlyAFewMeasuredHoursIsNotWellMeasured() {
        val hours = hoursOn("2026-09-28", 2) + hoursOn("2026-09-29", 24) + hoursOn("2026-09-30", 20) + hoursOn("2026-10-01", 19)
        val days = wellMeasuredDays(hours, TimeZone.UTC) { it.producedWh != null }
        assertEquals(setOf(LocalDate(2026, 9, 29), LocalDate(2026, 9, 30)), days)
    }

    @Test
    fun onlyTheHoursThatSatisfyTheMeasureCount() {
        val hours = hoursOn("2026-09-29", 24, produced = null)
        assertEquals(emptySet(), wellMeasuredDays(hours, TimeZone.UTC) { it.producedWh != null })
        assertEquals(setOf(LocalDate(2026, 9, 29)), wellMeasuredDays(hours, TimeZone.UTC) { it.batteryNetWh != null })
    }

    @Test
    fun aShorterCurrentPeriodIsNotLabelledAsComparedWithTheWholePreviousOne() {
        val february = StatsPeriod(PeriodKind.MONTH, LocalDate(2026, 2, 10))
        val (current, previous) = comparableDays(february, today = LocalDate(2026, 3, 5))
        assertEquals(28, current.size)
        assertEquals(28, previous.size)
        assertFalse(isWholeComparison(february, current, previous), "janvier compte 31 jours, il n'est comparé que sur 28")
    }

    @Test
    fun twoCompletedPeriodsOfTheSameLengthAreWhole() {
        val week = StatsPeriod(PeriodKind.WEEK, friday)
        val (current, previous) = comparableDays(week, today = LocalDate(2026, 10, 20))
        assertTrue(isWholeComparison(week, current, previous))
        val (started, startedPrevious) = comparableDays(week, today = LocalDate(2026, 10, 15))
        assertFalse(isWholeComparison(week, started, startedPrevious))
    }

    @Test
    fun theTrendChartRefreshesOnTheSessionClockNotOnRealTime() {
        val realStart = Instant.parse("2026-06-21T10:00:00Z")
        var real = realStart
        val realClock = object : Clock { override fun now() = real }
        val demo = AcceleratedClock(realClock, Instant.parse("2026-06-21T06:00:00Z"), 60.0)
        val shown = demo.now()
        assertFalse(historyIsStale(shown, demo.now()))
        real += 1.seconds
        assertTrue(historyIsStale(shown, demo.now()), "une seconde réelle = une minute de démo : la courbe doit suivre")
        assertFalse(historyIsStale(shown, shown + 59.seconds))
        assertTrue(historyIsStale(shown, shown + 60.seconds))
    }
}
