package app.voltdeck

import app.voltdeck.core.DeviceId
import app.voltdeck.core.Metric
import app.voltdeck.core.Reading
import app.voltdeck.core.Sample
import app.voltdeck.drivers.victron.VictronLinkStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

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

    @Test
    fun victronMetricsAreReadable() {
        assertEquals("Bulk", sampleText(Sample(Metric.DEVICE_STATE, value = 3.0)))
        assertEquals("Code 9", sampleText(Sample(Metric.DEVICE_STATE, value = 9.0)))
        assertEquals("Aucune", sampleText(Sample(Metric.ERROR_CODE, value = 0.0)))
        assertEquals("Code 17", sampleText(Sample(Metric.ERROR_CODE, value = 17.0)))
        assertEquals("Aucune", sampleText(Sample(Metric.ALARM_REASON, value = 0.0)))
        assertEquals("0x104", sampleText(Sample(Metric.OFF_REASON, value = 260.0)))
        assertEquals("16 h 40 min", sampleText(Sample(Metric.TIME_TO_GO, value = 1000.0)))
        assertEquals("-80.4 Ah", sampleText(Sample(Metric.CONSUMED_AH, value = -80.4)))
    }

    @Test
    fun victronDevicesHaveATitle() {
        assertEquals("SmartShunt (simulé)", deviceTitle(app.voltdeck.simulator.SimulatedPlant.SHUNT_ID))
    }

    @Test
    fun protectionAlarmsAreListedInFrench() {
        assertEquals("Aucune", sampleText(Sample(Metric.PROTECTION_FLAGS, value = 0.0)))
        assertEquals("surtension cellule", sampleText(Sample(Metric.PROTECTION_FLAGS, value = 1.0)))
        assertEquals("surtension cellule, court-circuit", protectionText(0x401))
        assertEquals("Alarmes", sampleLabel(Sample(Metric.PROTECTION_FLAGS, value = 0.0)))
    }

    @Test
    fun unnamedProtectionBitIsNeverShownAsNone() {
        assertEquals("Alarme inconnue", protectionText(0x2000))
        assertEquals("Alarme inconnue", sampleText(Sample(Metric.PROTECTION_FLAGS, value = 0x8000.toDouble())))
        assertEquals("surtension cellule", protectionText(0x2001))
    }

    @Test
    fun relayStateTellsWhichDirectionIsCut() {
        assertEquals("Charge et décharge actives", relayText(3))
        assertEquals("Charge coupée", relayText(2))
        assertEquals("Décharge coupée", relayText(1))
        assertEquals("Charge et décharge coupées", relayText(0))
    }

    @Test
    fun fetStateIsShownAsRelayAndIgnoresReservedBits() {
        assertEquals("Charge et décharge actives", sampleText(Sample(Metric.FET_STATE, value = 3.0)))
        assertEquals("Charge et décharge coupées", sampleText(Sample(Metric.FET_STATE, value = 0.0)))
        assertEquals("Charge et décharge actives", sampleText(Sample(Metric.FET_STATE, value = 0xFF.toDouble())))
        assertEquals("Charge et décharge actives", sampleText(Sample(Metric.FET_STATE, value = 131.0)))
    }

    @Test
    fun chargerDeviceStateIsNotShownAsRelay() {
        assertEquals("Absorption", sampleText(Sample(Metric.DEVICE_STATE, value = 4.0)))
    }

    @Test
    fun wrongKeyDevicesLoseTheirLastReading() {
        val a = DeviceId("a")
        val b = DeviceId("b")
        val reading = { id: DeviceId -> Reading(id, Instant.fromEpochSeconds(1), listOf(Sample(Metric.SOC, value = 50.0))) }
        val latest = mutableMapOf(a to reading(a), b to reading(b))
        dropWrongKeyReadings(latest, mapOf(a to VictronLinkStatus.WRONG_KEY, b to VictronLinkStatus.OK))
        assertEquals(setOf(b), latest.keys)
    }
}
