package app.voltdeck

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ShuntSetupTest {
    @Test
    fun everySetupSurvivesEncoding() {
        val setups = listOf(
            ShuntSetup.DEFAULT,
            ShuntSetup(ShuntPlacement.IGNORED),
            ShuntSetup(ShuntPlacement.LOAD, Circuit.FRIDGE),
            ShuntSetup(ShuntPlacement.SOURCE, Circuit.ALTERNATOR, "Moteur, côté cellule"),
        )
        setups.forEach { assertEquals(it, ShuntSetup.decode(it.encode())) }
    }

    @Test
    fun unreadableOrInconsistentTextIsIgnored() {
        assertNull(ShuntSetup.decode(""))
        assertNull(ShuntSetup.decode("ROOF"))
        assertNull(ShuntSetup.decode("LOAD"))
        assertNull(ShuntSetup.decode("LOAD\u001FALTERNATOR"))
        assertNull(ShuntSetup.decode("BATTERY\u001FFRIDGE"))
    }

    @Test
    fun aCircuitMustMatchThePlacement() {
        assertFailsWith<IllegalArgumentException> { ShuntSetup(ShuntPlacement.SOURCE, Circuit.FRIDGE) }
        assertFailsWith<IllegalArgumentException> { ShuntSetup(ShuntPlacement.BATTERY, Circuit.FRIDGE) }
    }

    @Test
    fun theNameFallsBackToTheCircuit() {
        assertEquals("Frigo", ShuntSetup(ShuntPlacement.LOAD, Circuit.FRIDGE, " ").displayName)
        assertEquals("Négatif de la batterie", placementSummary(ShuntSetup.DEFAULT))
        assertEquals("Consommation · Frigo", placementSummary(ShuntSetup(ShuntPlacement.LOAD, Circuit.FRIDGE)))
    }

    @Test
    fun frenchNumbersUseACommaAndARealMinus() {
        assertEquals("13,32", frDecimal(13.318, 2))
        assertEquals("+8,4", signedFr(8.4, 1))
        assertEquals("−2,1", signedFr(-2.1, 1))
        assertEquals("0,0", signedFr(0.0, 1))
    }

    @Test
    fun aDcMeterAnnouncesWhereItIsWired() {
        fun meter(type: Double) = app.voltdeck.core.Reading(
            app.voltdeck.core.DeviceId("s"), kotlin.time.Instant.fromEpochSeconds(0),
            listOf(app.voltdeck.core.Sample(app.voltdeck.core.Metric.METER_TYPE, value = type)),
        )
        assertEquals(ShuntSetup(ShuntPlacement.LOAD, Circuit.FRIDGE), detectedSetup(meter(3.0)))
        assertEquals(ShuntSetup(ShuntPlacement.SOURCE, Circuit.ALTERNATOR), detectedSetup(meter(-6.0)))
        assertEquals("Chargeur DC-DC", detectedSetup(meter(-3.0))!!.displayName)
        assertEquals(ShuntPlacement.SOURCE, detectedSetup(meter(-3.0))!!.placement)
        assertNull(detectedSetup(null))
    }

    @Test
    fun theCapacitySurvivesEncodingAndOldTextStillReads() {
        val withCapacity = ShuntSetup(ShuntPlacement.BATTERY, capacityAh = 200.0)
        assertEquals(withCapacity, ShuntSetup.decode(withCapacity.encode()))
        assertEquals(ShuntSetup(ShuntPlacement.BATTERY, capacityAh = 105.5), ShuntSetup.decode(ShuntSetup(ShuntPlacement.BATTERY, capacityAh = 105.5).encode()))
        assertEquals("BATTERY", ShuntSetup.DEFAULT.encode())
        assertEquals(ShuntSetup.DEFAULT, ShuntSetup.decode("BATTERY"))
        assertEquals(ShuntSetup(ShuntPlacement.SOURCE, Circuit.SOLAR, "Toit"), ShuntSetup.decode("SOURCE\u001FSOLAR\u001FToit"))
        assertNull(ShuntSetup.DEFAULT.capacityAh)
    }

    @Test
    fun aCapacityIsOnlyForTheBatteryAndWithinLimits() {
        assertFailsWith<IllegalArgumentException> { ShuntSetup(ShuntPlacement.LOAD, Circuit.FRIDGE, capacityAh = 100.0) }
        assertFailsWith<IllegalArgumentException> { ShuntSetup(ShuntPlacement.BATTERY, capacityAh = 0.0) }
        assertFailsWith<IllegalArgumentException> { ShuntSetup(ShuntPlacement.BATTERY, capacityAh = -5.0) }
        assertFailsWith<IllegalArgumentException> { ShuntSetup(ShuntPlacement.BATTERY, capacityAh = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { ShuntSetup(ShuntPlacement.BATTERY, capacityAh = 1e9) }
    }

    @Test
    fun anUnreadableStoredCapacityIsDroppedNotTheWholeSetup() {
        assertEquals(ShuntSetup.DEFAULT, ShuntSetup.decode("BATTERY\u001F\u001F\u001Fabc"))
        assertEquals(ShuntSetup.DEFAULT, ShuntSetup.decode("BATTERY\u001F\u001F\u001F-3.0"))
        assertEquals(ShuntSetup.DEFAULT, ShuntSetup.decode("BATTERY\u001F\u001F\u001FNaN"))
    }

    @Test
    fun theCapacityTypedByTheUserAcceptsACommaAndRejectsNonsense() {
        assertEquals(200.0, capacityOf("200"))
        assertEquals(105.5, capacityOf(" 105,5 "))
        assertNull(capacityOf(""))
        assertNull(capacityOf("0"))
        assertNull(capacityOf("1.2.3"))
        assertNull(capacityOf("99999"))
    }
}
