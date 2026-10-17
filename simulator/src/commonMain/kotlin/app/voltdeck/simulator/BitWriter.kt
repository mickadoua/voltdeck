package app.voltdeck.simulator

/** Écriture de champs de bits, du bit de poids faible au bit de poids fort : l'inverse exact de `BitReader` du pilote. */
internal class BitWriter {
    private val bytes = ArrayList<Byte>()
    private var bitCount = 0

    fun put(value: Long, bits: Int) {
        require(bits in 1..32) { "largeur de champ hors limites" }
        for (position in 0 until bits) {
            if (bitCount % 8 == 0) bytes.add(0)
            val bit = ((value shr position) and 1L).toInt()
            bytes[bitCount / 8] = (bytes[bitCount / 8].toInt() or (bit shl (bitCount % 8))).toByte()
            bitCount++
        }
    }

    /** Octets écrits, complétés par des zéros jusqu'à [minBytes] au moins. */
    fun toBytes(minBytes: Int): ByteArray = bytes.toByteArray().copyOf(maxOf(bytes.size, minBytes))
}
