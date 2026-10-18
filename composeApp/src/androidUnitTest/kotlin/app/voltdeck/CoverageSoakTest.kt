// TEST ONLY — clés publiques du simulateur uniquement.

package app.voltdeck

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.voltdeck.core.DeviceRole
import app.voltdeck.drivers.victron.InMemoryVictronKeyStore
import app.voltdeck.simulator.SimulatedTransport
import app.voltdeck.simulator.SimulatedVictron
import app.voltdeck.simulator.SimulationScenario
import app.voltdeck.storage.db.VoltdeckDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Mesure de la condition de succès de VOL-8 côté pipeline : le moteur réel (scan partagé, pilotes JBD et Victron,
 * agrégation minute, SQLite) tourne en temps réel (×1) sur le transport simulé, puis la couverture des minutes de chaque
 * équipement est relue en base. Ne s'exécute que si `VOLTDECK_SOAK_SECONDS` est défini (durée en secondes) ;
 * `VOLTDECK_SOAK_OUT` donne le fichier où écrire la progression et le résultat.
 *
 * Elle ne couvre ni l'écran éteint, ni le mode Doze, ni la pile Bluetooth d'Android : ceux-là ne se mesurent que sur un
 * appareil ou un émulateur.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoverageSoakTest {
    @Test
    fun everyDeviceKeepsAtLeast95PercentOfMinutes() = runBlocking {
        val seconds = System.getenv("VOLTDECK_SOAK_SECONDS")?.toLongOrNull() ?: return@runBlocking
        val out = File(System.getenv("VOLTDECK_SOAK_OUT") ?: "soak.txt")
        out.writeText("")
        fun log(line: String) = out.appendText("$line\n")

        val scenario = SimulationScenario.SUNNY_DAY
        val clock = Clock.System
        val keys = InMemoryVictronKeyStore()
        val registry = InMemoryDeviceRegistry()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

        val setup = LiveSession(SimulatedTransport(seed = 1L, scenario = scenario, clock = clock, zone = TimeZone.UTC), keys, registry, scope, clock)
        val found = withTimeout(30.seconds) { setup.discover().first { it.size == 3 } }
        for (device in found) {
            when (device.candidate.role) {
                DeviceRole.BATTERY -> setup.addJbd(device, "Batterie")
                else -> setup.addVictron(device, device.candidate.role.name, SimulatedVictron.deviceKeyHex(scenario))
            }
        }
        check(registry.all().size == 3) { "3 équipements simulés attendus, ${registry.all().size} ajoutés" }

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY + "?foreign_keys=true")
        VoltdeckDatabase.Schema.create(driver)
        val database = VoltdeckDatabase(driver)
        val runtime = VoltdeckRuntime(
            keys, InMemoryShuntSetupStore(), registry,
            { SimulatedTransport(seed = 1L, scenario = scenario, clock = clock, zone = TimeZone.UTC) },
            object : Databases {
                override fun real() = database
                override fun freshDemo() = error("pas de démo")
            },
            scope, TimeZone.UTC, clock,
        )
        runtime.start()
        val startedAt = clock.now()
        log("début ${startedAt}, durée ${seconds} s, ×1, ${registry.all().size} équipements")

        val deadline = startedAt + seconds.seconds
        while (clock.now() < deadline) {
            delay(5.minutes.coerceAtMost(deadline - clock.now()))
            val now = clock.now()
            registry.all().forEach { log("  ${now} ${it.role} ${runtime.coverage(it.id, now)} ${runtime.readingsPerMinute(it.id, now)}/min") }
        }

        scope.coroutineContext[Job]!!.cancelAndJoin()
        val end = clock.now()
        log("fin ${end}")
        val results = registry.all().map { it to runtime.coverage(it.id, end) }
        results.forEach { (device, coverage) -> log("RÉSULTAT ${device.role} : ${coverageText(coverage)}") }
        results.forEach { (device, coverage) ->
            assertTrue((coverage?.ratio ?: 0.0) >= COVERAGE_TARGET, "${device.role} : ${coverageText(coverage)}")
        }
    }
}
