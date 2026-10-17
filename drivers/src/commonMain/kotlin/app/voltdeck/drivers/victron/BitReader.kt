package app.voltdeck.drivers.victron

/**
 * Lecture de champs de bits, du bit de poids faible au bit de poids fort, dans l'ordre de l'enregistrement.
 *
 * Source : Victron, « Extra manufacturer Data », version du 2022-12-14,
 * https://communityarchive.victronenergy.com/storage/attachments/extra-manufacturer-data-2022-12-14.pdf
 * Le PDF indique les bits de départ et les largeurs ; l'ordre LSB d'abord des champs qui ne tombent pas sur un
 * octet (largeurs 2, 9, 10, 20, 22) est confirmé par les vecteurs de keshavdv/victron-ble (Unlicense).
 */
internal class BitReader(private val data: ByteArray) {
    private var index = 0

    val remainingBits: Int get() = data.size * 8 - index

    fun unsigned(bits: Int): Long {
        require(bits in 1..32) { "largeur de champ hors limites" }
        require(remainingBits >= bits) { "lecture au-delà de l'enregistrement" }
        var value = 0L
        for (position in 0 until bits) {
            val bit = (data[index shr 3].toInt() shr (index and 7)) and 1
            value = value or (bit.toLong() shl position)
            index++
        }
        return value
    }

    companion object {
        /** Complément à deux sur [bits] bits. */
        fun toSigned(raw: Long, bits: Int): Long = if (raw and (1L shl (bits - 1)) != 0L) raw - (1L shl bits) else raw
    }
}
