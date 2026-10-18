package app.voltdeck

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Metric
import app.voltdeck.storage.AggregateReader
import app.voltdeck.storage.DeviceStore
import app.voltdeck.storage.EnergyHistory
import app.voltdeck.storage.Rollup
import app.voltdeck.storage.SystemStats
import app.voltdeck.storage.db.VoltdeckDatabase
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Critère de performance de VOL-9 : la vue Année s'ouvre en moins de 300 ms sur l'historique « two_years_history »
 * (deux ans d'heures, trois équipements, neuf séries chacun). Le test mesure la médiane de [loadStats] pour chaque
 * période et l'affiche dans la sortie standard sous le préfixe PERF.
 */
class StatsPerformanceTest {
    private val zone = TimeZone.of("Europe/Paris")
    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private val bms = DeviceId("bms-1")
    private val mppt = DeviceId("mppt-1")
    private val shunt = DeviceId("shunt-1")
    private val metrics = listOf(
        Metric.BATTERY_VOLTAGE, Metric.BATTERY_CURRENT, Metric.SOC, Metric.PV_POWER,
        Metric.ENERGY_IN_WH, Metric.ENERGY_OUT_WH, Metric.CHARGE_IN_AH, Metric.CHARGE_OUT_AH, Metric.PV_ENERGY_WH,
    )

    private fun twoYears(): VoltdeckDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY + "?foreign_keys=true")
        VoltdeckDatabase.Schema.create(driver)
        val db = VoltdeckDatabase(driver)
        val first = (now - 730.days).epochSeconds / 3600 * 3600
        val created = Instant.fromEpochSeconds(first)
        DeviceStore(db).register(bms, "test", DeviceRole.BATTERY, "bms", "a-bms", null, created)
        DeviceStore(db).register(mppt, "test", DeviceRole.SOLAR_CHARGER, "mppt", "a-mppt", null, created)
        DeviceStore(db).register(shunt, "test", DeviceRole.SHUNT, "shunt", "a-shunt", null, created)
        db.transaction {
            var hour = first
            while (hour < now.epochSeconds / 3600 * 3600) {
                val phase = sin(2 * PI * (hour % 86_400) / 86_400.0)
                for (id in listOf(bms, mppt, shunt)) {
                    for ((index, metric) in metrics.withIndex()) {
                        val value = 10.0 + index + 5.0 * phase
                        db.hourQueries.upsertHour(id.value, metric.name, 0, hour, 60, value - 1, value + 1, value * 60, value)
                    }
                }
                hour += 3600
            }
        }
        Rollup(db).rebuild(now, zone)
        return db
    }

    private companion object {
        /** Garde-fou contre une régression : les comparaisons de l'année tournent hors du fil de l'interface, après le premier affichage. */
        const val LOAD_BUDGET_MS = 1500
    }

    private fun median(repeat: Int = 5, block: () -> Unit): Long {
        block()
        val samples = (1..repeat).map {
            val mark = Clock.System.now()
            block()
            (Clock.System.now() - mark).inWholeMilliseconds
        }
        return samples.sorted()[samples.size / 2]
    }

    @Test
    fun everyPeriodOfTheStatsScreenLoadsUnderThreeHundredMilliseconds() {
        val db = twoYears()
        val stats = SystemStats(db)
        val history = EnergyHistory(db)
        val reader = AggregateReader(db)
        val anchor = LocalDate(2026, 10, 10)
        val timings = PeriodKind.entries.associateWith { kind ->
            val data = loadStats(stats, history, reader, bms, StatsPeriod(kind, anchor), zone, now)
            assertNotNull(data.produced, "${kind.label} : production attendue")
            median { loadStats(stats, history, reader, bms, StatsPeriod(kind, anchor), zone, now) }
        }
        val previousYear = median { loadStats(stats, history, reader, bms, StatsPeriod(PeriodKind.YEAR, LocalDate(2025, 6, 1)), zone, now) }
        val yearFirstScreen = median { loadStats(stats, history, reader, bms, StatsPeriod(PeriodKind.YEAR, anchor), zone, now, comparisons = false) }
        timings.forEach { (kind, ms) -> println("PERF loadStats ${kind.label}: $ms ms") }
        println("PERF loadStats Année précédente (entière): $previousYear ms")
        println("PERF loadStats Année, graphique et totaux (sans comparaisons): $yearFirstScreen ms")

        assertTrue(yearFirstScreen < 300, "année, graphique et totaux : $yearFirstScreen ms")
        timings.filterKeys { it != PeriodKind.YEAR }.forEach { (kind, ms) -> assertTrue(ms < 300, "${kind.label} : $ms ms") }
        timings.forEach { (kind, ms) -> assertTrue(ms < LOAD_BUDGET_MS, "${kind.label}, comparaisons comprises : $ms ms") }
        assertTrue(previousYear < LOAD_BUDGET_MS, "année précédente : $previousYear ms")
    }
}
