/*
 * Source du format : Victron Energy, « Extra manufacturer Data », version imprimée le 2022-12-14,
 * https://communityarchive.victronenergy.com/storage/attachments/extra-manufacturer-data-2022-12-14.pdf
 * Aucun code d'esphome-victron_ble (GPL-3.0) ni victron-hacs (sans licence).
 */

package app.voltdeck.drivers.victron

import app.voltdeck.core.Metric
import app.voltdeck.core.Sample

/**
 * Contrôle de vraisemblance d'une charge utile déchiffrée. L'annonce ne porte que l'octet 0 de la clé : une clé
 * dont seul cet octet est juste (coquille ailleurs) déchiffre en valeurs absurdes. Une trame qui ne passe pas ce
 * contrôle est traitée comme une mauvaise clé : aucune mesure n'est émise.
 *
 * Les bornes sont larges (matériel réel 12/24/48 V) : elles écartent le bruit, pas les valeurs rares mais légitimes.
 * L'état et le code d'erreur ne sont pas contrôlés : Victron en ajoute au fil des versions, et une liste fermée
 * refusait de vraies trames avec la bonne clé (VOL-15).
 */
internal object VictronPlausibility {
    fun isPlausible(type: VictronRecordType, plain: ByteArray, samples: List<Sample>): Boolean =
        unusedBitsAreSet(type, plain) && samples.all { plausible(type, it) }

    /**
     * Le PDF : « Unused bits in the last byte of a record should be set ». Contrôlé seulement sur un enregistrement
     * de la longueur publiée : un enregistrement plus long a pu étendre le format et réutiliser ces bits.
     */
    private fun unusedBitsAreSet(type: VictronRecordType, plain: ByteArray): Boolean {
        if (plain.size != type.minPayloadBytes) return true
        val range = when (type) {
            VictronRecordType.SOLAR_CHARGER -> 89 until 96 // bits PDF 121..127, charge utile = bit PDF − 32
            VictronRecordType.BATTERY_MONITOR -> 118 until 120 // bits PDF 150..151
            VictronRecordType.DCDC_CONVERTER, VictronRecordType.DC_ENERGY_METER -> return true // 80 et 88 bits : l'octet est plein
        }
        return range.all { bit -> (plain[bit shr 3].toInt() shr (bit and 7)) and 1 == 1 }
    }

    private fun plausible(type: VictronRecordType, sample: Sample): Boolean {
        val v = sample.value
        return when (sample.metric) {
            Metric.BATTERY_VOLTAGE, Metric.OUTPUT_VOLTAGE, Metric.MID_VOLTAGE -> v in 0.0..MAX_VOLTS
            Metric.INPUT_VOLTAGE -> v in 0.0..MAX_INPUT_VOLTS
            Metric.AUX_VOLTAGE -> v in -1.0..MAX_VOLTS
            Metric.SOC -> v in 0.0..100.0
            Metric.BATTERY_CURRENT ->
                v in -currentLimit(type)..currentLimit(type)
            Metric.PV_POWER -> v in 0.0..MAX_PV_WATTS
            Metric.TEMPERATURE -> v in -60.0..150.0
            Metric.CONSUMED_AH -> v in -MAX_CONSUMED_AH..0.0
            Metric.METER_TYPE -> v in -MAX_METER_TYPE..MAX_METER_TYPE && v != 0.0
            else -> true
        }
    }

    private fun currentLimit(type: VictronRecordType) =
        if (type == VictronRecordType.BATTERY_MONITOR || type == VictronRecordType.DC_ENERGY_METER) MAX_SHUNT_AMPS else MAX_CHARGER_AMPS

    private const val MAX_VOLTS = 70.0
    private const val MAX_INPUT_VOLTS = 100.0
    private const val MAX_CHARGER_AMPS = 500.0
    private const val MAX_SHUNT_AMPS = 2000.0
    private const val MAX_PV_WATTS = 20000.0
    private const val MAX_CONSUMED_AH = 10000.0

    /** Types connus : −9 à 8. Marge pour ceux que Victron ajouterait, sans accepter n'importe quel entier 16 bits. */
    private const val MAX_METER_TYPE = 32.0
}
