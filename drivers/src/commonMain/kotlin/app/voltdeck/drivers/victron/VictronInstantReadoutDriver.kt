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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock

/** État du déchiffrement d'un équipement, affichable. */
enum class VictronLinkStatus { WAITING, OK, NO_KEY, WRONG_KEY }

/** Compteurs et états, sans donnée sensible. Sûr entre flux concurrents : un flux par appareil écrit ici. */
class VictronDiagnostics {
    private val truncated = MutableStateFlow(0)
    val truncatedFrames: Int get() = truncated.value

    private val statuses = MutableStateFlow<Map<DeviceId, VictronLinkStatus>>(emptyMap())
    val status: StateFlow<Map<DeviceId, VictronLinkStatus>> get() = statuses

    internal fun frameTruncated() {
        truncated.update { it + 1 }
    }

    internal fun setStatus(device: DeviceId, value: VictronLinkStatus) {
        statuses.update { if (it[device] == value) it else it + (device to value) }
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

    /**
     * Une clé n'est déclarée bonne qu'après [PLAUSIBLE_STREAK] trames plausibles d'affilée : sur une trame isolée, une
     * mauvaise clé passe parfois le contrôle de vraisemblance. Une trame refusée remet le compte à zéro.
     */
    override fun readings(device: KnownDevice, transport: BleTransport): Flow<Reading> = flow {
        var streak = 0
        var lastCounted: ByteArray? = null
        transport.advertisements(ScanFilter(companyId = ConnectionPolicy.VICTRON_COMPANY_ID))
            .filter { it.id == device.platformId }
            .collect { adv ->
                when (val step = decode(device, adv)) {
                    is Step.Plausible -> {
                        // Une annonce répétée à l'identique ne prouve rien de plus : seule une trame nouvelle compte.
                        val data = adv.manufacturerData.getValue(ConnectionPolicy.VICTRON_COMPANY_ID)
                        if (lastCounted?.contentEquals(data) != true) {
                            streak++
                            lastCounted = data
                        }
                        if (streak >= PLAUSIBLE_STREAK) {
                            diagnostics.setStatus(device.deviceId, VictronLinkStatus.OK)
                            step.reading?.let { emit(it) }
                        }
                    }
                    Step.Rejected -> {
                        streak = 0
                        lastCounted = null
                    }
                    Step.Ignored -> Unit
                }
            }
    }

    private sealed interface Step {
        class Plausible(val reading: Reading?) : Step
        data object Rejected : Step
        data object Ignored : Step
    }

    private fun decode(device: KnownDevice, adv: Adv): Step {
        val data = adv.manufacturerData[ConnectionPolicy.VICTRON_COMPANY_ID] ?: return Step.Ignored
        val key = keys.get(device.deviceId)
        if (key == null) {
            diagnostics.setStatus(device.deviceId, VictronLinkStatus.NO_KEY)
            return Step.Rejected
        }
        return when (val result = VictronDecoder.decode(data, key)) {
            is VictronDecodeResult.Decoded -> Step.Plausible(
                if (result.type.role == device.role && result.samples.isNotEmpty()) Reading(device.deviceId, clock.now(), result.samples) else null,
            )
            VictronDecodeResult.WrongKey -> {
                diagnostics.setStatus(device.deviceId, VictronLinkStatus.WRONG_KEY)
                Step.Rejected
            }
            VictronDecodeResult.Truncated -> {
                diagnostics.frameTruncated()
                Step.Ignored
            }
            VictronDecodeResult.NotProductAdvertisement, is VictronDecodeResult.UnsupportedRecord -> Step.Ignored
        }
    }

    companion object {
        const val DRIVER_ID = "victron.ir"
        const val PLAUSIBLE_STREAK = 3
    }
}
