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
}
