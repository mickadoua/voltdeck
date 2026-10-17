package app.voltdeck.simulator

import app.voltdeck.ble.Adv
import app.voltdeck.ble.AllowedCommand
import app.voltdeck.ble.BleTransport
import app.voltdeck.ble.ConnectionPolicy
import app.voltdeck.ble.GattProfile
import app.voltdeck.ble.PlatformDeviceId
import app.voltdeck.ble.ReadAllowlist
import app.voltdeck.ble.ReadOnlyGattLink
import app.voltdeck.ble.ScanFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Faux transport Bluetooth déterministe : il annonce une batterie (service JBD) et un MPPT (Company ID Victron),
 * puis refuse, comme le vrai transport, toute commande hors de [ReadAllowlist].
 *
 * Les annonces sont factices : la charge utile Victron est un gabarit non chiffré qui ne ressemble à aucun
 * enregistrement réel, et le lien GATT n'émet aucune notification. Les vrais encodages arrivent avec VOL-5 et VOL-6.
 * Non thread-safe.
 */
class SimulatedTransport(
    private val seed: Long = 1L,
    private val advertisementPeriod: Duration = 1.seconds,
) : BleTransport {
    private val accepted = mutableListOf<ByteArray>()

    /** Trames acceptées par les liens GATT de ce transport, dans l'ordre. */
    val acceptedFrames: List<ByteArray> get() = accepted.map { it.copyOf() }

    override fun advertisements(filter: ScanFilter): Flow<Adv> = flow {
        var tick = 0L
        while (true) {
            for ((index, template) in templates.withIndex()) {
                val rssi = -55 - (abs(SimulatedPlant.unit(seed, index.toLong(), tick)) * 20).toInt()
                val adv = template.toAdv(rssi)
                if (filter.matches(adv)) emit(adv)
            }
            tick++
            delay(advertisementPeriod)
        }
    }

    override suspend fun connect(id: PlatformDeviceId, profile: GattProfile): ReadOnlyGattLink {
        val template = templates.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Équipement simulé inconnu")
        ConnectionPolicy.requireConnectable(template.toAdv(rssi = -60))
        return SimulatedGattLink()
    }

    inner class SimulatedGattLink internal constructor() : ReadOnlyGattLink {
        override val notifications: Flow<ByteArray> = emptyFlow()

        override suspend fun request(cmd: AllowedCommand) = requestFrame(cmd.frame())

        internal fun requestFrame(frame: ByteArray) {
            ReadAllowlist.requireAllowed(frame)
            accepted += frame.copyOf()
        }

        override fun close() = Unit
    }

    private class Template(
        val id: PlatformDeviceId,
        val name: String,
        val manufacturerData: Map<Int, ByteArray>,
        val serviceUuids: List<String>,
    ) {
        fun toAdv(rssi: Int) = Adv(id, name, rssi, manufacturerData, serviceUuids)
    }

    companion object {
        val BATTERY_ID = PlatformDeviceId("sim-battery")
        val MPPT_ID = PlatformDeviceId("sim-mppt")
        const val VICTRON_COMPANY_ID = ConnectionPolicy.VICTRON_COMPANY_ID
        val JBD_SERVICE_UUID = GattProfile.Jbd.serviceUuid

        private val templates = listOf(
            Template(BATTERY_ID, "SIM-BATT", emptyMap(), listOf(JBD_SERVICE_UUID)),
            Template(MPPT_ID, "SIM-MPPT", mapOf(VICTRON_COMPANY_ID to byteArrayOf(0x10, 0x00, 0x00)), emptyList()),
        )
    }
}
