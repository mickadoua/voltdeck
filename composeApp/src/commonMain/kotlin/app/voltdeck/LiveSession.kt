package app.voltdeck

import app.voltdeck.ble.Adv
import app.voltdeck.ble.BleTransport
import app.voltdeck.ble.GattProfile
import app.voltdeck.ble.PlatformDeviceId
import app.voltdeck.ble.ReadOnlyGattLink
import app.voltdeck.ble.ScanFilter
import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Reading
import app.voltdeck.drivers.Candidate
import app.voltdeck.drivers.KnownDevice
import app.voltdeck.drivers.jbd.JbdDriver
import app.voltdeck.drivers.jbd.JbdIdentityException
import app.voltdeck.drivers.victron.CachingVictronKeyStore
import app.voltdeck.drivers.victron.VictronInstantReadoutDriver
import app.voltdeck.drivers.victron.VictronKeyStore
import app.voltdeck.drivers.victron.VictronLinkStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.shareIn
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Ce que l'écran attend d'une source d'équipements, simulée ou réelle. */
interface Session {
    val devices: List<KnownDevice>
    val linkStatus: StateFlow<Map<DeviceId, VictronLinkStatus>>

    fun readings(): Flow<Reading>

    fun title(id: DeviceId): String

    fun submitKey(device: KnownDevice, text: String): KeyEntryOutcome
}

/**
 * Un seul scan Bluetooth pour tous les pilotes : Android limite le nombre de scans démarrés, et chaque pilote Victron
 * en ouvrirait un. Une panne du scan (Bluetooth coupé, permission retirée) est publiée dans [scanError] puis le scan
 * reprend, sans jamais faire tomber l'application.
 */
class SharedScanTransport(private val inner: BleTransport, scope: CoroutineScope) : BleTransport {
    private val error = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> get() = error

    private val shared = inner.advertisements(ScanFilter.Any)
        .onEach { if (error.value != null) error.value = null }
        .retryWhen { cause, attempt ->
            if (cause is CancellationException) return@retryWhen false
            error.value = SCAN_ERROR_MESSAGE
            delay((2.seconds * (1 shl attempt.coerceAtMost(4).toInt())))
            true
        }
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000))

    override fun advertisements(filter: ScanFilter): Flow<Adv> = shared.filter(filter::matches)

    override suspend fun connect(id: PlatformDeviceId, profile: GattProfile): ReadOnlyGattLink = inner.connect(id, profile)

    companion object {
        const val SCAN_ERROR_MESSAGE =
            "Le Bluetooth ne répond pas : vérifiez qu'il est activé et que Voltdeck a l'autorisation « Appareils à proximité »."
    }
}

/** Équipement vu pendant la recherche, pas encore ajouté. */
class Found(val candidate: Candidate, val name: String?, val rssi: Int)

sealed interface AddOutcome {
    data object Added : AddOutcome

    data class Rejected(val message: String) : AddOutcome
}

/** Les équipements réels de l'utilisateur : batterie JBD par connexion GATT, Victron par écoute des annonces. */
class LiveSession(
    transport: BleTransport,
    keys: VictronKeyStore,
    private val registry: DeviceRegistry,
    scope: CoroutineScope,
    clock: Clock = Clock.System,
) : Session {
    private val shared = SharedScanTransport(transport, scope)
    private val scan = RecordingTransport(shared)
    private val keys = CachingVictronKeyStore(keys)
    private val victron = VictronInstantReadoutDriver(this.keys, clock)
    private val jbd = JbdDriver(clock)

    val scanError: StateFlow<String?> get() = shared.scanError

    val registered: List<RegisteredDevice> get() = registry.all()

    override val devices: List<KnownDevice> get() = registered.map { it.known }

    override val linkStatus: StateFlow<Map<DeviceId, VictronLinkStatus>> get() = victron.diagnostics.status

    override fun title(id: DeviceId): String = registered.firstOrNull { it.id == id }?.name ?: "Équipement"

    override fun readings(): Flow<Reading> {
        val current = devices
        if (current.isEmpty()) return emptyFlow()
        val keepScanning = scan.advertisements(ScanFilter.Any).filter { false }.mapNotNull<Adv, Reading> { null }
        return merge(keepScanning, *current.map { device -> driverFor(device).readings(device, scan) }.toTypedArray())
    }

    private fun driverFor(device: KnownDevice) = if (device.role == DeviceRole.BATTERY) jbd else victron

    /** Équipements reconnus autour du téléphone, du plus proche au plus lointain, sans ceux déjà ajoutés. */
    fun discover(): Flow<List<Found>> = scan.advertisements(ScanFilter.Any)
        .mapNotNull { adv -> (victron.identify(adv) ?: jbd.identify(adv))?.let { Found(it, adv.name, adv.rssi) } }
        .runningFold(emptyMap<PlatformDeviceId, Found>()) { acc, found -> acc + (found.candidate.platformId to found) }
        .mapNotNull { map ->
            val known = registered.map { it.platformId }.toSet()
            map.values.filter { it.candidate.platformId !in known }.sortedByDescending { it.rssi }
        }

    /** Victron : la clé est vérifiée sur une annonce reçue de l'appareil avant tout enregistrement. */
    fun addVictron(found: Found, name: String, keyText: String): AddOutcome {
        val id = newDeviceId()
        return when (val outcome = enterVictronKey(id, keyText, scan.latestVictronData(found.candidate.platformId), keys)) {
            KeyEntryOutcome.Saved -> {
                registry.add(RegisteredDevice(id, found.candidate.driverId, found.candidate.role, found.candidate.platformId, name, found.candidate.model))
                AddOutcome.Added
            }
            is KeyEntryOutcome.Rejected -> AddOutcome.Rejected(outcome.message)
        }
    }

    /** JBD : connexion et lecture de la version matérielle, pour ne jamais enregistrer un appareil qui n'est pas un BMS JBD. */
    suspend fun addJbd(found: Found, name: String): AddOutcome = try {
        val version = jbd.confirmIdentity(found.candidate.platformId, scan)
        registry.add(RegisteredDevice(newDeviceId(), found.candidate.driverId, DeviceRole.BATTERY, found.candidate.platformId, name, version.ifBlank { null }))
        AddOutcome.Added
    } catch (e: JbdIdentityException) {
        AddOutcome.Rejected("${e.message}. Rapprochez-vous de la batterie et fermez l'application du fabricant, qui garde parfois la connexion.")
    }

    fun remove(id: DeviceId) {
        registry.remove(id)
        keys.remove(id)
    }

    override fun submitKey(device: KnownDevice, text: String): KeyEntryOutcome =
        enterVictronKey(device.deviceId, text, scan.latestVictronData(device.platformId), keys)

    @OptIn(ExperimentalUuidApi::class)
    private fun newDeviceId(): DeviceId = DeviceId(Uuid.random().toString())
}

fun roleLabel(role: DeviceRole): String = when (role) {
    DeviceRole.BATTERY -> "Batterie à BMS"
    DeviceRole.SOLAR_CHARGER -> "Régulateur solaire"
    DeviceRole.DCDC -> "Chargeur DC-DC"
    DeviceRole.SHUNT -> "Shunt / moniteur de batterie"
}
