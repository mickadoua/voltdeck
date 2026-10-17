/*
 * Source du format : Victron Energy, « Extra manufacturer Data », version imprimée le 2022-12-14,
 * https://communityarchive.victronenergy.com/storage/attachments/extra-manufacturer-data-2022-12-14.pdf
 * Vecteurs de test : keshavdv/victron-ble (Unlicense), dossier tests/, et rajlaud/victron-ble-ha-parser (Apache-2.0),
 * tests/. Aucun code d'esphome-victron_ble (GPL-3.0) ni victron-hacs (sans licence) n'est utilisé.
 */

package app.voltdeck.drivers.victron

import app.voltdeck.core.Metric
import app.voltdeck.core.Sample

/**
 * Décodage bit à bit des charges utiles déchiffrées. Les « bits de départ » du PDF comptent depuis le début de
 * l'enregistrement ; la charge utile commence au bit 32, donc chaque champ ci-dessous est à (bit PDF − 32).
 *
 * Règle NA : chaque champ a sa propre valeur « non disponible » (colonne « NA value » du PDF), testée sur la
 * valeur brute, avant toute conversion de signe ou d'échelle. « Tout à 1 » ne suffit pas : un signé 16 bits a pour
 * NA 0x7FFF. Un champ NA ne produit aucun [Sample] : il n'est jamais remplacé par 0.
 */
internal object VictronRecords {
    fun decode(type: VictronRecordType, payload: ByteArray): List<Sample> = when (type) {
        VictronRecordType.SOLAR_CHARGER -> solar(BitReader(payload))
        VictronRecordType.BATTERY_MONITOR -> batteryMonitor(BitReader(payload))
        VictronRecordType.DCDC_CONVERTER -> dcdc(BitReader(payload))
        VictronRecordType.DC_ENERGY_METER -> dcEnergyMeter(BitReader(payload))
    }

    // PDF « Solar Charger » : état 32/8, erreur 40/8, V 48/16 s, I 64/16 s, rendement 80/16, PV 96/16, charge 112/9.
    private fun solar(r: BitReader): List<Sample> = buildList {
        field(Metric.DEVICE_STATE, r.unsigned(8), na = 0xFF)
        field(Metric.ERROR_CODE, r.unsigned(8), na = 0xFF)
        signedField(Metric.BATTERY_VOLTAGE, r.unsigned(16), bits = 16, na = 0x7FFF, divisor = 100.0)
        signedField(Metric.BATTERY_CURRENT, r.unsigned(16), bits = 16, na = 0x7FFF, divisor = 10.0)
        field(Metric.YIELD_TODAY, r.unsigned(16), na = 0xFFFF, divisor = 100.0)
        field(Metric.PV_POWER, r.unsigned(16), na = 0xFFFF)
        field(Metric.LOAD_CURRENT, r.unsigned(9), na = 0x1FF, divisor = 10.0)
    }

    // PDF « Battery Monitor » : TTG 32/16, V 48/16 s, alarme 64/16, aux 80/16, type aux 96/2, I 98/22 s,
    // Ah consommés 120/20, SOC 140/10.
    private fun batteryMonitor(r: BitReader): List<Sample> = buildList {
        field(Metric.TIME_TO_GO, r.unsigned(16), na = 0xFFFF)
        signedField(Metric.BATTERY_VOLTAGE, r.unsigned(16), bits = 16, na = 0x7FFF, divisor = 100.0)
        add(Sample(Metric.ALARM_REASON, value = r.unsigned(16).toDouble()))
        val aux = r.unsigned(16)
        val auxInput = r.unsigned(2).toInt()
        signedField(Metric.BATTERY_CURRENT, r.unsigned(22), bits = 22, na = 0x3FFFFF, divisor = 1000.0)
        // Le PDF : Ah consommés = −(valeur de l'enregistrement).
        val consumed = r.unsigned(20)
        if (consumed != 0xFFFFFL) add(Sample(Metric.CONSUMED_AH, value = 0.0 - consumed / 10.0))
        field(Metric.SOC, r.unsigned(10), na = 0x3FF, divisor = 10.0)
        // Entrée auxiliaire : 0 tension, 1 point milieu, 2 température (centi-kelvins), 3 aucune. Le PDF ne donne pas
        // de valeur NA pour ces champs : on écarte celles que la plage publiée exclut (0x7FFF signé, 0xFFFF non signé).
        when (auxInput) {
            0 -> signedField(Metric.AUX_VOLTAGE, aux, bits = 16, na = 0x7FFF, divisor = 100.0)
            1 -> field(Metric.MID_VOLTAGE, aux, na = 0xFFFF, divisor = 100.0)
            2 -> if (aux != 0xFFFFL) add(Sample(Metric.TEMPERATURE, value = aux / 100.0 - KELVIN_OFFSET))
        }
    }

    // PDF « DC/DC Converter » : état 32/8, erreur 40/8, V entrée 48/16, V sortie 64/16 s, raison d'arrêt 80/32.
    private fun dcdc(r: BitReader): List<Sample> = buildList {
        field(Metric.DEVICE_STATE, r.unsigned(8), na = 0xFF)
        field(Metric.ERROR_CODE, r.unsigned(8), na = 0xFF)
        field(Metric.INPUT_VOLTAGE, r.unsigned(16), na = 0xFFFF, divisor = 100.0)
        signedField(Metric.OUTPUT_VOLTAGE, r.unsigned(16), bits = 16, na = 0x7FFF, divisor = 100.0)
        // Le PDF ne définit pas de NA pour la raison d'arrêt (masque de bits).
        add(Sample(Metric.OFF_REASON, value = r.unsigned(32).toDouble()))
    }

    // PDF « DC Energy Meter » : type de compteur 32/16 s, V 48/16 s, alarme 64/16, aux 80/16, type aux 96/2, I 98/22 s.
    // Même disposition que le moniteur de batterie jusqu'au courant ; vecteurs : keshavdv/victron-ble, test_dc_energy_meter.py.
    private fun dcEnergyMeter(r: BitReader): List<Sample> = buildList {
        add(Sample(Metric.METER_TYPE, value = BitReader.toSigned(r.unsigned(16), 16).toDouble()))
        signedField(Metric.BATTERY_VOLTAGE, r.unsigned(16), bits = 16, na = 0x7FFF, divisor = 100.0)
        add(Sample(Metric.ALARM_REASON, value = r.unsigned(16).toDouble()))
        val aux = r.unsigned(16)
        val auxInput = r.unsigned(2).toInt()
        signedField(Metric.BATTERY_CURRENT, r.unsigned(22), bits = 22, na = 0x3FFFFF, divisor = 1000.0)
        // Entrée auxiliaire : 0 tension de la batterie de démarrage, 2 température (centi-kelvins), 3 aucune.
        when (auxInput) {
            0 -> signedField(Metric.AUX_VOLTAGE, aux, bits = 16, na = 0x7FFF, divisor = 100.0)
            2 -> if (aux != 0xFFFFL) add(Sample(Metric.TEMPERATURE, value = aux / 100.0 - KELVIN_OFFSET))
        }
    }

    private const val KELVIN_OFFSET = 273.15

    private fun MutableList<Sample>.field(metric: Metric, raw: Long, na: Long, divisor: Double = 1.0) {
        if (raw != na) add(Sample(metric, value = raw / divisor))
    }

    private fun MutableList<Sample>.signedField(metric: Metric, raw: Long, bits: Int, na: Long, divisor: Double) {
        if (raw != na) add(Sample(metric, value = BitReader.toSigned(raw, bits) / divisor))
    }
}
