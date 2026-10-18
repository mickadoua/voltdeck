package app.voltdeck

import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Metric
import app.voltdeck.core.Reading
import app.voltdeck.core.Sample

/** Une ligne de l'écran d'un équipement. [tag] précise l'origine quand ce n'est pas une mesure directe. */
data class DetailRow(val label: String, val value: String, val tag: String? = null)

data class DetailSection(val title: String, val rows: List<DetailRow>)

/** Valeur mise en avant en haut de l'écran. */
data class Hero(val label: String, val number: String, val unit: String?, val tag: String? = null)

/** Courbe des dernières 24 heures de la mesure principale. */
data class HistorySpec(val metric: Metric, val title: String, val unit: String, val decimals: Int, val fixedPercent: Boolean = false)

data class DeviceDetail(val hero: Hero?, val history: HistorySpec?, val sections: List<DetailSection>)

private const val CALCULATED = "calculée"

/**
 * Contenu de l'écran d'un équipement, à partir de sa dernière mesure. Un champ que le protocole n'envoie pas
 * n'apparaît pas (jamais « 0 ») ; un 0 réellement mesuré est affiché. Les valeurs déduites portent une étiquette.
 */
fun deviceDetail(role: DeviceRole, reading: Reading): DeviceDetail = when (role) {
    DeviceRole.BATTERY -> batteryDetail(reading)
    DeviceRole.SOLAR_CHARGER -> solarDetail(reading)
    DeviceRole.SHUNT -> shuntDetail(reading)
    DeviceRole.DCDC -> dcdcDetail(reading)
}

private fun Reading.value(metric: Metric, channel: Int = 0): Double? =
    samples.firstOrNull { it.metric == metric && it.channel == channel }?.value

private fun Reading.sample(metric: Metric): Sample? = samples.firstOrNull { it.metric == metric }

private fun Reading.channels(metric: Metric): List<Pair<Int, Double>> =
    samples.filter { it.metric == metric }.sortedBy { it.channel }.map { it.channel to it.value }

private fun row(label: String, value: Double?, unit: String, decimals: Int, tag: String? = null): DetailRow? =
    value?.let { DetailRow(label, "${frDecimal(it, decimals)} $unit", tag) }

private fun text(label: String, sample: Sample?, tag: String? = null): DetailRow? =
    sample?.let { DetailRow(label, sampleText(it), tag) }

/** Puissance déduite de V × I ; le signe suit le courant (+ = la batterie se charge). */
private fun power(voltage: Double?, current: Double?): Double? =
    if (voltage != null && current != null) voltage * current else null

private fun section(title: String, vararg rows: DetailRow?): DetailSection? =
    rows.filterNotNull().takeIf { it.isNotEmpty() }?.let { DetailSection(title, it) }

private fun hero(label: String, value: Double?, unit: String, decimals: Int, tag: String? = null) =
    value?.let { Hero(label, frDecimal(it, decimals), unit, tag) }

private fun batteryDetail(r: Reading): DeviceDetail {
    val voltage = r.value(Metric.BATTERY_VOLTAGE)
    val current = r.value(Metric.BATTERY_CURRENT)
    val cells = r.channels(Metric.CELL_VOLTAGE)
    val lowest = cells.minByOrNull { it.second }
    val highest = cells.maxByOrNull { it.second }
    val temperatures = r.channels(Metric.TEMPERATURE)
    return DeviceDetail(
        hero = hero("État de charge", r.value(Metric.SOC), "%", 0),
        history = HistorySpec(Metric.SOC, "Charge sur 24 h", "%", 0, fixedPercent = true),
        sections = listOfNotNull(
            section(
                "Mesures",
                row("Tension", voltage, "V", 2),
                row("Courant (+ = charge)", current, "A", 1),
                row("Puissance (+ = charge)", power(voltage, current), "W", 0, CALCULATED),
                row("Capacité restante", r.value(Metric.REMAINING_CAPACITY), "Ah", 1),
                row("Capacité nominale", r.value(Metric.NOMINAL_CAPACITY), "Ah", 1),
                r.value(Metric.CYCLES)?.let { DetailRow("Cycles", frDecimal(it, 0), "compteur du BMS") },
            ),
            if (cells.isEmpty()) null else DetailSection(
                "Cellules",
                listOfNotNull(
                    lowest?.let { DetailRow("Plus basse", "${frDecimal(it.second, 3)} V · cellule ${it.first + 1}") },
                    highest?.let { DetailRow("Plus haute", "${frDecimal(it.second, 3)} V · cellule ${it.first + 1}") },
                    if (lowest != null && highest != null) DetailRow("Écart", "${frDecimal((highest.second - lowest.second) * 1000, 0)} mV", CALCULATED) else null,
                ) + cells.map { (channel, v) -> DetailRow("Cellule ${channel + 1}", "${frDecimal(v, 3)} V") },
            ),
            if (temperatures.isEmpty()) null else DetailSection(
                "Températures",
                temperatures.map { (channel, v) -> DetailRow("Sonde ${channel + 1}", "${frDecimal(v, 1)} °C") },
            ),
            section(
                "Protections",
                text("Alarmes", r.sample(Metric.PROTECTION_FLAGS)),
                text("Relais", r.sample(Metric.FET_STATE)),
            ),
        ),
    )
}

private fun solarDetail(r: Reading): DeviceDetail {
    val voltage = r.value(Metric.BATTERY_VOLTAGE)
    val current = r.value(Metric.BATTERY_CURRENT)
    return DeviceDetail(
        hero = hero("Puissance solaire", r.value(Metric.PV_POWER), "W", 0),
        history = HistorySpec(Metric.PV_POWER, "Puissance solaire sur 24 h", "W", 0),
        sections = listOfNotNull(
            section(
                "Production",
                row("Production du jour", r.value(Metric.YIELD_TODAY), "kWh", 2),
                row("Tension batterie", voltage, "V", 2),
                row("Courant vers la batterie", current, "A", 1),
                row("Puissance vers la batterie", power(voltage, current), "W", 0, CALCULATED),
                row("Courant de charge", r.value(Metric.LOAD_CURRENT), "A", 1),
            ),
            section(
                "État",
                text("Étape de charge", r.sample(Metric.DEVICE_STATE)),
                text("Erreur", r.sample(Metric.ERROR_CODE)),
                text("Raison d'arrêt", r.sample(Metric.OFF_REASON)),
            ),
        ),
    )
}

private fun shuntDetail(r: Reading): DeviceDetail {
    val voltage = r.value(Metric.BATTERY_VOLTAGE)
    val current = r.value(Metric.BATTERY_CURRENT)
    val temperature = r.channels(Metric.TEMPERATURE).firstOrNull()?.second
    return DeviceDetail(
        hero = hero("État de charge", r.value(Metric.SOC), "%", 0),
        history = HistorySpec(Metric.SOC, "Charge sur 24 h", "%", 0, fixedPercent = true),
        sections = listOfNotNull(
            section(
                "Mesures",
                row("Tension", voltage, "V", 2),
                row("Courant (+ = charge)", current, "A", 1),
                row("Puissance (+ = charge)", power(voltage, current), "W", 0, CALCULATED),
                row("Ah consommés", r.value(Metric.CONSUMED_AH), "Ah", 1),
                text("Autonomie restante", r.sample(Metric.TIME_TO_GO), "annoncée par le shunt"),
            ),
            section(
                "Entrées auxiliaires",
                row("Tension point milieu", r.value(Metric.MID_VOLTAGE), "V", 2),
                row("Tension auxiliaire", r.value(Metric.AUX_VOLTAGE), "V", 2),
                row("Température", temperature, "°C", 1),
            ),
            section("Alarmes", text("Raison de l'alarme", r.sample(Metric.ALARM_REASON))),
        ),
    )
}

private fun dcdcDetail(r: Reading): DeviceDetail =
    DeviceDetail(
        hero = hero("Tension de sortie", r.value(Metric.OUTPUT_VOLTAGE), "V", 2),
        history = HistorySpec(Metric.OUTPUT_VOLTAGE, "Tension de sortie sur 24 h", "V", 2),
        sections = listOfNotNull(
            section(
                "Mesures",
                row("Tension d'entrée", r.value(Metric.INPUT_VOLTAGE), "V", 2),
                row("Tension de sortie", r.value(Metric.OUTPUT_VOLTAGE), "V", 2),
            ),
            section(
                "État",
                text("Étape de charge", r.sample(Metric.DEVICE_STATE)),
                text("Erreur", r.sample(Metric.ERROR_CODE)),
                text("Raison d'arrêt", r.sample(Metric.OFF_REASON)),
            ),
        ),
    )
