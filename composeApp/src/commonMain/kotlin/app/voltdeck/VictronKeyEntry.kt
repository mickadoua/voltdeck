package app.voltdeck

import app.voltdeck.core.DeviceId
import app.voltdeck.drivers.victron.KeyParse
import app.voltdeck.drivers.victron.VictronDecoder
import app.voltdeck.drivers.victron.VictronKey
import app.voltdeck.drivers.victron.VictronKeyStore

sealed interface KeyEntryOutcome {
    data object Saved : KeyEntryOutcome

    /** [message] est affichable tel quel : il ne répète jamais le texte saisi. */
    data class Rejected(val message: String) : KeyEntryOutcome
}

const val KEY_FORMAT_MESSAGE = "La clé fait 32 caractères hexadécimaux (16 octets) : chiffres 0 à 9 et lettres A à F."
const val KEY_NOT_HEX_MESSAGE = "La clé ne contient que des chiffres 0 à 9 et des lettres A à F."
const val KEY_UNVERIFIABLE_MESSAGE =
    "Aucune annonce reçue de cet appareil : la clé ne peut pas être vérifiée. Rapprochez-vous de l'appareil et réessayez."

/**
 * Saisie d'une clé d'annonce Victron : format (32 hexadécimaux), puis octet de contrôle comparé à la dernière annonce
 * reçue de l'appareil ([latestAdvertisement], donnée constructeur Victron), et seulement alors enregistrement dans [keys].
 * La clé saisie n'est ni répétée dans un message ni journalisée.
 */
fun enterVictronKey(
    device: DeviceId,
    text: String,
    latestAdvertisement: ByteArray?,
    keys: VictronKeyStore,
): KeyEntryOutcome {
    val key = when (val parsed = VictronKey.parse(text)) {
        is KeyParse.Invalid -> return KeyEntryOutcome.Rejected(
            if (parsed.reason == KeyParse.Reason.WRONG_LENGTH) KEY_FORMAT_MESSAGE else KEY_NOT_HEX_MESSAGE,
        )
        is KeyParse.Valid -> parsed.key
    }
    if (latestAdvertisement == null) return KeyEntryOutcome.Rejected(KEY_UNVERIFIABLE_MESSAGE)
    return when (VictronDecoder.checkKey(latestAdvertisement, key)) {
        VictronDecoder.KeyCheck.Matches -> {
            keys.put(device, key)
            KeyEntryOutcome.Saved
        }
        VictronDecoder.KeyCheck.Mismatch -> KeyEntryOutcome.Rejected(VictronDecoder.WRONG_KEY_MESSAGE)
        VictronDecoder.KeyCheck.NoUsableAdvertisement -> KeyEntryOutcome.Rejected(KEY_UNVERIFIABLE_MESSAGE)
    }
}
