// TEST ONLY — les clés et annonces ci-dessous sont des vecteurs de test publics, pas des secrets.
//
// Sources du format : Victron Energy, « Extra manufacturer Data », version du 2022-12-14,
// https://communityarchive.victronenergy.com/storage/attachments/extra-manufacturer-data-2022-12-14.pdf
// Sources des vecteurs : keshavdv/victron-ble (Unlicense), tests/test_battery_monitor.py, tests/test_solar_charger.py,
// tests/test_dcdc_converter.py ; rajlaud/victron-ble-ha-parser (Apache-2.0), tests/ (mêmes annonces et mêmes clés).
// Les valeurs attendues sont celles que ces projets affirment ; les écarts voulus sont signalés en commentaire.

package app.voltdeck.drivers.victron

import app.voltdeck.core.Metric
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VictronReferenceVectorsTest {
    @Test
    fun solarCharger0x01() {
        val result = decoded(hex(Vectors.SOLAR_ADV), keyOf(Vectors.SOLAR_KEY))
        assertEquals(VictronRecordType.SOLAR_CHARGER, result.type)
        assertEquals(0xA042, result.modelId)
        val s = result.samples
        assertEquals(4.0, s.value(Metric.DEVICE_STATE)) // absorption
        assertEquals(0.0, s.value(Metric.ERROR_CODE))
        assertEquals(13.88, s.value(Metric.BATTERY_VOLTAGE))
        assertEquals(1.4, s.value(Metric.BATTERY_CURRENT))
        assertEquals(0.03, s.value(Metric.YIELD_TODAY)) // 30 Wh
        assertEquals(19.0, s.value(Metric.PV_POWER))
        assertEquals(0.0, s.value(Metric.LOAD_CURRENT))
    }

    @Test
    fun batteryMonitor0x02() {
        val result = decoded(hex(Vectors.BATTERY_MONITOR_ADV), keyOf(Vectors.BATTERY_MONITOR_KEY))
        assertEquals(VictronRecordType.BATTERY_MONITOR, result.type)
        assertEquals(0xA389, result.modelId)
        val s = result.samples
        assertEquals(12.53, s.value(Metric.BATTERY_VOLTAGE))
        assertEquals(0.0, s.value(Metric.BATTERY_CURRENT))
        assertEquals(-50.0, s.value(Metric.CONSUMED_AH))
        assertEquals(50.0, s.value(Metric.SOC))
        assertEquals(0.0, s.value(Metric.ALARM_REASON))
        assertNull(s.value(Metric.TIME_TO_GO), "autonomie NA (0xFFFF) : métrique absente")
        assertTrue(s.metrics().intersect(setOf(Metric.AUX_VOLTAGE, Metric.MID_VOLTAGE, Metric.TEMPERATURE)).isEmpty())
    }

    @Test
    fun dcdcConverter0x04() {
        val result = decoded(hex(Vectors.DCDC_ADV), keyOf(Vectors.DCDC_KEY))
        assertEquals(VictronRecordType.DCDC_CONVERTER, result.type)
        assertEquals(0xA3C0, result.modelId)
        val s = result.samples
        assertEquals(0.0, s.value(Metric.DEVICE_STATE)) // arrêt
        assertEquals(0.0, s.value(Metric.ERROR_CODE))
        assertEquals(13.15, s.value(Metric.INPUT_VOLTAGE))
        assertNull(s.value(Metric.OUTPUT_VOLTAGE), "tension de sortie NA (0x7FFF) : métrique absente")
        assertEquals(128.0, s.value(Metric.OFF_REASON)) // bit 7 : arrêt moteur
    }

    @Test
    fun decryptedSolarVectors() {
        val s = VictronRecords.decode(VictronRecordType.SOLAR_CHARGER, hex("04006c050e000300130000fe409ac069"))
        assertEquals(13.88, s.value(Metric.BATTERY_VOLTAGE))
        assertEquals(1.4, s.value(Metric.BATTERY_CURRENT))
        assertEquals(0.03, s.value(Metric.YIELD_TODAY))
        assertEquals(19.0, s.value(Metric.PV_POWER))
        assertEquals(0.0, s.value(Metric.LOAD_CURRENT))

        val bulk = VictronRecords.decode(VictronRecordType.SOLAR_CHARGER, hex("0300f80402000200030000fe8c9a5572"))
        assertEquals(3.0, bulk.value(Metric.DEVICE_STATE))
        assertEquals(12.72, bulk.value(Metric.BATTERY_VOLTAGE))

        val mppt100 = VictronRecords.decode(VictronRecordType.SOLAR_CHARGER, hex("0300fb09650032000901ffff31bc45ad"))
        assertEquals(10.1, mppt100.value(Metric.BATTERY_CURRENT))
        assertEquals(25.55, mppt100.value(Metric.BATTERY_VOLTAGE))
        assertEquals(265.0, mppt100.value(Metric.PV_POWER))
        assertEquals(0.5, mppt100.value(Metric.YIELD_TODAY)) // 500 Wh
        assertNull(mppt100.value(Metric.LOAD_CURRENT), "courant de sortie NA (0x1FF) : métrique absente")
    }

    @Test
    fun decryptedBatteryMonitorAuxInputs() {
        val base = VictronRecords.decode(VictronRecordType.BATTERY_MONITOR, hex("ffffe50400000000030000f40140df03"))
        assertEquals(12.53, base.value(Metric.BATTERY_VOLTAGE))
        assertEquals(-50.0, base.value(Metric.CONSUMED_AH))
        assertEquals(50.0, base.value(Metric.SOC))

        val mid = VictronRecords.decode(VictronRecordType.BATTERY_MONITOR, hex("ffffe6040000feff010000000080fe0c"))
        assertEquals(655.34, mid.value(Metric.MID_VOLTAGE))

        val starter = VictronRecords.decode(VictronRecordType.BATTERY_MONITOR, hex("ffffe6040000feff000000000080feac"))
        assertEquals(-0.02, starter.value(Metric.AUX_VOLTAGE))
    }

    @Test
    fun auxTemperatureAtTheTopOfTheRangeIsNotAvailable() {
        // Écart voulu avec keshavdv, qui lit 0xFFFF comme 382,2 °C : la plage publiée par Victron s'arrête à 655,34 K.
        val s = VictronRecords.decode(VictronRecordType.BATTERY_MONITOR, hex("ffffe6040000ffff020000000080fede"))
        assertNull(s.value(Metric.TEMPERATURE))

        val ok = VictronRecords.decode(VictronRecordType.BATTERY_MONITOR, hex("ffffe6040000feff020000000080fede"))
        assertEquals(382.19, ok.value(Metric.TEMPERATURE)!!, absoluteTolerance = 1e-9)
    }

    @Test
    fun auxMidpointAndStarterVoltageNotAvailableValuesAreAbsent() {
        // Entrée auxiliaire 1 (point milieu) à 0xFFFF et 0 (tension) à 0x7FFF : hors plage publiée, donc NA.
        val mid = VictronRecords.decode(VictronRecordType.BATTERY_MONITOR, hex("ffffe6040000ffff010000000080fede"))
        assertNull(mid.value(Metric.MID_VOLTAGE))
        assertNull(mid.value(Metric.AUX_VOLTAGE))

        val starter = VictronRecords.decode(VictronRecordType.BATTERY_MONITOR, hex("ffffe6040000ff7f000000000080fede"))
        assertNull(starter.value(Metric.AUX_VOLTAGE))
        assertNull(starter.value(Metric.MID_VOLTAGE))
    }
}
