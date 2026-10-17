/*
 * Source du format : Victron Energy, « Extra manufacturer Data », version imprimée le 2022-12-14,
 * https://communityarchive.victronenergy.com/storage/attachments/extra-manufacturer-data-2022-12-14.pdf
 * Vecteurs de test : keshavdv/victron-ble (Unlicense), dossier tests/, et rajlaud/victron-ble-ha-parser (Apache-2.0),
 * tests/. Aucun code d'esphome-victron_ble (GPL-3.0) ni victron-hacs (sans licence) n'est utilisé.
 */

package app.voltdeck.drivers.victron

import app.voltdeck.ble.Adv
import app.voltdeck.ble.BleTransport
import app.voltdeck.ble.ConnectionPolicy
import app.voltdeck.ble.ScanFilter
import app.voltdeck.core.DeviceId
import app.voltdeck.core.Reading
import app.voltdeck.drivers.Candidate
import app.voltdeck.drivers.DeviceDriver
import app.voltdeck.drivers.KnownDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlin.time.Clock

/** État du déchiffrement d'un équipement, affichable. */
enum class VictronLinkStatus { WAITING, OK, NO_KEY, WRONG_KEY }

/** Compteurs et états, sans donnée sensible. Non thread-safe : alimenté par la collecte d'un seul flux à la fois. */
class VictronDiagnostics {
    var truncatedFrames: Int = 0
        private set

    private val statuses = MutableStateFlow<Map<DeviceId, VictronLinkStatus>>(emptyMap())
    val status: StateFlow<Map<DeviceId, VictronLinkStatus>> get() = statuses

    internal fun frameTruncated() {
        truncatedFrames++
    }

    internal fun setStatus(device: DeviceId, value: VictronLinkStatus) {
        if (statuses.value[device] != value) statuses.value = statuses.value + (device to value)
    }
}

/**
 * Pilote Victron Instant Readout (`victron.ir`) : écoute passive des annonces chiffrées du SmartSolar MPPT (0x01),
 * du SmartShunt / BMV (0x02) et de l'Orion Smart DC-DC (0x04). Aucune connexion : [ConnectionPolicy] la refuse.
 */
class VictronInstantReadoutDriver(
    private val keys: VictronKeyStore,
    private val clock: Clock = Clock.System,
    val diagnostics: VictronDiagnostics = VictronDiagnostics(),
) : DeviceDriver {
    override val driverId: String = DRIVER_ID

    override fun identify(adv: Adv): Candidate? {
        val data = adv.manufacturerData[ConnectionPolicy.VICTRON_COMPANY_ID] ?: return null
        if (!VictronHeader.isProductAdvertisement(data)) return null
        val header = VictronHeader.parse(data) ?: return null
        val type = header.type ?: return null
        return Candidate(DRIVER_ID, adv.id, type.role, model = "0x" + header.modelId.toString(16).uppercase().padStart(4, '0'))
    }

    override fun readings(device: KnownDevice, transport: BleTransport): Flow<Reading> =
        transport.advertisements(ScanFilter(companyId = ConnectionPolicy.VICTRON_COMPANY_ID))
            .filter { it.id == device.platformId }
            .mapNotNull { adv -> decode(device, adv) }

    private fun decode(device: KnownDevice, adv: Adv): Reading? {
        val data = adv.manufacturerData[ConnectionPolicy.VICTRON_COMPANY_ID] ?: return null
        val key = keys.get(device.id)
        if (key == null) {
            diagnostics.setStatus(device.id, VictronLinkStatus.NO_KEY)
            return null
        }
        return when (val result = VictronDecoder.decode(data, key)) {
            is VictronDecodeResult.Decoded -> {
                diagnostics.setStatus(device.id, VictronLinkStatus.OK)
                if (result.type.role == device.role && result.samples.isNotEmpty()) Reading(device.id, clock.now(), result.samples) else null
            }
            VictronDecodeResult.WrongKey -> {
                diagnostics.setStatus(device.id, VictronLinkStatus.WRONG_KEY)
                null
            }
            VictronDecodeResult.Truncated -> {
                diagnostics.frameTruncated()
                null
            }
            VictronDecodeResult.NotProductAdvertisement, is VictronDecodeResult.UnsupportedRecord -> null
        }
    }

    companion object {
        const val DRIVER_ID = "victron.ir"
    }
}
