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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VictronDecoderEdgeCasesTest {
    private val batteryKey = keyOf(Vectors.BATTERY_MONITOR_KEY)

    @Test
    fun negativeCurrentIsDischarge() {
        // Charge utile du SmartShunt de référence dont le courant (22 bits, bit 66) vaut −5,000 A.
        val s = VictronRecords.decode(VictronRecordType.BATTERY_MONITOR, hex("ffffe50400000000e3b1fff40140df03"))
        assertEquals(-5.0, s.value(Metric.BATTERY_CURRENT))
        assertEquals(12.53, s.value(Metric.BATTERY_VOLTAGE))
        assertEquals(-50.0, s.value(Metric.CONSUMED_AH))
        assertEquals(50.0, s.value(Metric.SOC))
    }

    @Test
    fun negativeSolarCurrentAndNegativeBatteryVoltage() {
        // Courant −0,1 A (0xFFFF) côté MPPT : charge connectée possible, d'après le PDF.
        val s = VictronRecords.decode(VictronRecordType.SOLAR_CHARGER, hex("0300f804ffff0200030000fe00000000"))
        assertEquals(-0.1, s.value(Metric.BATTERY_CURRENT))
    }

    @Test
    fun everyNotAvailableFieldIsAbsentNeverZero() {
        val solar = VictronRecords.decode(VictronRecordType.SOLAR_CHARGER, hex("ffffff7fff7fffffffffffff"))
        assertTrue(solar.isEmpty(), "toutes les valeurs NA d'un MPPT : aucun échantillon, obtenu ${solar.metrics()}")

        // TTG NA, V NA, alarme 0, aux NA (type 0), courant NA, Ah NA, SOC NA.
        val shunt = VictronRecords.decode(
            VictronRecordType.BATTERY_MONITOR,
            hex("ffffff7f0000ff7ffcffffffffffff"),
        )
        assertEquals(setOf(Metric.ALARM_REASON), shunt.metrics(), "obtenu ${shunt.metrics()}")

        val dcdc = VictronRecords.decode(VictronRecordType.DCDC_CONVERTER, hex("ffffffffff7f00000000"))
        assertEquals(setOf(Metric.OFF_REASON), dcdc.metrics())
    }

    @Test
    fun zeroIsAValueNotAnAbsence() {
        val s = VictronRecords.decode(VictronRecordType.SOLAR_CHARGER, hex("0000000000000000000000000000"))
        assertEquals(0.0, s.value(Metric.PV_POWER))
        assertEquals(0.0, s.value(Metric.BATTERY_VOLTAGE))
        assertEquals(0.0, s.value(Metric.LOAD_CURRENT))
        assertEquals(0.0, s.value(Metric.DEVICE_STATE))
    }

    @Test
    fun consumedAhZeroIsPositiveZero() {
        val s = VictronRecords.decode(VictronRecordType.BATTERY_MONITOR, hex("ffffe6040000feff000000000080feac"))
        val consumed = s.value(Metric.CONSUMED_AH)!!
        assertEquals(0.0, consumed)
        assertTrue(1.0 / consumed > 0, "−0,0 serait une valeur différente pour les égalités de data class")
    }

    @Test
    fun wrongKeyIsRefusedWithoutDecrypting() {
        val result = VictronDecoder.decode(hex(Vectors.BATTERY_MONITOR_WRONG_KEY_ADV), keyOf("ffffffffffffffffffffffffffffffff"))
        assertEquals(VictronDecodeResult.WrongKey, result)
        assertTrue(VictronDecoder.WRONG_KEY_MESSAGE.startsWith("Clé refusée"))
        assertFalse(VictronDecoder.WRONG_KEY_MESSAGE.contains("ffffffff"))
    }

    @Test
    fun rightAdvertisementWithAnotherDevicesKeyIsRefused() {
        val result = VictronDecoder.decode(hex(Vectors.SOLAR_ADV), batteryKey)
        assertEquals(VictronDecodeResult.WrongKey, result)
    }

    @Test
    fun longerAdvertisementIsDecodedWithoutError() {
        val base = hex(Vectors.BATTERY_MONITOR_ADV)
        val extended = base + ByteArray(12) { (0x40 + it).toByte() }
        val reference = decoded(base, batteryKey).samples
        val result = decoded(extended, batteryKey)
        assertEquals(reference, result.samples, "des octets en plus ne changent pas les champs connus")

        val longSolar = hex(Vectors.SOLAR_ADV) + ByteArray(40)
        assertEquals(13.88, decoded(longSolar, keyOf(Vectors.SOLAR_KEY)).samples.value(Metric.BATTERY_VOLTAGE))
    }

    @Test
    fun truncatedFramesAreTruncatedNotWrongKey() {
        val adv = hex(Vectors.BATTERY_MONITOR_ADV)
        for (length in 0 until adv.size) {
            val cut = adv.copyOf(length)
            val result = VictronDecoder.decode(cut, batteryKey)
            if (length == 0) {
                assertEquals(VictronDecodeResult.NotProductAdvertisement, result)
            } else {
                assertEquals(VictronDecodeResult.Truncated, result, "longueur $length")
            }
        }
        assertIs<VictronDecodeResult.Decoded>(VictronDecoder.decode(adv, batteryKey))
    }

    @Test
    fun truncatedSolarAndDcdcAreRejectedOneByteShort() {
        val solar = hex(Vectors.SOLAR_ADV)
        assertEquals(VictronDecodeResult.Truncated, VictronDecoder.decode(solar.copyOf(solar.size - 1), keyOf(Vectors.SOLAR_KEY)))
        val dcdc = hex(Vectors.DCDC_ADV)
        assertEquals(VictronDecodeResult.Truncated, VictronDecoder.decode(dcdc.copyOf(dcdc.size - 1), keyOf(Vectors.DCDC_KEY)))
    }

    @Test
    fun otherManufacturerRecordsAreIgnored() {
        val notProduct = hex(Vectors.SOLAR_ADV).also { it[0] = 0x02 }
        assertEquals(VictronDecodeResult.NotProductAdvertisement, VictronDecoder.decode(notProduct, keyOf(Vectors.SOLAR_KEY)))
        // Onduleur (0x03) : type non décodé, ni erreur ni échantillon.
        val inverter = hex(Vectors.SOLAR_ADV).also { it[4] = 0x03 }
        assertEquals(VictronDecodeResult.UnsupportedRecord(0x03), VictronDecoder.decode(inverter, keyOf(Vectors.SOLAR_KEY)))
    }

    @Test
    fun checkKeyComparesOnlyTheControlByte() {
        val adv = hex(Vectors.SOLAR_ADV)
        assertEquals(VictronDecoder.KeyCheck.Matches, VictronDecoder.checkKey(adv, keyOf(Vectors.SOLAR_KEY)))
        assertEquals(VictronDecoder.KeyCheck.Mismatch, VictronDecoder.checkKey(adv, batteryKey))
        assertEquals(VictronDecoder.KeyCheck.NoUsableAdvertisement, VictronDecoder.checkKey(adv.copyOf(5), batteryKey))
        assertEquals(VictronDecoder.KeyCheck.NoUsableAdvertisement, VictronDecoder.checkKey(ByteArray(0), batteryKey))
    }

    @Test
    fun bitReaderReadsLsbFirst() {
        val r = BitReader(hex("a5ff"))
        assertEquals(1L, r.unsigned(1))
        assertEquals(0L, r.unsigned(1))
        assertEquals(0b1001L, r.unsigned(4))
        assertEquals(0b10L, r.unsigned(2))
        assertEquals(8, r.remainingBits)
        assertEquals(0xFFL, r.unsigned(8))
        assertFailsWith<IllegalArgumentException> { r.unsigned(1) }
        assertEquals(-1L, BitReader.toSigned(0x3FFFFF, 22))
        assertEquals(-32768L, BitReader.toSigned(0x8000, 16))
        assertEquals(32767L, BitReader.toSigned(0x7FFF, 16))
    }

    @Test
    fun aesCtrMatchesNistSp80038aVector() {
        // NIST SP 800-38A F.5.1, premier bloc de CTR-AES128.Encrypt.
        val out = platformAesCtr(
            hex("2b7e151628aed2a6abf7158809cf4f3c"),
            hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"),
            hex("6bc1bee22e409f96e93d7e117393172a"),
        )
        assertContentEquals(hex("874d6191b620e3261bef6864990db6ce"), out)
        val partial = platformAesCtr(
            hex("2b7e151628aed2a6abf7158809cf4f3c"),
            hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"),
            hex("6bc1bee22e409f96e93d"),
        )
        assertContentEquals(hex("874d6191b620e3261bef"), partial)
    }

    @Test
    fun keyParsing() {
        assertIs<KeyParse.Valid>(VictronKey.parse("  " + Vectors.SOLAR_KEY.uppercase() + "\n"))
        assertEquals(KeyParse.Invalid(KeyParse.Reason.WRONG_LENGTH), VictronKey.parse(Vectors.SOLAR_KEY.dropLast(1)))
        assertEquals(KeyParse.Invalid(KeyParse.Reason.WRONG_LENGTH), VictronKey.parse(Vectors.SOLAR_KEY + "00"))
        assertEquals(KeyParse.Invalid(KeyParse.Reason.WRONG_LENGTH), VictronKey.parse(""))
        assertEquals(
            KeyParse.Invalid(KeyParse.Reason.NOT_HEXADECIMAL),
            VictronKey.parse("zz" + Vectors.SOLAR_KEY.drop(2)),
        )
        assertEquals(
            KeyParse.Invalid(KeyParse.Reason.NOT_HEXADECIMAL),
            VictronKey.parse("0x" + Vectors.SOLAR_KEY.drop(2)),
        )
        assertEquals(0xAD, keyOf(Vectors.SOLAR_KEY).checkByte)
    }

    @Test
    fun keyNeverShowsItselfInTextForms() {
        val key = keyOf(Vectors.SOLAR_KEY)
        assertEquals("VictronKey(***)", key.toString())
        val parsed = VictronKey.parse(Vectors.SOLAR_KEY)
        assertFalse(parsed.toString().contains(Vectors.SOLAR_KEY))
        assertFalse(parsed.toString().contains("adec", ignoreCase = true))
        val exported = key.exportBytes()
        exported.fill(0)
        assertEquals(0xAD, key.checkByte, "exportBytes renvoie une copie")
    }
}
