/*
 * Source du format : Victron Energy, « Extra manufacturer Data », version imprimée le 2022-12-14,
 * https://communityarchive.victronenergy.com/storage/attachments/extra-manufacturer-data-2022-12-14.pdf
 * Vecteurs de test : keshavdv/victron-ble (Unlicense) et rajlaud/victron-ble-ha-parser (Apache-2.0).
 * Aucun code d'esphome-victron_ble (GPL-3.0) ni victron-hacs (sans licence) n'est utilisé.
 */

package app.voltdeck.simulator

import app.voltdeck.core.Metric
import app.voltdeck.core.Sample
import app.voltdeck.drivers.victron.VictronCrypto
import app.voltdeck.drivers.victron.VictronKey
import app.voltdeck.drivers.victron.VictronRecordType
import kotlin.math.roundToLong

/**
 * Encode des mesures en donnée constructeur Victron « Product Advertisement » chiffrée, avec le vrai AES-CTR du
 * pilote. C'est l'inverse de `VictronRecords` : mêmes positions de bits, mêmes valeurs « non disponible » (NA).
 * Une mesure absente de [samples] est écrite avec la valeur NA de son champ, jamais avec 0.
 *
 * Disposition : `10 00 | modèle (2) | type | nonce (2) | octet 0 de la clé | charge utile chiffrée`.
 */
object VictronAdvertisementEncoder {
    private const val PRODUCT_ADVERTISEMENT = 0x10
    private const val KELVIN_OFFSET = 273.15

    fun encode(type: VictronRecordType, modelId: Int, nonce: Int, key: VictronKey, samples: List<Sample>): ByteArray {
        val plain = payload(type, samples.associate { it.metric to it.value })
        val encrypted = VictronCrypto.encryptFirstBlock(key, nonce, plain)
        val header = byteArrayOf(
            PRODUCT_ADVERTISEMENT.toByte(), 0,
            modelId.toByte(), (modelId shr 8).toByte(),
            type.code.toByte(),
            nonce.toByte(), (nonce shr 8).toByte(),
            key.checkByte.toByte(),
        )
        return header + encrypted
    }

    private fun payload(type: VictronRecordType, v: Map<Metric, Double>): ByteArray {
        val w = BitWriter()
        when (type) {
            VictronRecordType.SOLAR_CHARGER -> {
                w.put(unsigned(v[Metric.DEVICE_STATE], 8, 1.0), 8)
                w.put(unsigned(v[Metric.ERROR_CODE], 8, 1.0), 8)
                w.put(signed(v[Metric.BATTERY_VOLTAGE], 16, 100.0), 16)
                w.put(signed(v[Metric.BATTERY_CURRENT], 16, 10.0), 16)
                w.put(unsigned(v[Metric.YIELD_TODAY], 16, 100.0), 16)
                w.put(unsigned(v[Metric.PV_POWER], 16, 1.0), 16)
                w.put(unsigned(v[Metric.LOAD_CURRENT], 9, 10.0), 9)
            }
            VictronRecordType.BATTERY_MONITOR -> {
                w.put(unsigned(v[Metric.TIME_TO_GO], 16, 1.0), 16)
                w.put(signed(v[Metric.BATTERY_VOLTAGE], 16, 100.0), 16)
                w.put((v[Metric.ALARM_REASON] ?: 0.0).roundToLong(), 16)
                val temperature = v[Metric.TEMPERATURE]
                val auxInput = if (temperature != null) AUX_TEMPERATURE else AUX_NONE
                w.put(if (temperature != null) unsigned(temperature + KELVIN_OFFSET, 16, 100.0) else 0xFFFFL, 16)
                w.put(auxInput, 2)
                w.put(signed(v[Metric.BATTERY_CURRENT], 22, 1000.0), 22)
                // Le PDF : Ah consommés = −(valeur de l'enregistrement).
                w.put(unsigned(v[Metric.CONSUMED_AH]?.let { 0.0 - it }, 20, 10.0), 20)
                w.put(unsigned(v[Metric.SOC], 10, 10.0), 10)
            }
            VictronRecordType.DCDC_CONVERTER -> {
                w.put(unsigned(v[Metric.DEVICE_STATE], 8, 1.0), 8)
                w.put(unsigned(v[Metric.ERROR_CODE], 8, 1.0), 8)
                w.put(unsigned(v[Metric.INPUT_VOLTAGE], 16, 100.0), 16)
                w.put(signed(v[Metric.OUTPUT_VOLTAGE], 16, 100.0), 16)
                w.put((v[Metric.OFF_REASON] ?: 0.0).roundToLong(), 32)
            }
        }
        return w.toBytes(type.minPayloadBytes)
    }

    private const val AUX_TEMPERATURE = 2L
    private const val AUX_NONE = 3L

    /** NA des champs non signés : tous les bits à 1. */
    private fun unsigned(value: Double?, bits: Int, scale: Double): Long {
        val max = (1L shl bits) - 1
        if (value == null) return max
        val raw = (value * scale).roundToLong().coerceIn(0L, max)
        return if (raw == max) max - 1 else raw
    }

    /** NA des champs signés 16 bits : 0x7FFF ; 22 bits : 0x3FFFFF (voir `VictronRecords`). */
    private fun signed(value: Double?, bits: Int, scale: Double): Long {
        val mask = (1L shl bits) - 1
        val na = if (bits == 22) mask else (1L shl (bits - 1)) - 1
        if (value == null) return na
        val raw = (value * scale).roundToLong().coerceIn(-(1L shl (bits - 1)), (1L shl (bits - 1)) - 1)
        val encoded = raw and mask
        return if (encoded == na) (encoded - 1) and mask else encoded
    }
}
