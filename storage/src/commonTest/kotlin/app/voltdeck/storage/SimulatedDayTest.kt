package app.voltdeck.storage

import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Metric
import app.voltdeck.simulator.SimulatedPlant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** Condition de réussite de VOL-7 : un jour de simulateur « sunny_day » à ×60, de bout en bout dans la base. */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedDayTest {
    private val start = Instant.parse("2026-06-21T00:00:00Z")
    private val day = LocalDate(2026, 6, 21)

    @Test
    fun productionOfTheDayMatchesTheSimulatedYieldAndTheIntegratedEnergy() = runTest {
        val db = createTestDatabase()
        db.addDevice(SimulatedPlant.BATTERY_ID, DeviceRole.BATTERY)
        db.addDevice(SimulatedPlant.MPPT_ID, DeviceRole.SOLAR_CHARGER)
        val plant = SimulatedPlant(seed = 7L, start = start)
        val clock = object : Clock {
            override fun now(): Instant = start + (testScheduler.currentTime * 60).milliseconds
        }
        val aggregator = MinuteAggregator(db)
        var lastYieldOfTheDay = Double.NaN
        val collector = launch {
            plant.readings(clock).collect { reading ->
                if (reading.at < start + 24.hours) {
                    reading.samples.firstOrNull { it.metric == Metric.YIELD_TODAY }?.let { lastYieldOfTheDay = it.value }
                }
                aggregator.add(reading)
            }
        }
        advanceTimeBy(24L * 60 * 1000 + 500)
        runCurrent()
        collector.cancel()
        aggregator.flushAll()

        val now = start + 25.hours
        Maintenance(db).run(now, UTC)
        val stats = SystemStats(db)

        val production = stats.solarProduction(day, UTC)
        assertEquals(Nature.DEVICE_COMPUTED, production.nature)
        assertEquals(lastYieldOfTheDay, production.value!!, 1e-9, "la base garde le YIELD_TODAY simulé du jour")
        assertTrue(lastYieldOfTheDay > 1.0, "journée ensoleillée : $lastYieldOfTheDay kWh")

        val integrated = AggregateReader(db)
            .daily(SeriesKey(SimulatedPlant.MPPT_ID, Metric.PV_ENERGY_WH), start, start + 24.hours, UTC).single().stats.sum / 1000.0
        val deviation = abs(integrated - production.value!!) / production.value!!
        println("SIM_DAY yield=${production.value} kWh integrated=$integrated kWh deviation=${deviation * 100} %")
        assertTrue(deviation <= 0.02, "énergie intégrée $integrated kWh contre $lastYieldOfTheDay kWh simulés (${deviation * 100} %)")

        val consumption = stats.consumption(day, UTC, now)
        assertEquals(Nature.CALCULATED, consumption.nature)
        assertTrue(consumption.coverage!! > 0.99, "couverture ${consumption.coverage}")
        assertTrue(consumption.value!! in 1000.0..1400.0, "consommation ${consumption.value} Wh (3,8 A moyens sous ~13 V)")
    }
}
