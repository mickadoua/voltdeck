package app.voltdeck

import app.voltdeck.ble.Adv
import app.voltdeck.ble.BleTransport
import app.voltdeck.ble.ConnectionPolicy
import app.voltdeck.ble.GattProfile
import app.voltdeck.ble.PlatformDeviceId
import app.voltdeck.ble.ReadOnlyGattLink
import app.voltdeck.ble.ScanFilter
import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Reading
import app.voltdeck.drivers.KnownDevice
import app.voltdeck.drivers.victron.CachingVictronKeyStore
import app.voltdeck.drivers.victron.VictronInstantReadoutDriver
import app.voltdeck.drivers.victron.VictronKeyStore
import app.voltdeck.simulator.AcceleratedClock
import app.voltdeck.simulator.SimulatedPlant
import app.voltdeck.simulator.SimulatedTransport
import app.voltdeck.simulator.SimulatedVictron
import app.voltdeck.simulator.SimulationScenario
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

const val DEMO_SPEED = 60.0
private const val DEMO_SEED = 1L

/** Début de la journée simulée : 06 h 30 locale du jour courant, juste avant le lever du soleil simulé. */
fun demoStart(now: Instant, zone: TimeZone): Instant =
    now.toLocalDateTime(zone).date.atStartOfDayIn(zone) + 6.hours + 30.minutes

/** Horloge simulée accélérée ×[DEMO_SPEED] : une seconde réelle = une minute simulée. */
fun demoClock(real: Clock, zone: TimeZone = TimeZone.currentSystemDefault()): Clock =
    AcceleratedClock(real, demoStart(real.now(), zone), DEMO_SPEED)

/** Transport qui laisse passer les annonces et retient la dernière donnée constructeur Victron de chaque appareil. */
class RecordingTransport(private val inner: BleTransport) : BleTransport {
    private val latest = HashMap<PlatformDeviceId, ByteArray>()

    fun latestVictronData(id: PlatformDeviceId): ByteArray? = latest[id]?.copyOf()

    override fun advertisements(filter: ScanFilter): Flow<Adv> = inner.advertisements(filter).onEach { adv ->
        adv.manufacturerData[ConnectionPolicy.VICTRON_COMPANY_ID]?.let { latest[adv.id] = it.copyOf() }
    }

    override suspend fun connect(id: PlatformDeviceId, profile: GattProfile): ReadOnlyGattLink = inner.connect(id, profile)
}

/**
 * Une session du mode démo : le simulateur annonce de vraies trames Victron chiffrées, le pilote `victron.ir` les
 * déchiffre avec les clés de [keys]. Les clés des équipements simulés sont publiques (TEST ONLY) et recopiées dans
 * [keys] au démarrage de la session, comme si l'utilisateur les avait saisies.
 */
class DemoSession(
    val scenario: SimulationScenario,
    keys: VictronKeyStore,
    real: Clock = Clock.System,
    zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    /** Coffre mis en cache : le pilote lit la clé à chaque annonce, sans relire le Keystore/Keychain. */
    val keys: VictronKeyStore = CachingVictronKeyStore(keys)

    private val simulatedClock = demoClock(real, zone)
    private val transport = RecordingTransport(
        SimulatedTransport(DEMO_SEED, scenario = scenario, clock = simulatedClock, zone = zone),
    )
    private val batteryPlant = SimulatedPlant(DEMO_SEED, simulatedClock.now(), zone)

    val driver = VictronInstantReadoutDriver(keys, simulatedClock)
    val mppt = KnownDevice(SimulatedPlant.MPPT_ID, SimulatedTransport.MPPT_ID, DeviceRole.SOLAR_CHARGER)
    val shunt = KnownDevice(SimulatedPlant.SHUNT_ID, SimulatedTransport.SHUNT_ID, DeviceRole.SHUNT)
    val victronDevices = listOf(mppt, shunt)

    init {
        // La batterie JBD n'a pas encore de pilote (VOL-6) : son Reading vient directement du modèle (flux ci-dessous).
        victronDevices.forEach { keys.put(it.id, SimulatedVictron.TEST_KEY) }
    }

    /** Clé des équipements simulés, à afficher dans l'écran de démo (publique, TEST ONLY). */
    val deviceKeyHex: String get() = SimulatedVictron.deviceKeyHex(scenario)

    fun readings(): Flow<Reading> = merge(
        batteryPlant.readings(simulatedClock).filter { it.deviceId == SimulatedPlant.BATTERY_ID },
        driver.readings(mppt, transport),
        driver.readings(shunt, transport),
    )

    /** Vérifie puis enregistre la clé saisie pour [device]. Voir [enterVictronKey]. */
    fun submitKey(device: KnownDevice, text: String): KeyEntryOutcome =
        enterVictronKey(device.id, text, transport.latestVictronData(device.platformId), keys)
}
