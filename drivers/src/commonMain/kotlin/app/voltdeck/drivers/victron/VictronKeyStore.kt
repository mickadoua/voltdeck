/*
 * Source du format : Victron Energy, « Extra manufacturer Data », version imprimée le 2022-12-14,
 * https://communityarchive.victronenergy.com/storage/attachments/extra-manufacturer-data-2022-12-14.pdf
 * Vecteurs de test : keshavdv/victron-ble (Unlicense), dossier tests/, et rajlaud/victron-ble-ha-parser (Apache-2.0),
 * tests/. Aucun code d'esphome-victron_ble (GPL-3.0) ni victron-hacs (sans licence) n'est utilisé.
 */

package app.voltdeck.drivers.victron

import app.voltdeck.core.DeviceId

/**
 * Coffre des clés d'annonce, une par équipement. Les implémentations de production sont adossées au Keystore Android
 * et au Keychain iOS (module `composeApp`). Une clé n'est jamais écrite en SQLite, dans un fichier de configuration
 * ni dans un journal (VOL-1 §4.2).
 */
interface VictronKeyStore {
    fun get(device: DeviceId): VictronKey?

    fun put(device: DeviceId, key: VictronKey)

    fun remove(device: DeviceId)
}

/** Coffre en mémoire : tests et mode démo. Rien n'est persisté. */
class InMemoryVictronKeyStore : VictronKeyStore {
    private val keys = HashMap<DeviceId, VictronKey>()

    override fun get(device: DeviceId): VictronKey? = keys[device]

    override fun put(device: DeviceId, key: VictronKey) {
        keys[device] = key
    }

    override fun remove(device: DeviceId) {
        keys.remove(device)
    }
}
