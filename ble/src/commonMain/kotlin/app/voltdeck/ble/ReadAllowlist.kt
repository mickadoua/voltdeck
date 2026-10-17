package app.voltdeck.ble

/**
 * Liste blanche des seules trames que Voltdeck a le droit d'émettre vers un équipement.
 *
 * Voltdeck est en lecture seule (VOL-1, §4.3) : une trame hors de cette liste n'est jamais envoyée.
 * Chaque entrée est une trame constante, sans donnée variable, et cite sa source.
 */
sealed class AllowedCommand private constructor(val label: String, frame: ByteArray) {
    private val bytes: ByteArray = frame.copyOf()

    /** Copie des octets : l'appelant ne peut pas modifier la trame autorisée. */
    fun frame(): ByteArray = bytes.copyOf()

    override fun toString(): String = "AllowedCommand($label)"

    /**
     * JBD registre 0x03, infos de base (tension, courant, capacités, cycles, protections, SOC, FET, NTC).
     * Requête `DD A5 <reg> <len> <chk_hi> <chk_lo> 77` avec chk = 0x10000 - (reg + len) = 0xFFFD.
     * Sources : syssi/esphome-jbd-bms (Apache-2.0), components/jbd_bms_ble/jbd_bms_ble.cpp ;
     * patman15/aiobmsble (Apache-2.0), aiobmsble/bms/jbd_bms.py ;
     * somme de contrôle : https://blog.jklr.org/2020/02/07/ltt-power-bms-chinese-protocol.html
     */
    data object JbdBasicInfo : AllowedCommand(
        "jbd.0x03.basic_info",
        byteArrayOf(0xDD.toByte(), 0xA5.toByte(), 0x03, 0x00, 0xFF.toByte(), 0xFD.toByte(), 0x77),
    )

    /**
     * JBD registre 0x04, tension de chaque cellule (2 octets, mV). chk = 0x10000 - 0x04 = 0xFFFC.
     * Sources : mêmes que [JbdBasicInfo].
     */
    data object JbdCellVoltages : AllowedCommand(
        "jbd.0x04.cell_voltages",
        byteArrayOf(0xDD.toByte(), 0xA5.toByte(), 0x04, 0x00, 0xFF.toByte(), 0xFC.toByte(), 0x77),
    )

    /**
     * JBD registre 0x05, version matérielle (ASCII), sert à confirmer l'identification. chk = 0x10000 - 0x05 = 0xFFFB.
     * Sources : mêmes que [JbdBasicInfo].
     */
    data object JbdHardwareVersion : AllowedCommand(
        "jbd.0x05.hardware_version",
        byteArrayOf(0xDD.toByte(), 0xA5.toByte(), 0x05, 0x00, 0xFF.toByte(), 0xFB.toByte(), 0x77),
    )
}

/** Levée quand une trame hors liste blanche allait être émise. Rien n'a été envoyé. */
class ForbiddenCommandException(message: String) : RuntimeException(message)

object ReadAllowlist {
    val commands: List<AllowedCommand> = listOf(
        AllowedCommand.JbdBasicInfo,
        AllowedCommand.JbdCellVoltages,
        AllowedCommand.JbdHardwareVersion,
    )

    fun isAllowed(frame: ByteArray): Boolean = commands.any { it.frame().contentEquals(frame) }

    /** Comparaison à égalité d'octets avec les trames constantes ci-dessus, jamais avec la commande reçue. */
    fun requireAllowed(frame: ByteArray) {
        if (!isAllowed(frame)) {
            throw ForbiddenCommandException(
                "Trame hors liste blanche (${frame.size} octets, début ${frame.preview()}) : non envoyée",
            )
        }
    }

    private fun ByteArray.preview(): String =
        take(4).joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
}
