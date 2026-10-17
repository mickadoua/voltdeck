package app.voltdeck.simulator

import app.voltdeck.core.DeviceId
import app.voltdeck.core.Metric
import app.voltdeck.core.Reading
import app.voltdeck.core.Sample
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Modèle physique minimal et déterministe d'un système 12 V : un MPPT, une batterie LiFePO4 4S de 200 Ah
 * et une consommation (base + frigo par cycles). Les mesures sont des valeurs synthétiques : aucune ne
 * vient du matériel du fondateur.
 *
 * Déterminisme : mêmes `seed`, `start` et `zone`, et mêmes instants demandés => mêmes [Reading]. Le bruit est
 * une fonction pure de (graine, canal, seconde). L'état de charge est intégré par pas d'une minute simulée.
 * Non thread-safe : un seul consommateur à la fois.
 *
 * Les vrais encodages (annonces Victron chiffrées, trames JBD) arrivent avec VOL-5 et VOL-6.
 */
class SimulatedPlant(
    private val seed: Long,
    start: Instant,
    private val zone: TimeZone = TimeZone.UTC,
) {
    private var simulatedUntil: Instant = start
    private var soc = INITIAL_SOC
    private var yieldKwh = 0.0
    private var yieldDay = start.toLocalDateTime(zone).date

    private class Flows(val mpptAmps: Double, val loadAmps: Double) {
        val netAmps get() = mpptAmps - loadAmps
    }

    fun readingsAt(at: Instant): List<Reading> {
        advanceTo(at)
        val flows = flowsAt(at, soc)
        val packVolts = packVoltage(soc, flows.netAmps)
        return listOf(batteryReading(at, flows, packVolts), mpptReading(at, flows, packVolts))
    }

    /** Émet les deux [Reading] toutes les [period] réelles, à l'heure donnée par [clock]. */
    fun readings(clock: Clock, period: Duration = 1.seconds): Flow<Reading> = flow {
        while (true) {
            readingsAt(clock.now()).forEach { emit(it) }
            delay(period)
        }
    }

    private fun advanceTo(at: Instant) {
        while (simulatedUntil + STEP <= at) {
            step(simulatedUntil)
            simulatedUntil += STEP
        }
    }

    private fun step(t: Instant) {
        val day = t.toLocalDateTime(zone).date
        if (day != yieldDay) {
            yieldDay = day
            yieldKwh = 0.0
        }
        val flows = flowsAt(t, soc)
        val volts = packVoltage(soc, flows.netAmps)
        soc = (soc + flows.netAmps / CAPACITY_AH * 100.0 / 60.0).coerceIn(0.0, 100.0)
        yieldKwh += flows.mpptAmps * volts / EFFICIENCY / 1000.0 / 60.0
    }

    private fun flowsAt(t: Instant, soc: Double): Flows {
        val volts = ocvPack(soc)
        val loadAmps = loadAmps(t)
        var mpptAmps = solarWatts(t) * EFFICIENCY / volts
        if (soc >= 100.0 && mpptAmps > loadAmps) mpptAmps = loadAmps
        return Flows(mpptAmps, loadAmps)
    }

    private fun solarWatts(t: Instant): Double {
        val hour = localHour(t)
        if (hour <= SUNRISE || hour >= SUNSET) return 0.0
        val bell = sin(PI * (hour - SUNRISE) / (SUNSET - SUNRISE)).pow(1.3)
        val cloud = 1.0 - 0.65 * exp(-((hour - CLOUD_HOUR) / 0.4).pow(2))
        return PEAK_WATTS * bell * cloud * (1.0 + 0.03 * noise(CH_PV, t))
    }

    private fun loadAmps(t: Instant): Double {
        val minuteOfCycle = ((t.epochSeconds / 60.0) + fridgePhaseMinutes) % FRIDGE_PERIOD_MIN
        val fridge = if (minuteOfCycle < FRIDGE_ON_MIN) FRIDGE_AMPS else 0.0
        return max(0.0, BASE_LOAD_AMPS + fridge + 0.15 * noise(CH_LOAD, t))
    }

    private val fridgePhaseMinutes = (unit(seed, CH_FRIDGE_PHASE, 0) + 1.0) * FRIDGE_PERIOD_MIN / 2.0

    private fun localHour(t: Instant): Double {
        val local = t.toLocalDateTime(zone)
        return local.hour + local.minute / 60.0 + local.second / 3600.0
    }

    private fun packVoltage(soc: Double, netAmps: Double): Double =
        (0 until CELLS).sumOf { cellVoltage(it, soc, netAmps) }

    private fun cellVoltage(index: Int, soc: Double, netAmps: Double): Double =
        ocvCell(soc) + CELL_DRIFT_V * unit(seed, CH_CELL_DRIFT + index, 0) + netAmps * CELL_RESISTANCE_OHM

    private fun noise(channel: Long, t: Instant): Double = unit(seed, channel, t.epochSeconds)

    private fun batteryReading(at: Instant, flows: Flows, packVolts: Double): Reading {
        val temp0 = 21.0 + 5.0 * sin(2 * PI * (localHour(at) - 9.0) / 24.0) + 0.3 * abs(flows.netAmps) / 10.0
        val samples = buildList {
            add(Sample(Metric.BATTERY_VOLTAGE, value = packVolts.round(2)))
            add(Sample(Metric.BATTERY_CURRENT, value = flows.netAmps.round(2)))
            add(Sample(Metric.SOC, value = soc.round(1)))
            add(Sample(Metric.REMAINING_CAPACITY, value = (soc / 100.0 * CAPACITY_AH).round(1)))
            add(Sample(Metric.NOMINAL_CAPACITY, value = CAPACITY_AH))
            add(Sample(Metric.CYCLES, value = CYCLES))
            add(Sample(Metric.TEMPERATURE, 0, temp0.round(1)))
            add(Sample(Metric.TEMPERATURE, 1, (temp0 + 0.8 + 0.1 * noise(CH_TEMP, at)).round(1)))
            for (cell in 0 until CELLS) {
                add(Sample(Metric.CELL_VOLTAGE, cell, cellVoltage(cell, soc, flows.netAmps).round(3)))
            }
        }
        return Reading(BATTERY_ID, at, samples)
    }

    private fun mpptReading(at: Instant, flows: Flows, packVolts: Double): Reading {
        val pvWatts = flows.mpptAmps * packVolts / EFFICIENCY
        return Reading(
            MPPT_ID,
            at,
            listOf(
                Sample(Metric.PV_POWER, value = pvWatts.roundToInt().toDouble()),
                Sample(Metric.BATTERY_VOLTAGE, value = packVolts.round(2)),
                Sample(Metric.BATTERY_CURRENT, value = flows.mpptAmps.round(2)),
                Sample(Metric.YIELD_TODAY, value = yieldKwh.round(2)),
            ),
        )
    }

    companion object {
        val BATTERY_ID = DeviceId("00000000-0000-4000-8000-0000000000b1")
        val MPPT_ID = DeviceId("00000000-0000-4000-8000-0000000000c2")

        const val CAPACITY_AH = 200.0
        private const val INITIAL_SOC = 60.0
        private const val CELLS = 4
        private const val CYCLES = 37.0
        private const val EFFICIENCY = 0.97
        private const val PEAK_WATTS = 380.0
        private const val SUNRISE = 7.0
        private const val SUNSET = 20.0
        private const val CLOUD_HOUR = 13.5
        private const val BASE_LOAD_AMPS = 2.0
        private const val FRIDGE_AMPS = 6.0
        private const val FRIDGE_PERIOD_MIN = 40.0
        private const val FRIDGE_ON_MIN = 12.0
        private const val CELL_DRIFT_V = 0.005
        private const val CELL_RESISTANCE_OHM = 0.0005
        private val STEP = 1.minutes

        private const val CH_PV = 1L
        private const val CH_LOAD = 2L
        private const val CH_TEMP = 3L
        private const val CH_FRIDGE_PHASE = 4L
        private const val CH_CELL_DRIFT = 100L

        private val OCV_CELL = listOf(
            0.0 to 2.90, 5.0 to 3.10, 10.0 to 3.20, 20.0 to 3.25, 40.0 to 3.28,
            60.0 to 3.30, 80.0 to 3.32, 90.0 to 3.33, 99.0 to 3.35, 100.0 to 3.45,
        )

        private fun ocvCell(soc: Double): Double {
            val s = soc.coerceIn(0.0, 100.0)
            val hi = OCV_CELL.indexOfFirst { it.first >= s }.coerceAtLeast(1)
            val (s0, v0) = OCV_CELL[hi - 1]
            val (s1, v1) = OCV_CELL[hi]
            return v0 + (v1 - v0) * (s - s0) / (s1 - s0)
        }

        private fun ocvPack(soc: Double) = ocvCell(soc) * CELLS

        private fun Double.round(decimals: Int): Double {
            var factor = 1.0
            repeat(decimals) { factor *= 10.0 }
            return (this * factor).roundToInt() / factor
        }

        /** Bruit déterministe dans [-1, 1[ (splitmix64), fonction pure de ses arguments. */
        internal fun unit(seed: Long, channel: Long, k: Long): Double {
            var z = seed * 31 + channel * 1_000_003 + k * 0x9E3779B97F4A7C15uL.toLong()
            z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
            z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
            z = z xor (z ushr 31)
            return (z ushr 11).toDouble() / (1L shl 53).toDouble() * 2.0 - 1.0
        }
    }
}
