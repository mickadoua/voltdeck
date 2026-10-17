// TEST ONLY — vecteurs de test publics (keshavdv/victron-ble, Unlicense, tests/test_dc_energy_meter.py), pas des secrets.

package app.voltdeck.drivers.victron

import app.voltdeck.ble.Adv
import app.voltdeck.ble.ConnectionPolicy
import app.voltdeck.ble.PlatformDeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Metric
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class VictronDcEnergyMeterTest {
    private fun plain(hex: String) = VictronRecords.decode(VictronRecordType.DC_ENERGY_METER, hex(hex))

    @Test
    fun theReferenceAdvertisementDecodesAsADcdcMeter() {
        val decoded = decoded(hex(DcMeterVectors.ADV), keyOf(DcMeterVectors.KEY))
        assertEquals(VictronRecordType.DC_ENERGY_METER, decoded.type)
        assertEquals(-3.0, decoded.samples.value(Metric.METER_TYPE))
        assertEquals(12.52, decoded.samples.value(Metric.BATTERY_VOLTAGE))
        assertEquals(0.0, decoded.samples.value(Metric.BATTERY_CURRENT))
        assertEquals(-0.01, decoded.samples.value(Metric.AUX_VOLTAGE))
        assertEquals(0.0, decoded.samples.value(Metric.ALARM_REASON))
    }

    @Test
    fun auxiliaryInputFollowsItsMode() {
        assertEquals(-0.01, plain(DcMeterVectors.PLAIN_DCDC_STARTER).value(Metric.AUX_VOLTAGE))
        assertNull(plain(DcMeterVectors.PLAIN_TEMPERATURE_NONE).value(Metric.TEMPERATURE))
        assertEquals(76.37, plain(DcMeterVectors.PLAIN_TEMPERATURE).value(Metric.TEMPERATURE)!!, 1e-9)
        val source = plain(DcMeterVectors.PLAIN_GENERIC_SOURCE)
        assertEquals(-1.0, source.value(Metric.METER_TYPE))
        assertEquals(26.71, source.value(Metric.BATTERY_VOLTAGE))
        assertNull(source.value(Metric.AUX_VOLTAGE))
        assertNull(source.value(Metric.TEMPERATURE))
    }

    @Test
    fun aDcMeterIsRecognisedAsAShunt() {
        val adv = Adv(PlatformDeviceId("dc-meter"), "SmartShunt", -60, mapOf(ConnectionPolicy.VICTRON_COMPANY_ID to hex(DcMeterVectors.ADV)))
        val candidate = VictronInstantReadoutDriver(InMemoryVictronKeyStore()).identify(adv)!!
        assertEquals(DeviceRole.SHUNT, candidate.role)
    }

    @Test
    fun anImplausibleMeterTypeIsAWrongKey() {
        val key = keyOf(DcMeterVectors.KEY)
        val bad = hex(DcMeterVectors.PLAIN_GENERIC_SOURCE).also { it[0] = 0x00; it[1] = 0x40 }
        val header = byteArrayOf(0x10, 0, 0x89.toByte(), 0xA3.toByte(), 0x0D, 0x34, 0x12, key.checkByte.toByte())
        assertIs<VictronDecodeResult.WrongKey>(VictronDecoder.decode(header + VictronCrypto.encryptFirstBlock(key, 0x1234, bad), key))
    }
}
