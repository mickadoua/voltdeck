package app.voltdeck.drivers.victron

/**
 * Clé d'annonce Victron (16 octets, propre à chaque appareil). Secret : [toString] ne la montre jamais, elle ne va
 * ni dans un journal, ni dans SQLite (VOL-1 §4.2). Le stockage est confié à un [VictronKeyStore].
 *
 * Source : Victron, « Extra manufacturer Data » (2022-12-14), voir [VictronAdvertisement].
 */
class VictronKey private constructor(private val bytes: ByteArray) {
    /** Octet 0 de la clé, repris dans chaque annonce pour vérifier qu'on a la bonne clé. */
    val checkByte: Int get() = bytes[0].toInt() and 0xFF

    /** Copie des octets, pour le chiffrement et pour les magasins de secrets uniquement. */
    fun exportBytes(): ByteArray = bytes.copyOf()

    override fun toString(): String = "VictronKey(***)"

    override fun equals(other: Any?): Boolean = other is VictronKey && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = checkByte

    companion object {
        const val SIZE_BYTES = 16
        const val HEX_LENGTH = SIZE_BYTES * 2

        fun fromBytes(bytes: ByteArray): VictronKey {
            require(bytes.size == SIZE_BYTES) { "Une clé Victron fait $SIZE_BYTES octets" }
            return VictronKey(bytes.copyOf())
        }

        /** 32 caractères hexadécimaux (casse libre), espaces de bord ignorés. Le texte saisi n'est jamais répété. */
        fun parse(text: String): KeyParse {
            val hex = text.trim()
            if (hex.length != HEX_LENGTH) return KeyParse.Invalid(KeyParse.Reason.WRONG_LENGTH)
            val bytes = ByteArray(SIZE_BYTES)
            for (i in 0 until SIZE_BYTES) {
                val high = hexValue(hex[2 * i])
                val low = hexValue(hex[2 * i + 1])
                if (high < 0 || low < 0) return KeyParse.Invalid(KeyParse.Reason.NOT_HEXADECIMAL)
                bytes[i] = ((high shl 4) or low).toByte()
            }
            return KeyParse.Valid(VictronKey(bytes))
        }

        private fun hexValue(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
    }
}

sealed interface KeyParse {
    class Valid(val key: VictronKey) : KeyParse {
        override fun toString(): String = "Valid(***)"
    }

    data class Invalid(val reason: Reason) : KeyParse

    enum class Reason { WRONG_LENGTH, NOT_HEXADECIMAL }
}
