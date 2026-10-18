package app.voltdeck

import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Metric
import app.voltdeck.core.Reading
import app.voltdeck.drivers.KnownDevice
import kotlin.math.abs

data class SolarTile(
    val powerW: Double?,
    val batteryVoltage: Double?,
    val batteryCurrent: Double?,
    val yieldTodayKwh: Double?,
    val state: Int?,
)

data class DcdcTile(val inputVoltage: Double?, val outputVoltage: Double?, val state: Int?) {
    val running: Boolean get() = state != null && state != VICTRON_STATE_OFF
}

enum class BatterySource { BMS, SHUNT }

enum class BatteryTrend { CHARGING, DISCHARGING, IDLE }

data class BatteryTile(
    val soc: Double?,
    val voltage: Double?,
    val current: Double?,
    val temperature: Double?,
    val cellMin: Double?,
    val cellMax: Double?,
    val remainingAh: Double?,
    val nominalAh: Double?,
    val cycles: Double?,
    val source: BatterySource,
    /** [cycles] vient du flux du shunt et de la capacité saisie, pas d'un compteur de BMS. */
    val cyclesEstimated: Boolean = false,
) {
    val powerW: Double? get() = if (voltage != null && current != null) voltage * current else null

    val trend: BatteryTrend
        get() = when {
            current == null || abs(current) < IDLE_AMPS -> BatteryTrend.IDLE
            current > 0 -> BatteryTrend.CHARGING
            else -> BatteryTrend.DISCHARGING
        }
}

enum class AutonomyGap { CHARGING, NO_ESTIMATE }

/**
 * Autonomie lue sur un shunt de batterie (`TIME_TO_GO`), sinon estimée sur l'historique ([estimated]),
 * sinon la raison de son absence.
 */
data class Autonomy(val minutes: Double?, val gap: AutonomyGap?, val estimated: Boolean = false)

/** Consommation calculée = sources mesurées côté batterie − flux net de la batterie (VOL-1 §11.5). */
data class Consumption(val watts: Double, val incomplete: Boolean)

data class CircuitTile(val deviceId: DeviceId, val name: String, val isSource: Boolean, val powerW: Double?, val current: Double?)

data class Overview(
    val solar: SolarTile?,
    val dcdc: DcdcTile?,
    val battery: BatteryTile?,
    val autonomy: Autonomy,
    val consumption: Consumption?,
    val sources: List<CircuitTile>,
    val loads: List<CircuitTile>,
)

/**
 * « 3 cycles » pour un compteur de BMS. Une estimation est un minimum (les trous d'enregistrement sous-comptent les Ah déchargés) :
 * « au moins 0,4 cycles », avec une décimale sous 10 cycles, sinon un cycle équivalent d'écart n'a plus de sens.
 */
fun cyclesLabel(cycles: Double, estimated: Boolean): String =
    if (estimated) "au moins ${frDecimal(cycles, if (cycles < 10.0) 1 else 0)} cycles" else "${frDecimal(cycles, 0)} cycles"

/** Capacité nominale saisie sur le shunt placé sur le négatif de la batterie, `null` si aucune. */
fun nominalCapacityAh(devices: List<KnownDevice>, setupOf: (DeviceId) -> ShuntSetup): Double? =
    devices.filter { it.role == DeviceRole.SHUNT }.map { setupOf(it.deviceId) }.firstOrNull { it.placement == ShuntPlacement.BATTERY && it.capacityAh != null }?.capacityAh

fun buildOverview(
    devices: List<KnownDevice>,
    latest: Map<DeviceId, Reading>,
    setupOf: (DeviceId) -> ShuntSetup,
    /** Estimation de [app.voltdeck.storage.SystemStats.autonomy] quand elle est de nature « estimée ». */
    estimatedAutonomyHours: Double? = null,
    /** Estimation de [app.voltdeck.storage.SystemStats.cycles] quand elle est de nature « estimée » ; absente sans capacité saisie. */
    estimatedCycles: Double? = null,
): Overview {
    fun Reading.value(metric: Metric, channel: Int = 0) = samples.firstOrNull { it.metric == metric && it.channel == channel }?.value
    fun Reading.values(metric: Metric) = samples.filter { it.metric == metric }.map { it.value }

    val chargers = devices.filter { it.role == DeviceRole.SOLAR_CHARGER }
    val chargerReadings = chargers.mapNotNull { latest[it.deviceId] }
    val solar = chargerReadings.takeIf { it.isNotEmpty() }?.let { readings ->
        SolarTile(
            powerW = readings.mapNotNull { it.value(Metric.PV_POWER) }.takeIf { it.isNotEmpty() }?.sum(),
            batteryVoltage = readings.firstNotNullOfOrNull { it.value(Metric.BATTERY_VOLTAGE) },
            batteryCurrent = readings.mapNotNull { it.value(Metric.BATTERY_CURRENT) }.takeIf { it.isNotEmpty() }?.sum(),
            yieldTodayKwh = readings.mapNotNull { it.value(Metric.YIELD_TODAY) }.takeIf { it.isNotEmpty() }?.sum(),
            state = readings.firstNotNullOfOrNull { it.value(Metric.DEVICE_STATE) }?.toInt(),
        )
    }

    val dcdc = devices.firstOrNull { it.role == DeviceRole.DCDC }?.let { latest[it.deviceId] }?.let {
        DcdcTile(it.value(Metric.INPUT_VOLTAGE), it.value(Metric.OUTPUT_VOLTAGE), it.value(Metric.DEVICE_STATE)?.toInt())
    }

    val shunts = devices.filter { it.role == DeviceRole.SHUNT }
    val batteryShunt = shunts.firstOrNull { setupOf(it.deviceId).placement == ShuntPlacement.BATTERY }?.let { latest[it.deviceId] }
    val bms = devices.firstOrNull { it.role == DeviceRole.BATTERY }?.let { latest[it.deviceId] }

    val battery = if (bms == null && batteryShunt == null) null else {
        val primary = batteryShunt ?: bms!!
        val cells = bms?.values(Metric.CELL_VOLTAGE).orEmpty()
        val bmsCycles = bms?.value(Metric.CYCLES)
        BatteryTile(
            soc = primary.value(Metric.SOC) ?: bms?.value(Metric.SOC),
            voltage = primary.value(Metric.BATTERY_VOLTAGE) ?: bms?.value(Metric.BATTERY_VOLTAGE),
            current = primary.value(Metric.BATTERY_CURRENT) ?: bms?.value(Metric.BATTERY_CURRENT),
            temperature = (bms?.values(Metric.TEMPERATURE)?.takeIf { it.isNotEmpty() } ?: batteryShunt?.values(Metric.TEMPERATURE))?.maxOrNull(),
            cellMin = cells.minOrNull(),
            cellMax = cells.maxOrNull(),
            remainingAh = bms?.value(Metric.REMAINING_CAPACITY),
            nominalAh = bms?.value(Metric.NOMINAL_CAPACITY),
            cycles = bmsCycles ?: estimatedCycles,
            source = if (batteryShunt != null) BatterySource.SHUNT else BatterySource.BMS,
            cyclesEstimated = bmsCycles == null && estimatedCycles != null,
        )
    }

    val circuits = shunts.mapNotNull { device ->
        val setup = setupOf(device.deviceId)
        if (setup.placement != ShuntPlacement.LOAD && setup.placement != ShuntPlacement.SOURCE) return@mapNotNull null
        val reading = latest[device.deviceId]
        val volts = reading?.value(Metric.BATTERY_VOLTAGE)
        val amps = reading?.value(Metric.BATTERY_CURRENT)?.let(::abs)
        CircuitTile(
            deviceId = device.deviceId,
            name = setup.displayName,
            isSource = setup.placement == ShuntPlacement.SOURCE,
            powerW = if (volts != null && amps != null) volts * amps else null,
            current = amps,
        )
    }
    val sources = circuits.filter { it.isSource }

    val autonomy = when {
        batteryShunt?.value(Metric.TIME_TO_GO) != null -> Autonomy(batteryShunt.value(Metric.TIME_TO_GO), null)
        battery?.trend == BatteryTrend.CHARGING -> Autonomy(null, AutonomyGap.CHARGING)
        battery != null && estimatedAutonomyHours != null -> Autonomy(estimatedAutonomyHours * 60.0, null, estimated = true)
        else -> Autonomy(null, AutonomyGap.NO_ESTIMATE)
    }

    val consumption = battery?.powerW?.let { batteryW ->
        val solarW = chargerReadings.map { r ->
            val v = r.value(Metric.BATTERY_VOLTAGE)
            val a = r.value(Metric.BATTERY_CURRENT)
            if (v != null && a != null) v * a else r.value(Metric.PV_POWER)
        }
        val unknownSource = solarW.any { it == null } || chargerReadings.size < chargers.size || sources.any { it.powerW == null }
        val sourcesW = solarW.sumOf { it ?: 0.0 } + sources.sumOf { it.powerW ?: 0.0 }
        Consumption((sourcesW - batteryW).coerceAtLeast(0.0), incomplete = unknownSource || dcdc?.running == true)
    }

    return Overview(solar, dcdc, battery, autonomy, consumption, sources, circuits.filter { !it.isSource })
}

private const val IDLE_AMPS = 0.2
private const val VICTRON_STATE_OFF = 0
