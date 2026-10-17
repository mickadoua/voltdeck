package app.voltdeck

import app.voltdeck.core.DeviceId
import app.voltdeck.core.Metric
import app.voltdeck.core.Sample
import app.voltdeck.simulator.SimulatedPlant
import kotlin.math.abs
import kotlin.math.roundToLong

fun formatDecimal(value: Double, decimals: Int): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = (abs(value) * factor).roundToLong()
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    if (decimals == 0) return "$sign$scaled"
    return "$sign${scaled / factor}.${(scaled % factor).toString().padStart(decimals, '0')}"
}

fun deviceTitle(id: DeviceId): String = when (id) {
    SimulatedPlant.BATTERY_ID -> "Batterie (simulée)"
    SimulatedPlant.MPPT_ID -> "MPPT solaire (simulé)"
    else -> "Équipement inconnu"
}

fun sampleLabel(sample: Sample): String = when (sample.metric) {
    Metric.BATTERY_VOLTAGE -> "Tension batterie"
    Metric.BATTERY_CURRENT -> "Courant batterie (+ = charge)"
    Metric.SOC -> "État de charge"
    Metric.REMAINING_CAPACITY -> "Capacité restante"
    Metric.NOMINAL_CAPACITY -> "Capacité nominale"
    Metric.CYCLES -> "Cycles"
    Metric.PV_POWER -> "Puissance solaire"
    Metric.YIELD_TODAY -> "Production du jour"
    Metric.TEMPERATURE -> "Température sonde ${sample.channel + 1}"
    Metric.CELL_VOLTAGE -> "Cellule ${sample.channel + 1}"
    else -> sample.metric.name
}

fun sampleText(sample: Sample): String {
    val decimals = when (sample.metric) {
        Metric.CELL_VOLTAGE -> 3
        Metric.SOC, Metric.REMAINING_CAPACITY, Metric.NOMINAL_CAPACITY, Metric.TEMPERATURE -> 1
        Metric.CYCLES, Metric.PV_POWER -> 0
        else -> 2
    }
    return "${formatDecimal(sample.value, decimals)} ${sample.metric.unit}"
}
