package app.voltdeck.drivers.jbd

/**
 * Trames de réponse du BMS JBD / Xiaoxiang : `DD <registre> <statut> <longueur> <données> <chk 2 o> 77`.
 *
 * Sources (décodeurs réécrits ici, rien n'est copié) :
 *  - patman15/aiobmsble (Apache-2.0), aiobmsble/bms/jbd_bms.py : bornes de trame, somme sur statut..données ;
 *  - syssi/esphome-jbd-bms (Apache-2.0), components/jbd_bms_ble/jbd_bms_ble.cpp ;
 *  - somme de contrôle : https://blog.jklr.org/2020/02/07/ltt-power-bms-chinese-protocol.html
 *
 * Ce fichier ne sait que lire : aucune fabrication de trame vers le BMS (seules les trames de
 * `app.voltdeck.ble.ReadAllowlist` partent, et uniquement via `ReadOnlyGattLink.request`).
 */
internal object JbdProtocol {
    const val START: Int = 0xDD
    const val END: Int = 0x77
    const val REG_BASIC_INFO: Int = 0x03
    const val REG_CELL_VOLTAGES: Int = 0x04
    const val REG_HARDWARE_VERSION: Int = 0x05

    /** DD + registre + statut + longueur + 2 octets de somme + 77. */
    const val OVERHEAD: Int = 7

    /** `0x10000 − Σ(statut, longueur, données)`, sur 16 bits. */
    fun checksum(bytes: ByteArray, from: Int, until: Int): Int {
        var sum = 0
        for (i in from until until) sum += bytes[i].toInt() and 0xFF
        return (0x10000 - sum) and 0xFFFF
    }
}

/** Réponse valide (marqueurs et somme de contrôle justes). `status != 0` : le BMS a refusé la requête. */
class JbdResponse(val register: Int, val status: Int, data: ByteArray) {
    val data: ByteArray = data.copyOf()

    val isOk: Boolean get() = status == 0

    override fun toString(): String = "JbdResponse(register=0x${register.toString(16)}, status=$status, size=${data.size})"
}

/**
 * Réassemble les réponses à partir des notifications BLE, qui découpent une trame en morceaux (20 octets
 * en général). Une trame dont la fin, la longueur ou la somme de contrôle est fausse est écartée et comptée
 * dans [rejectedFrames] : elle ne produit jamais de [JbdResponse]. Non thread-safe.
 */
class JbdFrameAssembler {
    private var buffer = ByteArray(0)

    /** Nombre de trames ou de séries d'octets écartées depuis la création. */
    var rejectedFrames: Int = 0
        private set

    private var discarding = false

    fun reset() {
        buffer = ByteArray(0)
        discarding = false
    }

    /** Une série d'octets écartés d'affilée ne compte qu'une fois, jusqu'à la prochaine trame valide. */
    private fun reject() {
        if (!discarding) rejectedFrames++
        discarding = true
    }

    fun feed(chunk: ByteArray): List<JbdResponse> {
        buffer += chunk
        val out = mutableListOf<JbdResponse>()
        while (true) {
            val start = buffer.indexOfFirst { (it.toInt() and 0xFF) == JbdProtocol.START }
            if (start < 0) {
                if (buffer.isNotEmpty()) reject()
                buffer = ByteArray(0)
                break
            }
            if (start > 0) {
                reject()
                buffer = buffer.copyOfRange(start, buffer.size)
            }
            if (buffer.size < 4) break
            val total = (buffer[3].toInt() and 0xFF) + JbdProtocol.OVERHEAD
            if (buffer.size < total) break
            val response = parse(buffer.copyOfRange(0, total))
            if (response == null) {
                // Ce DD n'ouvrait pas une vraie trame : ne jeter que lui, la vraie trame peut suivre dans le tampon.
                reject()
                buffer = buffer.copyOfRange(1, buffer.size)
            } else {
                discarding = false
                out += response
                buffer = buffer.copyOfRange(total, buffer.size)
            }
        }
        return out
    }

    private fun parse(frame: ByteArray): JbdResponse? {
        val total = frame.size
        if ((frame[total - 1].toInt() and 0xFF) != JbdProtocol.END) return null
        val received = ((frame[total - 3].toInt() and 0xFF) shl 8) or (frame[total - 2].toInt() and 0xFF)
        if (received != JbdProtocol.checksum(frame, 2, total - 3)) return null
        return JbdResponse(
            register = frame[1].toInt() and 0xFF,
            status = frame[2].toInt() and 0xFF,
            data = frame.copyOfRange(4, total - 3),
        )
    }
}
