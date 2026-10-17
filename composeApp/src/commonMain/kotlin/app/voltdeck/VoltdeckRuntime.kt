package app.voltdeck

import app.voltdeck.ble.BleTransport
import app.voltdeck.core.DeviceId
import app.voltdeck.core.Reading
import app.voltdeck.drivers.victron.VictronKeyStore
import app.voltdeck.simulator.SimulationScenario
import app.voltdeck.storage.DeviceStore
import app.voltdeck.storage.EnergyHistory
import app.voltdeck.storage.Maintenance
import app.voltdeck.storage.MinuteAggregator
import app.voltdeck.storage.SystemStats
import app.voltdeck.storage.db.VoltdeckDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock
import kotlin.time.Instant

/** Ouvre les bases : la réelle, et une base de démo neuve à chaque session de démo (son horloge repart à 06 h 30). */
interface Databases {
    fun real(): VoltdeckDatabase

    fun freshDemo(): VoltdeckDatabase
}

data class RecorderState(val lastWrite: Instant? = null, val pending: Int = 0, val error: String? = null)

/**
 * Le cœur de l'application, indépendant des écrans : il tient la session (démo ou équipements réels), garde la dernière
 * mesure de chaque équipement et l'enregistre en base. Le service Android de collecte le garde en vie écran éteint.
 * [scope] doit être mono-fil : le simulateur et l'agrégateur ne sont pas thread-safe.
 */
class VoltdeckRuntime(
    private val keys: VictronKeyStore,
    val shuntSetups: ShuntSetupStore,
    private val registry: DeviceRegistry,
    transportFactory: (() -> BleTransport)?,
    private val databases: Databases?,
    private val scope: CoroutineScope,
    val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val realClock: Clock = Clock.System,
    private val demoHistoryDays: Int = DEMO_HISTORY_DAYS,
    private val historyContext: CoroutineContext = Dispatchers.Default,
) {
    val live: LiveSession? = transportFactory?.let { LiveSession(it(), keys, registry, scope) }

    private val demoState = MutableStateFlow(live == null || registry.all().isEmpty())
    private val scenarioState = MutableStateFlow(SimulationScenario.SUNNY_DAY)
    private val version = MutableStateFlow(0)
    private val sessionState = MutableStateFlow<Session>(DemoSession(scenarioState.value, keys, realClock, zone))
    private val latestState = MutableStateFlow<Map<DeviceId, Reading>>(emptyMap())
    private val databaseState = MutableStateFlow<VoltdeckDatabase?>(null)
    private val recorder = MutableStateFlow(RecorderState())

    val demo: StateFlow<Boolean> get() = demoState
    val scenario: StateFlow<SimulationScenario> get() = scenarioState
    val session: StateFlow<Session> get() = sessionState
    val latest: StateFlow<Map<DeviceId, Reading>> get() = latestState
    val database: StateFlow<VoltdeckDatabase?> get() = databaseState
    val recorderState: StateFlow<RecorderState> get() = recorder

    /** Change à chaque ajout ou retrait d'équipement, et à chaque réglage de shunt : les écrans relisent alors l'état. */
    val changes: StateFlow<Int> get() = version

    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            combine(demoState, scenarioState, version) { demo, scenario, _ -> demo to scenario }.collect { (demo, scenario) ->
                current?.cancel()
                val session: Session = if (demo || live == null) DemoSession(scenario, keys, realClock, zone) else live
                sessionState.value = session
                latestState.value = emptyMap()
                current = scope.launch { run(session, demo) }
            }
        }
    }

    private var current: Job? = null

    fun setDemo(value: Boolean) {
        demoState.value = value
    }

    fun setScenario(value: SimulationScenario) {
        scenarioState.value = value
    }

    fun devicesChanged() {
        version.update { it + 1 }
    }

    fun setupOf(id: DeviceId): ShuntSetup = shuntSetups.get(id) ?: detectedSetup(latestState.value[id]) ?: ShuntSetup.DEFAULT

    /** Ce que le shunt annonce de lui-même, indépendamment du réglage de l'utilisateur. */
    fun announcedSetup(id: DeviceId): ShuntSetup? = detectedSetup(latestState.value[id])

    fun saveSetup(id: DeviceId, setup: ShuntSetup) {
        shuntSetups.put(id, setup)
        version.update { it + 1 }
    }

    private fun measuresBattery(id: DeviceId) = setupOf(id).placement == ShuntPlacement.BATTERY

    fun stats(): SystemStats? = databaseState.value?.let { SystemStats(it, ::measuresBattery) }

    fun energyHistory(): EnergyHistory? = databaseState.value?.let { EnergyHistory(it, ::measuresBattery) }

    private suspend fun run(session: Session, demo: Boolean) = coroutineScope {
        val db = try {
            databases?.let { if (demo) it.freshDemo() else it.real() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recorder.value = RecorderState(error = "Base indisponible : ${e.message}")
            null
        }
        databaseState.value = db
        val now = session.clock.now()
        val since = if (demo) demoHistoryStart(now, zone, demoHistoryDays) else now - 1.minutes
        val aggregator = db?.let { register(it, session, since); MinuteAggregator(it) }
        if (db != null && demo) {
            launch(historyContext) {
                try {
                    seedDemoHistory(db, now, zone, demoHistoryDays)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    recorder.update { it.copy(error = "Historique de démo indisponible : ${e.message}") }
                }
            }
        }
        val maintenance = db?.let { Maintenance(it) }
        var lastMaintenance = session.clock.now()
        val ticker = launch {
            while (true) {
                delay(FLUSH_PERIOD)
                val now = session.clock.now()
                persist(aggregator) { aggregator?.flushClosed(now) }
                if (maintenance != null && now - lastMaintenance >= 1.hours) {
                    persist(aggregator) { maintenance.run(now, zone) }
                    lastMaintenance = now
                }
            }
        }
        try {
            session.readings().collect { reading ->
                latestState.update { it + (reading.deviceId to reading) }
                persist(aggregator) { aggregator?.add(reading) }
            }
        } finally {
            ticker.cancel()
            persist(aggregator) { aggregator?.flushAll() }
        }
    }

    private fun register(db: VoltdeckDatabase, session: Session, createdAt: Instant) {
        val store = DeviceStore(db)
        val known = store.all().map { it.id }.toSet()
        session.records().filter { it.id !in known }.forEach {
            store.register(it.id, it.driverId, it.role, it.name, it.platformId.value, it.model, createdAt)
        }
        if (session === live) {
            val wanted = session.records().map { it.id }.toSet()
            store.all().filter { it.archivedAt == null && it.id !in wanted }.forEach { store.archive(it.id, session.clock.now()) }
        }
    }

    private inline fun persist(aggregator: MinuteAggregator?, block: () -> Unit) {
        if (aggregator == null) return
        try {
            val before = aggregator.pendingCount
            block()
            val after = aggregator.pendingCount
            recorder.update {
                it.copy(lastWrite = if (after < before) sessionState.value.clock.now() else it.lastWrite, pending = after, error = null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recorder.update { it.copy(error = "Écriture en base impossible : ${e.message}") }
        }
    }

    private companion object {
        val FLUSH_PERIOD = 30.seconds
    }
}
