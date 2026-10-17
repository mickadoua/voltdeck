/*
 * Source du format : Victron Energy, « Extra manufacturer Data », version imprimée le 2022-12-14,
 * https://communityarchive.victronenergy.com/storage/attachments/extra-manufacturer-data-2022-12-14.pdf
 * Vecteurs de test : keshavdv/victron-ble (Unlicense), dossier tests/, et rajlaud/victron-ble-ha-parser (Apache-2.0),
 * tests/. Aucun code d'esphome-victron_ble (GPL-3.0) ni victron-hacs (sans licence) n'est utilisé.
 */

package app.voltdeck.drivers.victron

import app.voltdeck.core.DeviceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

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

/**
 * Garde en mémoire les clés déjà lues dans [delegate], pour ne pas relire le Keystore/Keychain à chaque annonce
 * (plusieurs par seconde, sur le fil principal). Une absence n'est jamais mémorisée : une panne transitoire du coffre
 * ne doit pas masquer la clé jusqu'au redémarrage. [put] et [remove] mettent le cache à jour, même en cas d'échec.
 * Toute écriture doit passer par cette instance.
 */
class CachingVictronKeyStore(private val delegate: VictronKeyStore) : VictronKeyStore {
    /** [version] change à chaque écriture : une lecture lancée avant une écriture ne doit pas ressusciter l'ancienne clé. */
    private class State(val keys: Map<DeviceId, VictronKey>, val version: Int)

    private val state = MutableStateFlow(State(emptyMap(), 0))

    override fun get(device: DeviceId): VictronKey? {
        val before = state.value
        before.keys[device]?.let { return it }
        val loaded = delegate.get(device) ?: return null
        state.update { if (it.version == before.version) State(it.keys + (device to loaded), it.version) else it }
        return loaded
    }

    override fun put(device: DeviceId, key: VictronKey) {
        try {
            delegate.put(device, key)
        } catch (e: Throwable) {
            state.update { State(it.keys - device, it.version + 1) }
            throw e
        }
        state.update { State(it.keys + (device to key), it.version + 1) }
    }

    override fun remove(device: DeviceId) {
        try {
            delegate.remove(device)
        } finally {
            state.update { State(it.keys - device, it.version + 1) }
        }
    }
}
