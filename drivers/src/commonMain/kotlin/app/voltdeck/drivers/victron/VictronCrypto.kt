/*
 * Source du format : Victron Energy, « Extra manufacturer Data », version imprimée le 2022-12-14,
 * https://communityarchive.victronenergy.com/storage/attachments/extra-manufacturer-data-2022-12-14.pdf
 * Vecteurs de test : keshavdv/victron-ble (Unlicense), dossier tests/, et rajlaud/victron-ble-ha-parser (Apache-2.0),
 * tests/. Aucun code d'esphome-victron_ble (GPL-3.0) ni victron-hacs (sans licence) n'est utilisé.
 */

package app.voltdeck.drivers.victron

/** AES-128-CTR de la plateforme (Android `javax.crypto`, iOS CommonCrypto). Aucune dépendance ajoutée. */
internal expect fun platformAesCtr(key: ByteArray, counterBlock: ByteArray, data: ByteArray): ByteArray

/** Public pour que le simulateur chiffre ses annonces avec le même code que celui qui les déchiffre. */
object VictronCrypto {
    const val BLOCK_BYTES = 16

    /**
     * Déchiffre au plus un bloc AES. Bloc compteur : le nonce de 2 octets petit-boutiste dans les octets 0-1, zéros
     * ensuite (même construction que keshavdv/victron-ble : compteur 128 bits petit-boutiste initialisé au nonce).
     * Le PDF précise que tout enregistrement tient en un bloc ; au-delà de 16 octets, l'incrément du compteur n'est
     * pas documenté sans ambiguïté, donc on ne déchiffre pas plus.
     */
    fun decryptFirstBlock(key: VictronKey, nonce: Int, encrypted: ByteArray): ByteArray = applyKeystream(key, nonce, encrypted)

    /** AES-CTR est symétrique : chiffrer est la même opération que déchiffrer. Réservé au simulateur et aux tests. */
    fun encryptFirstBlock(key: VictronKey, nonce: Int, plain: ByteArray): ByteArray = applyKeystream(key, nonce, plain)

    private fun applyKeystream(key: VictronKey, nonce: Int, encrypted: ByteArray): ByteArray {
        val counter = ByteArray(BLOCK_BYTES)
        counter[0] = nonce.toByte()
        counter[1] = (nonce shr 8).toByte()
        val keyBytes = key.exportBytes()
        try {
            return platformAesCtr(keyBytes, counter, encrypted.copyOf(minOf(encrypted.size, BLOCK_BYTES)))
        } finally {
            keyBytes.fill(0)
        }
    }
}
