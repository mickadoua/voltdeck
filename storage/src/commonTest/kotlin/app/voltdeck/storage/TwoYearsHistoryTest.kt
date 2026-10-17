package app.voltdeck.storage

import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Metric
import app.voltdeck.storage.db.VoltdeckDatabase
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

import kotlin.time.Duration.Companion.days

import kotlin.time.Instant

/**
 * Historique « two_years_history » : deux ans d'heures pour trois équipements et neuf séries chacun, puis les requêtes de
 * graphique. Condition de réussite de VOL-7 : un an se lit en moins de 300 ms (sur l'émulateur de référence ; ce test mesure
 * le même code sur la JVM, et affiche la mesure dans la sortie standard sous le préfixe PERF).
 */
class TwoYearsHistoryTest {
    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private val zone = PARIS
    private val devices = listOf(BMS to DeviceRole.BATTERY, MPPT to DeviceRole.SOLAR_CHARGER, SHUNT to DeviceRole.SHUNT)
    private val metrics = listOf(
        Metric.BATTERY_VOLTAGE, Metric.BATTERY_CURRENT, Metric.SOC, Metric.PV_POWER,
        Metric.ENERGY_IN_WH, Metric.ENERGY_OUT_WH, Metric.CHARGE_IN_AH, Metric.CHARGE_OUT_AH, Metric.PV_ENERGY_WH,
    )

    private fun generate(): VoltdeckDatabase {
        val db = createTestDatabase()
        val first = (now - 730.days).hourStart()
        for ((id, role) in devices) db.addDevice(id, role, createdAt = instantOf(first))
        db.transaction {
            var hour = first
            while (hour < now.hourStart()) {
                val phase = sin(2 * PI * (hour % 86_400) / 86_400.0)
                for ((id, _) in devices) {
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

    private fun timed(repeat: Int = 5, block: () -> Int): Pair<Long, Int> {
        block()
        var points = 0
        val samples = (1..repeat).map {
            val mark = Clock.System.now()
            points = block()
            (Clock.System.now() - mark).inWholeMilliseconds
        }
        return samples.sorted()[samples.size / 2] to points
    }

    @Test
    fun aYearOfChartDataReadsUnderThreeHundredMilliseconds() {
        val db = generate()
        val reader = AggregateReader(db)
        val key = SeriesKey(BMS, Metric.BATTERY_VOLTAGE)

        val (yearMs, yearPoints) = timed { reader.series(key, now - 365.days, now, now, zone).rows.size }
        val (energyMs, energyPoints) = timed { reader.series(SeriesKey(MPPT, Metric.PV_ENERGY_WH), now - 365.days, now, now, zone).rows.size }
        val (twoYearsMs, twoYearsPoints) = timed { reader.series(key, now - 730.days, now, now, zone).rows.size }
        val (stackMs, _) = timed { metrics.sumOf { reader.series(SeriesKey(SHUNT, it), now - 365.days, now, now, zone).rows.size } }
        val (monthMs, monthPoints) = timed { reader.series(key, now - 60.days, now, now, zone).rows.size }
        println("PERF un an (jours): $yearMs ms, $yearPoints points")
        println("PERF un an, énergie: $energyMs ms, $energyPoints points")
        println("PERF deux ans: $twoYearsMs ms, $twoYearsPoints points")
        println("PERF un an, ${metrics.size} séries d'un écran: $stackMs ms")
        println("PERF 60 jours (heures): $monthMs ms, $monthPoints points")

        assertTrue(yearMs < 300, "un an : $yearMs ms")
        assertTrue(stackMs < 300, "un an, ${metrics.size} séries : $stackMs ms")
        assertTrue(yearPoints <= MAX_CHART_POINTS && twoYearsPoints <= MAX_CHART_POINTS && monthPoints <= MAX_CHART_POINTS)
        assertEquals(Level.DAY, reader.series(key, now - 365.days, now, now, zone).level)
        assertTrue(yearPoints in 360..367)
    }

}
