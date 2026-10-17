/*
 * Source du format : Victron Energy, « Extra manufacturer Data », version imprimée le 2022-12-14,
 * https://communityarchive.victronenergy.com/storage/attachments/extra-manufacturer-data-2022-12-14.pdf
 * Vecteurs de test : keshavdv/victron-ble (Unlicense), dossier tests/, et rajlaud/victron-ble-ha-parser (Apache-2.0),
 * tests/. Aucun code d'esphome-victron_ble (GPL-3.0) ni victron-hacs (sans licence) n'est utilisé.
 */

package app.voltdeck.drivers.victron

import app.voltdeck.core.Sample

/** Issue du décodage d'une annonce. Aucune variante ne transporte d'octet de clé ni de charge utile en clair. */
sealed interface VictronDecodeResult {
    /** Annonce décodée. [samples] ne contient que les champs disponibles. */
    class Decoded(val type: VictronRecordType, val modelId: Int, val samples: List<Sample>) : VictronDecodeResult {
        override fun toString(): String = "Decoded(type=$type, samples=${samples.size})"
    }

    /** Pas une annonce Victron « Product Advertisement » (premier octet différent de 0x10) : ignorée, pas une erreur. */
    data object NotProductAdvertisement : VictronDecodeResult

    /** Type d'enregistrement que Voltdeck ne décode pas (onduleur, SmartLithium, Orion XS…) : ignoré, pas une erreur. */
    data class UnsupportedRecord(val code: Int) : VictronDecodeResult

    /** Trame trop courte pour son type : ignorée, à compter comme erreur de trame. */
    data object Truncated : VictronDecodeResult

    /**
     * Mauvaise clé : l'octet de contrôle ne correspond pas (rien n'est déchiffré), ou il correspond mais la charge
     * utile déchiffrée est invraisemblable (coquille ailleurs que sur l'octet 0 de la clé). Aucune mesure n'est émise.
     */
    data object WrongKey : VictronDecodeResult
}

object VictronDecoder {
    /** Message affiché à l'utilisateur quand la clé ne correspond pas. Ne contient jamais la clé. */
    const val WRONG_KEY_MESSAGE =
        "Clé refusée : elle ne correspond pas à cet appareil. Recopiez-la depuis VictronConnect " +
            "(Infos produit, Lecture instantanée via Bluetooth). Elle change si le code PIN Bluetooth change."

    /** [manufacturerData] : donnée constructeur du Company ID 0x02E1, sans le Company ID. */
    fun decode(manufacturerData: ByteArray, key: VictronKey): VictronDecodeResult {
        if (!VictronHeader.isProductAdvertisement(manufacturerData)) return VictronDecodeResult.NotProductAdvertisement
        val header = VictronHeader.parse(manufacturerData) ?: return VictronDecodeResult.Truncated
        val type = header.type ?: return VictronDecodeResult.UnsupportedRecord(header.recordCode)
        if (header.payload.size < type.minPayloadBytes) return VictronDecodeResult.Truncated
        if (header.keyCheckByte != key.checkByte) return VictronDecodeResult.WrongKey
        val plain = VictronCrypto.decryptFirstBlock(key, header.nonce, header.payload)
        return try {
            val samples = VictronRecords.decode(type, plain)
            if (!VictronPlausibility.isPlausible(type, plain, samples)) return VictronDecodeResult.WrongKey
            VictronDecodeResult.Decoded(type, header.modelId, samples)
        } finally {
            plain.fill(0)
        }
    }

    /**
     * Vérifie une clé saisie contre une annonce déjà reçue, avant de l'enregistrer : octet de contrôle, puis
     * déchiffrement d'essai et contrôle de vraisemblance (l'octet de contrôle seul laisse passer toute coquille
     * hors de l'octet 0). Sur une seule trame, une mauvaise clé passe encore parfois (de l'ordre de 1 % pour un
     * SmartShunt) : le pilote exige donc plusieurs trames plausibles d'affilée avant de déclarer la clé bonne.
     */
    fun checkKey(manufacturerData: ByteArray, key: VictronKey): KeyCheck = checkKey(listOf(manufacturerData), key)

    /** Comme [checkKey] sur plusieurs annonces distinctes : une seule invraisemblable suffit à refuser la clé. */
    fun checkKey(advertisements: List<ByteArray>, key: VictronKey): KeyCheck {
        val results = advertisements.map { decode(it, key) }
        return when {
            results.any { it == VictronDecodeResult.WrongKey } -> KeyCheck.Mismatch
            results.any { it is VictronDecodeResult.Decoded } -> KeyCheck.Matches
            else -> KeyCheck.NoUsableAdvertisement
        }
    }

    enum class KeyCheck { Matches, Mismatch, NoUsableAdvertisement }
}
