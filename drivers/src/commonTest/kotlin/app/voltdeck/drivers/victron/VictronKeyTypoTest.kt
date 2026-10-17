// TEST ONLY — les clés et annonces ci-dessous sont des vecteurs de test publics, pas des secrets.
//
// Sources du format : Victron Energy, « Extra manufacturer Data », version du 2022-12-14,
// https://communityarchive.victronenergy.com/storage/attachments/extra-manufacturer-data-2022-12-14.pdf
// Sources des vecteurs : keshavdv/victron-ble (Unlicense), tests/ ; rajlaud/victron-ble-ha-parser (Apache-2.0), tests/.
// Les charges utiles modifiées ci-dessous sont dérivées de ces vecteurs ; elles servent à sortir une borne de sa plage.

package app.voltdeck.drivers.victron

import app.voltdeck.ble.Adv
import app.voltdeck.ble.ConnectionPolicy
import app.voltdeck.ble.PlatformDeviceId
import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VictronKeyTypoTest {
    private class Vector(val name: String, val adv: String, val key: String, val role: DeviceRole)

    private val vectors = listOf(
        Vector("MPPT", Vectors.SOLAR_ADV, Vectors.SOLAR_KEY, DeviceRole.SOLAR_CHARGER),
        Vector("SmartShunt", Vectors.BATTERY_MONITOR_ADV, Vectors.BATTERY_MONITOR_KEY, DeviceRole.SHUNT),
        Vector("Orion", Vectors.DCDC_ADV, Vectors.DCDC_KEY, DeviceRole.DCDC),
    )

    /** Remplace le dernier chiffre hexadécimal de l'octet [byteIndex] : une coquille, octet de contrôle (0) intact. */
    private fun typo(keyHex: String, byteIndex: Int): String {
        val at = 2 * byteIndex + 1
        val flipped = if (keyHex[at] == '0') '1' else '0'
        return keyHex.substring(0, at) + flipped + keyHex.substring(at + 1)
    }

    @Test
    fun theUntouchedReferenceKeysStillDecode() {
        vectors.forEach { v -> assertIs<VictronDecodeResult.Decoded>(VictronDecoder.decode(hex(v.adv), keyOf(v.key)), v.name) }
    }

    @Test
    fun aTypoOnByte5_10_or15IsWrongKeyOnEachReferenceVector() {
        for (v in vectors) {
            for (byteIndex in listOf(5, 10, 15)) {
                val key = keyOf(typo(v.key, byteIndex))
                assertEquals(v.key.take(2), typo(v.key, byteIndex).take(2), "l'octet de contrôle n'a pas bougé")
                assertEquals(VictronDecodeResult.WrongKey, VictronDecoder.decode(hex(v.adv), key), "${v.name}, octet $byteIndex")
                assertEquals(VictronDecoder.KeyCheck.Mismatch, VictronDecoder.checkKey(hex(v.adv), key), "${v.name}, octet $byteIndex")
            }
        }
    }

    /**
     * Sur une trame isolée, l'Orion n'a presque que deux tensions à contrôler : une coquille peut y passer. Sur trois
     * trames distinctes, comme à la saisie de la clé, elle est refusée quel que soit l'octet touché.
     */
    @Test
    fun aTypoOnAnyByteAfterTheControlByteIsRefusedOverThreeDistinctFrames() {
        for (v in vectors) {
            val frames = List(3) { withNonce(v.adv, v.key, 0x0200 + 37 * it) }
            assertEquals(VictronDecoder.KeyCheck.Matches, VictronDecoder.checkKey(frames, keyOf(v.key)), v.name)
            for (byteIndex in 1 until VictronKey.SIZE_BYTES) {
                assertEquals(VictronDecoder.KeyCheck.Mismatch, VictronDecoder.checkKey(frames, keyOf(typo(v.key, byteIndex))), "${v.name}, octet $byteIndex")
            }
        }
    }

    @Test
    fun theDriverEmitsNoReadingAndReportsWrongKeyForATypo() = runTest {
        for (v in vectors) {
            val platformId = PlatformDeviceId("typo-${v.name}")
            val device = app.voltdeck.drivers.KnownDevice(DeviceId("device-${v.name}"), platformId, v.role)
            val keys = InMemoryVictronKeyStore().apply { put(device.deviceId, keyOf(typo(v.key, 5))) }
            val driver = VictronInstantReadoutDriver(keys, FixedClock)
            val adv = Adv(platformId, v.name, -60, mapOf(ConnectionPolicy.VICTRON_COMPANY_ID to hex(v.adv)))
            val readings = driver.readings(device, ScriptedTransport(listOf(adv, adv))).toList()
            assertEquals(emptyList(), readings, v.name)
            assertEquals(VictronLinkStatus.WRONG_KEY, driver.diagnostics.status.value[device.deviceId], v.name)
        }
    }

    // --- Bornes de vraisemblance : une charge utile qui sort d'une plage est refusée, même avec la bonne clé. ---

    private val solarPlain = hex("04006c050e000300130000fe")
    private val monitorPlain = hex("ffffe50400000000030000f40140df")
    private val dcdcPlain = hex("00000b05ff7f80000000")

    private fun decodeTampered(type: VictronRecordType, plain: ByteArray, keyHex: String): VictronDecodeResult {
        val key = keyOf(keyHex)
        val nonce = 0x1234
        val header = byteArrayOf(0x10, 0, 0x42, 0xA0.toByte(), type.code.toByte(), nonce.toByte(), (nonce shr 8).toByte(), key.checkByte.toByte())
        return VictronDecoder.decode(header + VictronCrypto.encryptFirstBlock(key, nonce, plain), key)
    }

    private fun ByteArray.with(index: Int, value: Int) = copyOf().also { it[index] = value.toByte() }

    @Test
    fun theUntamperedPlainPayloadsAreAccepted() {
        assertIs<VictronDecodeResult.Decoded>(decodeTampered(VictronRecordType.SOLAR_CHARGER, solarPlain, Vectors.SOLAR_KEY))
        assertIs<VictronDecodeResult.Decoded>(decodeTampered(VictronRecordType.BATTERY_MONITOR, monitorPlain, Vectors.BATTERY_MONITOR_KEY))
        assertIs<VictronDecodeResult.Decoded>(decodeTampered(VictronRecordType.DCDC_CONVERTER, dcdcPlain, Vectors.DCDC_KEY))
    }

    private fun assertWrong(type: VictronRecordType, plain: ByteArray, keyHex: String, why: String) =
        assertEquals(VictronDecodeResult.WrongKey, decodeTampered(type, plain, keyHex), why)

    @Test
    fun solarBoundsAreEnforced() {
        val k = Vectors.SOLAR_KEY
        assertWrong(VictronRecordType.SOLAR_CHARGER, solarPlain.with(3, 0x1B).with(2, 0x59), k, "tension batterie 70,01 V")
        assertWrong(VictronRecordType.SOLAR_CHARGER, solarPlain.with(3, 0xFF).with(2, 0xF0), k, "tension batterie négative")
        assertWrong(VictronRecordType.SOLAR_CHARGER, solarPlain.with(5, 0x20).with(4, 0), k, "courant 819,2 A")
        assertWrong(VictronRecordType.SOLAR_CHARGER, solarPlain.with(9, 0x7F).with(8, 0xFF), k, "puissance PV 32767 W")
        assertWrong(VictronRecordType.SOLAR_CHARGER, solarPlain.with(11, 0x00), k, "bits inutilisés à zéro")
    }

    @Test
    fun batteryMonitorBoundsAreEnforced() {
        val k = Vectors.BATTERY_MONITOR_KEY
        assertWrong(VictronRecordType.BATTERY_MONITOR, monitorPlain.with(3, 0x1B).with(2, 0x59), k, "tension batterie 70,01 V")
        assertWrong(VictronRecordType.BATTERY_MONITOR, monitorPlain.with(14, 0xFF).with(13, 0x20), k, "SOC 101,0 %")
        assertWrong(VictronRecordType.BATTERY_MONITOR, monitorPlain.with(14, 0x1F), k, "bits inutilisés à zéro")
        assertWrong(VictronRecordType.BATTERY_MONITOR, monitorPlain.with(8, 0xA3).with(9, 0xFD).with(10, 0x7F), k, "courant 2097 A")
    }

    @Test
    fun dcdcBoundsAreEnforced() {
        val k = Vectors.DCDC_KEY
        assertWrong(VictronRecordType.DCDC_CONVERTER, dcdcPlain.with(3, 0x7F).with(2, 0x00), k, "tension d'entrée > 100 V")
        assertWrong(VictronRecordType.DCDC_CONVERTER, dcdcPlain.with(5, 0x1B).with(4, 0x59), k, "tension de sortie 70,01 V")
    }

    /** Codes d'erreur et état d'une vraie installation, refusés à tort avant VOL-15 (source : keshavdv/victron-ble, devices/base.py). */
    @Test
    fun everyKnownStateAndErrorCodeIsAccepted() {
        val errors = listOf(11, 14, 22, 23, 24) + (40..43) + (50..58) + (69..71) + (80..87) + listOf(205, 212, 215)
        for (code in errors) {
            assertIs<VictronDecodeResult.Decoded>(decodeTampered(VictronRecordType.SOLAR_CHARGER, solarPlain.with(1, code), Vectors.SOLAR_KEY), "MPPT, erreur $code")
            assertIs<VictronDecodeResult.Decoded>(decodeTampered(VictronRecordType.DCDC_CONVERTER, dcdcPlain.with(1, code), Vectors.DCDC_KEY), "Orion, erreur $code")
        }
        assertIs<VictronDecodeResult.Decoded>(decodeTampered(VictronRecordType.SOLAR_CHARGER, solarPlain.with(0, 249), Vectors.SOLAR_KEY), "MPPT, état 249")
        assertIs<VictronDecodeResult.Decoded>(decodeTampered(VictronRecordType.DCDC_CONVERTER, dcdcPlain.with(0, 249), Vectors.DCDC_KEY), "Orion, état 249")
    }

    @Test
    fun anExtendedRecordIsNotRejectedForItsUnusedBits() {
        val extended = solarPlain.with(11, 0x00) + byteArrayOf(0x55, 0x55)
        assertIs<VictronDecodeResult.Decoded>(decodeTampered(VictronRecordType.SOLAR_CHARGER, extended, Vectors.SOLAR_KEY))
    }

    @Test
    fun everyDocumentedStateIsAccepted() {
        assertTrue(listOf(0, 1, 2, 3, 4, 5, 6, 7, 9, 11, 245, 246, 247, 248, 252).all { state ->
            decodeTampered(VictronRecordType.SOLAR_CHARGER, solarPlain.with(0, state), Vectors.SOLAR_KEY) is VictronDecodeResult.Decoded
        })
    }
}
