package app.voltdeck.drivers.jbd

import app.voltdeck.core.Metric
import app.voltdeck.core.Sample

/**
 * Décodage des registres JBD 0x03, 0x04 et 0x05, réécrit en Kotlin à partir des spécifications.
 * Sources :
 *  - patman15/aiobmsble (Apache-2.0), aiobmsble/bms/jbd_bms.py (décalages des champs, températures) ;
 *  - syssi/esphome-jbd-bms (Apache-2.0), components/jbd_bms_ble/jbd_bms_ble.cpp
 *    (`on_basic_info_data_`, tableau `ERRORS`) ;
 *  - sshoecraft/jbdtool (BSD-3-Clause), carte des registres.
 * Tous les décalages ci-dessous comptent à partir du premier octet de données (après DD, registre, statut, longueur).
 */
class JbdDecodeException(message: String) : RuntimeException(message)

/**
 * Registre 0x03. Les mesures hors plage physique sont omises de [samples] (jamais ramenées à 0).
 *
 * Choix de lecture : l'octet d'état des FET (décalage 20) n'est lu que par ses deux bits bas (charge, décharge).
 * esphome-jbd-bms signale un bit 0x80 « unités de 0,1 A / 0,1 Ah » que patman15/aiobmsble ignore ; il n'est pas
 * appliqué ici tant qu'une capture du matériel ne le montre pas (sinon les courants seraient faux d'un facteur 10).
 */
class JbdBasicInfo(
    val voltageV: Double,
    /** Positif = la batterie se charge (convention de Voltdeck, identique à celle du BMS). */
    val currentA: Double,
    val remainingAh: Double,
    val nominalAh: Double,
    val cycles: Int,
    val balanceMask: Long,
    /** Bits de protection actifs, voir [JbdProtectionFlag]. */
    val protectionMask: Int,
    /** Octet de version logicielle brut (0x10 = 1.0). */
    val softwareVersion: Int,
    val socPercent: Int,
    /** Bit 0 = FET de charge fermé, bit 1 = FET de décharge fermé. */
    val fetMask: Int,
    val cellCount: Int,
    val temperaturesC: List<Double>,
) {
    fun toSamples(): List<Sample> = buildList {
        if (voltageV in 0.0..MAX_PACK_VOLTS) add(Sample(Metric.BATTERY_VOLTAGE, value = voltageV))
        add(Sample(Metric.BATTERY_CURRENT, value = currentA))
        if (nominalAh > 0.0) {
            add(Sample(Metric.NOMINAL_CAPACITY, value = nominalAh))
            if (remainingAh <= nominalAh * MAX_OVERFILL) add(Sample(Metric.REMAINING_CAPACITY, value = remainingAh))
        }
        add(Sample(Metric.CYCLES, value = cycles.toDouble()))
        if (socPercent in 0..100) add(Sample(Metric.SOC, value = socPercent.toDouble()))
        add(Sample(Metric.PROTECTION_FLAGS, value = protectionMask.toDouble()))
        add(Sample(Metric.DEVICE_STATE, value = fetMask.toDouble()))
        temperaturesC.forEachIndexed { channel, celsius ->
            if (celsius in MIN_TEMP_C..MAX_TEMP_C) add(Sample(Metric.TEMPERATURE, channel, celsius))
        }
    }

    companion object {
        private const val MIN_DATA = 23
        private const val MAX_PACK_VOLTS = 200.0
        private const val MAX_OVERFILL = 1.2
        private const val MIN_TEMP_C = -60.0
        private const val MAX_TEMP_C = 150.0

        fun decode(data: ByteArray): JbdBasicInfo {
            if (data.size < MIN_DATA) throw JbdDecodeException("registre 0x03 trop court : ${data.size} octets")
            val ntc = data.u8(22)
            if (data.size < MIN_DATA + 2 * ntc) {
                throw JbdDecodeException("registre 0x03 tronqué : $ntc sondes annoncées, ${data.size} octets")
            }
            return JbdBasicInfo(
                voltageV = data.u16(0) / 100.0,
                currentA = data.s16(2) / 100.0,
                remainingAh = data.u16(4) / 100.0,
                nominalAh = data.u16(6) / 100.0,
                cycles = data.u16(8),
                // Deux mots de 16 bits dans l'ordre inverse : cellules 17-32 puis 1-16 (esphome-jbd-bms).
                balanceMask = (data.u16(14).toLong() shl 16) or data.u16(12).toLong(),
                protectionMask = data.u16(16),
                softwareVersion = data.u8(18),
                socPercent = data.u8(19),
                fetMask = data.u8(20) and 0x03,
                cellCount = data.u8(21),
                temperaturesC = List(ntc) { (data.u16(23 + 2 * it) - 2731) / 10.0 },
            )
        }
    }
}

/** Registre 0x04 : tension de chaque cellule, 2 octets big-endian en mV. */
object JbdCellVoltages {
    private const val MIN_VALID_MV = 1
    private const val MAX_VALID_MV = 6000

    /** `null` pour une cellule à 0 mV ou hors plage : son échantillon est omis, le numéro des autres ne bouge pas. */
    fun decode(data: ByteArray, expectedCells: Int): List<Double?> {
        if (expectedCells <= 0) throw JbdDecodeException("nombre de cellules inconnu")
        if (data.size != 2 * expectedCells) {
            throw JbdDecodeException("registre 0x04 : ${data.size} octets pour $expectedCells cellules")
        }
        return List(expectedCells) {
            val mv = data.u16(2 * it)
            if (mv in MIN_VALID_MV..MAX_VALID_MV) mv / 1000.0 else null
        }
    }

    fun toSamples(cells: List<Double?>): List<Sample> =
        cells.mapIndexedNotNull { channel, volts -> volts?.let { Sample(Metric.CELL_VOLTAGE, channel, it) } }
}

/** Registre 0x05 : version matérielle en ASCII, sert à confirmer qu'il s'agit bien d'un BMS JBD. */
object JbdHardwareVersion {
    fun decode(data: ByteArray): String {
        if (data.isEmpty()) throw JbdDecodeException("registre 0x05 vide")
        val chars = data.map { it.toInt() and 0xFF }
        if (chars.any { it !in 0x20..0x7E }) throw JbdDecodeException("registre 0x05 : caractères non ASCII imprimables")
        return chars.joinToString("") { it.toChar().toString() }.trim().ifEmpty {
            throw JbdDecodeException("registre 0x05 : version vide")
        }
    }
}

/**
 * Bits de protection du registre 0x03 (décalage 16). Source : tableau `ERRORS` de syssi/esphome-jbd-bms
 * (Apache-2.0). Les bits 13 à 15 ne sont documentés nulle part : ils restent dans [JbdBasicInfo.protectionMask]
 * mais n'ont pas de nom.
 */
enum class JbdProtectionFlag(val bit: Int) {
    CELL_OVERVOLTAGE(0),
    CELL_UNDERVOLTAGE(1),
    PACK_OVERVOLTAGE(2),
    PACK_UNDERVOLTAGE(3),
    CHARGE_OVER_TEMPERATURE(4),
    CHARGE_UNDER_TEMPERATURE(5),
    DISCHARGE_OVER_TEMPERATURE(6),
    DISCHARGE_UNDER_TEMPERATURE(7),
    CHARGE_OVERCURRENT(8),
    DISCHARGE_OVERCURRENT(9),
    SHORT_CIRCUIT(10),
    FRONT_END_IC_ERROR(11),
    MOSFET_SOFTWARE_LOCK(12),
    ;

    companion object {
        fun active(mask: Int): List<JbdProtectionFlag> = entries.filter { mask and (1 shl it.bit) != 0 }
    }
}

private fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

private fun ByteArray.u16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)

private fun ByteArray.s16(i: Int): Int = u16(i).toShort().toInt()
