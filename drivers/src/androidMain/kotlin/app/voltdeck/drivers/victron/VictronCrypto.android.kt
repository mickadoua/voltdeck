/*
 * Source du format : Victron Energy, « Extra manufacturer Data », version imprimée le 2022-12-14,
 * https://communityarchive.victronenergy.com/storage/attachments/extra-manufacturer-data-2022-12-14.pdf
 * Vecteurs de test : keshavdv/victron-ble (Unlicense), dossier tests/, et rajlaud/victron-ble-ha-parser (Apache-2.0),
 * tests/. Aucun code d'esphome-victron_ble (GPL-3.0) ni victron-hacs (sans licence) n'est utilisé.
 */

package app.voltdeck.drivers.victron

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal actual fun platformAesCtr(key: ByteArray, counterBlock: ByteArray, data: ByteArray): ByteArray {
    require(key.size == 16 && counterBlock.size == 16 && data.size <= 16) { "AES-128-CTR : un seul bloc" }
    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(counterBlock))
    return cipher.doFinal(data)
}
