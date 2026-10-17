package app.voltdeck.simulator

import app.voltdeck.ble.Adv
import app.voltdeck.ble.AllowedCommand
import app.voltdeck.ble.BleTransport
import app.voltdeck.ble.ConnectionLedger
import app.voltdeck.ble.ConnectionPolicy
import app.voltdeck.ble.GattProfile
import app.voltdeck.ble.PlatformDeviceId
import app.voltdeck.ble.ReadAllowlist
import app.voltdeck.ble.ReadOnlyGattLink
import app.voltdeck.ble.ScanFilter
import app.voltdeck.core.DeviceId
import app.voltdeck.core.Reading
import app.voltdeck.drivers.victron.VictronRecordType
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.datetime.TimeZone
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Faux transport Bluetooth déterministe : il annonce une batterie (service JBD), un MPPT et un SmartShunt (Company
 * ID Victron), puis refuse, comme le vrai transport, toute commande hors de [ReadAllowlist].
 *
 * Les annonces Victron sont de vrais enregistrements « Instant Readout » : les mesures de [SimulatedPlant] à l'heure
 * de [clock] sont encodées bit à bit puis chiffrées en AES-CTR avec la clé du [scenario] (voir [SimulatedVictron]).
 * Le lien GATT de la batterie répond par de vraies trames JBD ([SimulatedJbdBms]), selon le [JbdScenario] choisi.
 * Non thread-safe : tous les collecteurs d'[advertisements] doivent s'exécuter sur le même fil.
 */
class SimulatedTransport(
    private val seed: Long = 1L,
    private val advertisementPeriod: Duration = 1.seconds,
    private val scenario: SimulationScenario = SimulationScenario.SUNNY_DAY,
    jbdScenario: JbdScenario = JbdScenario.Nominal,
    private val clock: Clock = Clock.System,
    zone: TimeZone = TimeZone.UTC,
    private val plant: SimulatedPlant = SimulatedPlant(seed, jbdScenario.plantStart(clock.now()), zone),
) : BleTransport {
    private val accepted = mutableListOf<ByteArray>()
    private val victronKey = SimulatedVictron.deviceKey(scenario)
    private val bms = SimulatedJbdBms(plant, jbdScenario, clock)

    /** Trames acceptées par les liens GATT de ce transport, dans l'ordre. */
    val acceptedFrames: List<ByteArray> get() = accepted.map { it.copyOf() }

    override fun advertisements(filter: ScanFilter): Flow<Adv> = flow {
        var tick = 0L
        while (true) {
            val readings = plant.readingsAt(clock.now()).associateBy { it.deviceId }
            for ((index, device) in devices.withIndex()) {
                val rssi = -55 - (abs(SimulatedPlant.unit(seed, index.toLong(), tick)) * 20).toInt()
                val adv = device.toAdv(rssi, tick, readings)
                if (filter.matches(adv)) emit(adv)
            }
            tick++
            delay(advertisementPeriod)
        }
    }

    private fun Device.toAdv(rssi: Int, tick: Long, readings: Map<DeviceId, Reading>): Adv {
        val victron = victron
        val data = if (victron == null) manufacturerData else {
            val samples = readings.getValue(victron.plantId).samples
            val nonce = ((tick + victron.nonceOffset) and 0xFFFF).toInt()
            val record = VictronAdvertisementEncoder.encode(victron.type, victron.modelId, nonce, victronKey, samples)
            mapOf(VICTRON_COMPANY_ID to record)
        }
        return Adv(id, name, rssi, data, serviceUuids)
    }

    override suspend fun connect(id: PlatformDeviceId, profile: GattProfile): ReadOnlyGattLink {
        val device = devices.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Équipement simulé inconnu")
        val ledger = ConnectionLedger().apply { record(Adv(id, device.name, -60, device.advertisedManufacturerData(), device.serviceUuids)) }
        ledger.requireConnectable(id, profile)
        bms.markConnectAttempt()
        if (bms.isOffline()) throw SimulatedLinkException("BMS simulé hors de portée")
        return SimulatedGattLink()
    }

    /**
     * Lien GATT du BMS simulé : à chaque requête autorisée, les notifications portent la vraie trame JBD de réponse,
     * découpée en morceaux de 20 octets. Les scénarios de [JbdScenario] y ajoutent coupures, trames corrompues et
     * premières écritures en échec.
     */
    inner class SimulatedGattLink internal constructor() : ReadOnlyGattLink {
        private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        private var failuresLeft = bms.failuresPerConnection()
        private var lost = false

        override val notifications: Flow<ByteArray> = inbox.receiveAsFlow()

        override suspend fun request(cmd: AllowedCommand) {
            requestFrame(cmd.frame())
            bms.respond(cmd).forEach { inbox.trySend(it) }
        }

        internal fun requestFrame(frame: ByteArray) {
            ReadAllowlist.requireAllowed(frame)
            if (lost || bms.isOffline()) {
                lost = true
                val cause = SimulatedLinkException("liaison perdue")
                inbox.close(cause)
                throw cause
            }
            if (failuresLeft > 0) {
                failuresLeft--
                throw SimulatedLinkException("écriture simulée en échec")
            }
            accepted += frame.copyOf()
        }

        override fun close() {
            inbox.close()
        }
    }

    private class VictronRole(val plantId: DeviceId, val type: VictronRecordType, val modelId: Int, val nonceOffset: Long)

    private class Device(
        val id: PlatformDeviceId,
        val name: String,
        val manufacturerData: Map<Int, ByteArray>,
        val serviceUuids: List<String>,
        val victron: VictronRole? = null,
    ) {
        /** Donnée constructeur sans mesure : suffit au registre de connexions, qui ne regarde que le Company ID. */
        fun advertisedManufacturerData(): Map<Int, ByteArray> =
            if (victron == null) manufacturerData else mapOf(VICTRON_COMPANY_ID to byteArrayOf(0x10))
    }

    companion object {
        val BATTERY_ID = PlatformDeviceId("sim-battery")
        val MPPT_ID = PlatformDeviceId("sim-mppt")
        val SHUNT_ID = PlatformDeviceId("sim-shunt")
        const val VICTRON_COMPANY_ID = ConnectionPolicy.VICTRON_COMPANY_ID
        val JBD_SERVICE_UUID = GattProfile.Jbd.serviceUuid

        private val devices = listOf(
            Device(BATTERY_ID, "SIM-BATT", emptyMap(), listOf(JBD_SERVICE_UUID)),
            Device(
                MPPT_ID, "SIM-MPPT", emptyMap(), emptyList(),
                VictronRole(SimulatedPlant.MPPT_ID, VictronRecordType.SOLAR_CHARGER, SimulatedVictron.MPPT_MODEL_ID, nonceOffset = 100),
            ),
            Device(
                SHUNT_ID, "SIM-SHUNT", emptyMap(), emptyList(),
                VictronRole(SimulatedPlant.SHUNT_ID, VictronRecordType.BATTERY_MONITOR, SimulatedVictron.SHUNT_MODEL_ID, nonceOffset = 7_000),
            ),
        )
    }
}
