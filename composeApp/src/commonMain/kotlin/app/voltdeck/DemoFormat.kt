package app.voltdeck

import app.voltdeck.core.DeviceId
import app.voltdeck.core.Metric
import app.voltdeck.core.Reading
import app.voltdeck.core.Sample
import app.voltdeck.drivers.jbd.JbdProtectionFlag
import app.voltdeck.drivers.victron.VictronLinkStatus
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

/** Retire la dernière mesure des appareils dont la clé est refusée : elle serait périmée, et trompeuse à l'écran. */
fun dropWrongKeyReadings(latest: MutableMap<DeviceId, Reading>, statuses: Map<DeviceId, VictronLinkStatus>) {
    statuses.forEach { (device, status) -> if (status == VictronLinkStatus.WRONG_KEY) latest.remove(device) }
}

fun deviceTitle(id: DeviceId): String = when (id) {
    SimulatedPlant.BATTERY_ID -> "Batterie (simulée)"
    SimulatedPlant.MPPT_ID -> "SmartSolar MPPT (simulé)"
    SimulatedPlant.SHUNT_ID -> "SmartShunt (simulé)"
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
    Metric.DEVICE_STATE -> "État"
    Metric.ERROR_CODE -> "Erreur"
    Metric.LOAD_CURRENT -> "Courant de la sortie load"
    Metric.TIME_TO_GO -> "Autonomie restante"
    Metric.CONSUMED_AH -> "Ah consommés"
    Metric.INPUT_VOLTAGE -> "Tension d'entrée"
    Metric.OUTPUT_VOLTAGE -> "Tension de sortie"
    Metric.AUX_VOLTAGE -> "Tension auxiliaire"
    Metric.MID_VOLTAGE -> "Tension point milieu"
    Metric.ALARM_REASON -> "Alarmes"
    Metric.OFF_REASON -> "Raison d'arrêt"
    Metric.PROTECTION_FLAGS -> "Alarmes"
    Metric.FET_STATE -> "Relais de la batterie"
    else -> sample.metric.name
}

private val VICTRON_STATES = mapOf(
    0 to "Arrêt", 1 to "Faible puissance", 2 to "Défaut", 3 to "Bulk", 4 to "Absorption", 5 to "Float",
    6 to "Stockage", 7 to "Égalisation",
)

fun victronStateText(code: Int): String = VICTRON_STATES[code] ?: "Code $code"

/** Alarmes de protection du BMS JBD (bits du registre 0x03, voir [JbdProtectionFlag]). */
fun protectionText(mask: Int): String {
    val active = JbdProtectionFlag.active(mask)
    return when {
        active.isNotEmpty() -> active.joinToString(", ") { it.frenchLabel() }
        mask != 0 -> "Alarme inconnue"
        else -> "Aucune"
    }
}

/** Relais du BMS JBD : bit 0 = charge, bit 1 = décharge (registre 0x03, octet d'état des FET). */
fun relayText(mask: Int): String = when (mask and 0x03) {
    0x03 -> "Charge et décharge actives"
    0x02 -> "Charge coupée"
    0x01 -> "Décharge coupée"
    else -> "Charge et décharge coupées"
}

private fun JbdProtectionFlag.frenchLabel(): String = when (this) {
    JbdProtectionFlag.CELL_OVERVOLTAGE -> "surtension cellule"
    JbdProtectionFlag.CELL_UNDERVOLTAGE -> "sous-tension cellule"
    JbdProtectionFlag.PACK_OVERVOLTAGE -> "surtension batterie"
    JbdProtectionFlag.PACK_UNDERVOLTAGE -> "sous-tension batterie"
    JbdProtectionFlag.CHARGE_OVER_TEMPERATURE -> "surchauffe en charge"
    JbdProtectionFlag.CHARGE_UNDER_TEMPERATURE -> "température trop basse en charge"
    JbdProtectionFlag.DISCHARGE_OVER_TEMPERATURE -> "surchauffe en décharge"
    JbdProtectionFlag.DISCHARGE_UNDER_TEMPERATURE -> "température trop basse en décharge"
    JbdProtectionFlag.CHARGE_OVERCURRENT -> "surintensité en charge"
    JbdProtectionFlag.DISCHARGE_OVERCURRENT -> "surintensité en décharge"
    JbdProtectionFlag.SHORT_CIRCUIT -> "court-circuit"
    JbdProtectionFlag.FRONT_END_IC_ERROR -> "erreur du circuit frontal"
    JbdProtectionFlag.MOSFET_SOFTWARE_LOCK -> "verrou logiciel des MOSFET"
}

fun sampleText(sample: Sample): String = when (sample.metric) {
    Metric.DEVICE_STATE -> victronStateText(sample.value.toInt())
    Metric.ERROR_CODE -> if (sample.value == 0.0) "Aucune" else "Code ${sample.value.toInt()}"
    Metric.ALARM_REASON, Metric.OFF_REASON ->
        if (sample.value == 0.0) "Aucune" else "0x" + sample.value.toLong().toString(16).uppercase()
    Metric.PROTECTION_FLAGS -> protectionText(sample.value.toInt())
    Metric.FET_STATE -> relayText(sample.value.toInt())
    Metric.TIME_TO_GO -> {
        val minutes = sample.value.toLong()
        "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} min"
    }
    else -> {
        val decimals = when (sample.metric) {
            Metric.CELL_VOLTAGE -> 3
            Metric.SOC, Metric.REMAINING_CAPACITY, Metric.NOMINAL_CAPACITY, Metric.TEMPERATURE,
            Metric.CONSUMED_AH, Metric.LOAD_CURRENT,
            -> 1
            Metric.CYCLES, Metric.PV_POWER -> 0
            else -> 2
        }
        "${formatDecimal(sample.value, decimals)} ${sample.metric.unit}"
    }
}
