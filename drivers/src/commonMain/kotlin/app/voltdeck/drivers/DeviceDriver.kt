package app.voltdeck.drivers

import app.voltdeck.ble.Adv
import app.voltdeck.ble.BleTransport
import app.voltdeck.ble.PlatformDeviceId
import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Reading
import kotlinx.coroutines.flow.Flow

/**
 * Contrat commun des pilotes (VOL-1 §4.1). Un pilote par marque ou modèle, isolé des écrans et du stockage.
 * Il n'a accès au matériel que par un [BleTransport] : il ne peut émettre qu'une `AllowedCommand` de la liste blanche.
 */
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
    val platformId: PlatformDeviceId,
    val role: DeviceRole,
    /** Identifiant de modèle tel qu'annoncé (ex. « 0xA042 »), jamais un nom commercial deviné. */
    val model: String? = null,
    val name: String? = null,
    /** Indice faible (préfixe d'adresse constructeur, Android seulement) : jamais suffisant pour enregistrer. */
    val addressHint: Boolean = false,
) {
    override fun toString(): String = "Candidate(driverId=$driverId, role=$role, model=$model, name=$name)"
}

/** Équipement enregistré : son identité locale et son identifiant plateforme (jamais journalisé). */
class KnownDevice(val deviceId: DeviceId, val platformId: PlatformDeviceId, val role: DeviceRole) {
    override fun toString(): String = "KnownDevice(deviceId=$deviceId, role=$role)"
}
