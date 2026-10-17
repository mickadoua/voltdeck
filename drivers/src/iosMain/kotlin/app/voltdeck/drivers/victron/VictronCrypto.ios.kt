/*
 * Source du format : Victron Energy, « Extra manufacturer Data », version imprimée le 2022-12-14,
 * https://communityarchive.victronenergy.com/storage/attachments/extra-manufacturer-data-2022-12-14.pdf
 * Vecteurs de test : keshavdv/victron-ble (Unlicense), dossier tests/, et rajlaud/victron-ble-ha-parser (Apache-2.0),
 * tests/. Aucun code d'esphome-victron_ble (GPL-3.0) ni victron-hacs (sans licence) n'est utilisé.
 */

@file:OptIn(ExperimentalForeignApi::class)

package app.voltdeck.drivers.victron

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCSuccess
import platform.posix.size_tVar

/**
 * CTR sur un seul bloc : le flux de clé est AES-ECB(clé, bloc compteur), puis un XOR. CommonCrypto n'est pas
 * compilable sous Linux : ce fichier est vérifié par le job CI iOS (workflow_dispatch), pas localement.
 */
internal actual fun platformAesCtr(key: ByteArray, counterBlock: ByteArray, data: ByteArray): ByteArray {
    require(key.size == 16 && counterBlock.size == 16 && data.size <= 16) { "AES-128-CTR : un seul bloc" }
    val keystream = ByteArray(16)
    val status = memScoped {
        val moved = alloc<size_tVar>()
        key.usePinned { k ->
            counterBlock.usePinned { input ->
                keystream.usePinned { output ->
                    CCCrypt(
                        kCCEncrypt,
                        kCCAlgorithmAES,
                        kCCOptionECBMode.convert(),
                        k.addressOf(0),
                        16.convert(),
                        null,
                        input.addressOf(0),
                        16.convert(),
                        output.addressOf(0),
                        16.convert(),
                        moved.ptr,
                    )
                }
            }
        }
    }
    check(status == kCCSuccess) { "CommonCrypto a échoué" }
    return ByteArray(data.size) { (data[it].toInt() xor keystream[it].toInt()).toByte() }
}
