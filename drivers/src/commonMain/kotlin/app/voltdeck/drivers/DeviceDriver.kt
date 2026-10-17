package app.voltdeck.drivers

import app.voltdeck.ble.Adv
import app.voltdeck.ble.BleTransport
import app.voltdeck.ble.PlatformDeviceId
import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Reading
import kotlinx.coroutines.flow.Flow

/** Contrat commun des pilotes (VOL-1 §4.1). Un pilote par marque ou modèle, isolé des écrans et du stockage. */
interface DeviceDriver {
    /** Stocké en base, ne change jamais (ex. `jbd.gatt`, `victron.ir`). */
    val driverId: String

    /** Identification passive, sans connexion. `null` si l'annonce n'est pas celle de ce pilote. */
    fun identify(adv: Adv): Candidate?

    /** Flux de mesures. Un trou de communication ne produit aucune mesure : le flux se tait, il n'invente rien. */
    fun readings(device: KnownDevice, transport: BleTransport): Flow<Reading>
}

/** Équipement reconnu dans une annonce, pas encore enregistré. */
class Candidate(
    val driverId: String,
    val role: DeviceRole,
    val platformId: PlatformDeviceId,
    val name: String?,
    /** Indice faible (préfixe d'adresse constructeur, Android seulement) : jamais suffisant pour enregistrer. */
    val addressHint: Boolean = false,
) {
    override fun toString(): String = "Candidate(driverId=$driverId, role=$role, name=$name)"
}

/** Équipement enregistré : son identité locale et son identifiant plateforme (jamais journalisé). */
class KnownDevice(val deviceId: DeviceId, val platformId: PlatformDeviceId) {
    override fun toString(): String = "KnownDevice(deviceId=$deviceId)"
}
