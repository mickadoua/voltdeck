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

    /**
     * Octets écrits, complétés par des zéros jusqu'à [minBytes] au moins. Comme le prescrit le PDF Victron, les bits
     * inutilisés du dernier octet écrit sont mis à 1.
     */
    fun toBytes(minBytes: Int): ByteArray {
        val written = bytes.toByteArray()
        if (bitCount % 8 != 0) written[written.size - 1] = (written.last().toInt() or (0xFF shl (bitCount % 8))).toByte()
        return written.copyOf(maxOf(written.size, minBytes))
    }
}
