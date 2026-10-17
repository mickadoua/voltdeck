package app.voltdeck.drivers

import app.voltdeck.ble.Adv
import app.voltdeck.ble.BleTransport
import app.voltdeck.ble.PlatformDeviceId
import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Reading
import kotlinx.coroutines.flow.Flow

/**
 * Interface commune des pilotes (VOL-1 §4.1). Un pilote n'a accès au matériel que par un [BleTransport] :
 * il ne peut émettre qu'une `AllowedCommand` de la liste blanche.
 */
interface DeviceDriver {
    /** Stocké en base, ne change jamais (ex. `victron.ir`). */
    val driverId: String

    /** Reconnaissance passive d'une annonce, sans connexion. */
    fun identify(adv: Adv): Candidate?

    fun readings(device: KnownDevice, transport: BleTransport): Flow<Reading>
}

/** Équipement reconnu dans une annonce, pas encore enregistré. */
data class Candidate(
    val driverId: String,
    val platformId: PlatformDeviceId,
    val role: DeviceRole,
    /** Identifiant de modèle tel qu'annoncé (ex. « 0xA042 »), jamais un nom commercial deviné. */
    val model: String?,
)

/** Équipement enregistré par l'utilisateur. */
data class KnownDevice(
    val id: DeviceId,
    val platformId: PlatformDeviceId,
    val role: DeviceRole,
)
