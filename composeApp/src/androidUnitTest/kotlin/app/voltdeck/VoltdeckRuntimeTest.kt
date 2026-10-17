package app.voltdeck

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.voltdeck.core.Metric
import app.voltdeck.drivers.victron.InMemoryVictronKeyStore
import app.voltdeck.simulator.SimulatedPlant
import app.voltdeck.storage.AggregateReader
import app.voltdeck.storage.Level
import app.voltdeck.storage.Nature
import app.voltdeck.storage.SeriesKey
import app.voltdeck.storage.db.VoltdeckDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class VoltdeckRuntimeTest {
    private val start = Instant.parse("2026-06-21T10:00:00Z")

    private fun memoryDatabase(): VoltdeckDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY + "?foreign_keys=true")
        VoltdeckDatabase.Schema.create(driver)
        return VoltdeckDatabase(driver)
    }

    private fun TestScope.runtime(databases: Databases?) = VoltdeckRuntime(
        InMemoryVictronKeyStore(), InMemoryShuntSetupStore(), InMemoryDeviceRegistry(), null, databases, backgroundScope,
        TimeZone.UTC, object : Clock { override fun now() = start + testScheduler.currentTime.milliseconds },
    )

    @Test
    fun theDemoIsRecordedAndReadBackByTheStatistics() = runTest {
        var created = 0
        val databases = object : Databases {
            val demo = memoryDatabase()
            override fun real() = error("pas de base réelle en démo")
            override fun freshDemo(): VoltdeckDatabase {
                created++
                return demo
            }
        }
        val runtime = runtime(databases)
        runtime.start()
        advanceTimeBy(6.minutes)
        runCurrent()

        assertEquals(1, created)
        assertEquals(3, runtime.latest.value.size)
        val db = runtime.database.value!!
        val battery = AggregateReader(db).stored(Level.MINUTE, SeriesKey(SimulatedPlant.BATTERY_ID, Metric.SOC), Instant.DISTANT_PAST, Instant.DISTANT_FUTURE, TimeZone.UTC)
        assertTrue(battery.size > 200, "${battery.size} minutes de SOC enregistrées pour 6 h simulées")
        val now = runtime.session.value.clock.now()
        val buckets = runtime.energyHistory()!!.buckets(Level.HOUR, now - 6.hours, now, TimeZone.UTC)
        assertTrue(buckets.any { it.consumedWh != null }, "consommation horaire déduite")
        val production = runtime.stats()!!.solarProduction(now.toLocalDateTime(TimeZone.UTC).date, TimeZone.UTC)
        assertNotEquals(Nature.UNAVAILABLE, production.nature, "production : $production")
        assertEquals(null, runtime.recorderState.value.error)
    }

    @Test
    fun withoutDatabaseTheScreenStillGetsLiveReadings() = runTest {
        val runtime = runtime(null)
        runtime.start()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(3, runtime.latest.value.size)
        assertEquals(null, runtime.stats())
    }

    @Test
    fun aLoadShuntStopsCountingAsTheBatteryFlow() = runTest {
        val runtime = runtime(null)
        runtime.saveSetup(SimulatedPlant.SHUNT_ID, ShuntSetup(ShuntPlacement.LOAD, Circuit.FRIDGE))
        assertEquals(ShuntPlacement.LOAD, runtime.setupOf(SimulatedPlant.SHUNT_ID).placement)
        assertEquals(1, runtime.changes.value)
    }
}
