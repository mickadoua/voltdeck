package app.voltdeck.simulator

import app.voltdeck.ble.AllowedCommand
import app.voltdeck.core.Metric
import app.voltdeck.core.Reading
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Scénarios nommés du BMS JBD simulé (VOL-1 §7). Chaque scénario ne change que ce que le nom annonce.
 * [id] est le nom stable (snake_case) utilisé par les captures et les tests.
 */
sealed class JbdScenario(val id: String) {
    /** Début de la journée simulée pour cette horloge. Par défaut : l'heure de l'horloge. */
    internal open fun plantStart(now: Instant): Instant = now

    /** Fonctionnement normal : rien d'exceptionnel. */
    data object Nominal : JbdScenario("nominal")

    /** La journée simulée commence à 22 h (UTC) : pas de soleil, la batterie se décharge. */
    data object NightDischarge : JbdScenario("night_discharge") {
        override fun plantStart(now: Instant): Instant =
            now.toLocalDateTime(TimeZone.UTC).date.atStartOfDayIn(TimeZone.UTC) + 22.hours
    }

    /** Le BMS disparaît [outage] après la première connexion + [after], puis revient. */
    data class BmsDisconnects(val after: Duration = 30.seconds, val outage: Duration = 3.minutes) :
        JbdScenario("bms_disconnects")

    /** Une réponse sur [every] arrive avec une mauvaise somme de contrôle. */
    data class CorruptedFrame(val every: Int = 3) : JbdScenario("corrupted_frame") {
        init {
            require(every >= 2) { "every doit être au moins 2 (la première réponse reste juste)" }
        }
    }

    /** Surtension de la cellule 2 et FET de charge ouvert, [after] la première connexion. */
    data class BmsProtectionAlarm(val after: Duration = 10.seconds) : JbdScenario("bms_protection_alarm")

    /** Les [failures] premières écritures après chaque connexion échouent (cas de BMS_BLE-HA#279). */
    data class FirstWriteFails(val failures: Int = 1) : JbdScenario("first_write_fails") {
        init {
            require(failures >= 0) { "failures ne peut pas être négatif" }
        }
    }
}

/** L'écriture simulée a échoué (première écriture capricieuse, ou liaison perdue). */
class SimulatedLinkException(message: String) : IllegalStateException(message)

/**
 * BMS JBD simulé : fabrique de vraies trames de réponse (DD … 77, somme de contrôle `0x10000 − Σ`) à partir de la
 * batterie du [SimulatedPlant]. Écrit indépendamment du décodeur du pilote, pour que les tests croisent les deux.
 * Valeurs synthétiques : aucune ne vient du matériel du fondateur.
 */
internal class SimulatedJbdBms(
    private val plant: SimulatedPlant,
    private val scenario: JbdScenario,
    private val clock: Clock,
) {
    private val timeShift: Duration = scenario.plantStart(clock.now()) - clock.now()
    private var firstConnectAt: Instant? = null
    private var basicInfoResponses = 0

    /** À appeler à chaque tentative de connexion. */
    fun markConnectAttempt() {
        if (firstConnectAt == null) firstConnectAt = clock.now()
    }

    fun isOffline(): Boolean {
        val scenario = scenario as? JbdScenario.BmsDisconnects ?: return false
        val since = sinceFirstConnect() ?: return false
        return since >= scenario.after && since < scenario.after + scenario.outage
    }

    private fun isAlarmActive(): Boolean {
        val scenario = scenario as? JbdScenario.BmsProtectionAlarm ?: return false
        return (sinceFirstConnect() ?: Duration.ZERO) >= scenario.after
    }

    private fun sinceFirstConnect(): Duration? = firstConnectAt?.let { clock.now() - it }

    fun failuresPerConnection(): Int = (scenario as? JbdScenario.FirstWriteFails)?.failures ?: 0

    /** Réponse à [cmd], découpée en notifications de [CHUNK] octets au plus. */
    fun respond(cmd: AllowedCommand): List<ByteArray> {
        val battery = batteryReading()
        val frame = when (cmd) {
            AllowedCommand.JbdBasicInfo -> {
                basicInfoResponses++
                response(0x03, basicInfo(battery))
            }
            AllowedCommand.JbdCellVoltages -> response(0x04, cellVoltages(battery))
            AllowedCommand.JbdHardwareVersion -> response(0x05, HARDWARE_VERSION.encodeToByteArray())
        }
        if (cmd == AllowedCommand.JbdBasicInfo && isCorruptedTurn()) frame[frame.size - 2] = (frame[frame.size - 2].toInt() xor 0xFF).toByte()
        return frame.toList().chunked(CHUNK).map { it.toByteArray() }
    }

    private fun isCorruptedTurn(): Boolean {
        val scenario = scenario as? JbdScenario.CorruptedFrame ?: return false
        return basicInfoResponses % scenario.every == 0
    }

    private fun batteryReading(): Reading {
        val simNow = clock.now() + timeShift
        return plant.readingsAt(simNow).first { it.deviceId == SimulatedPlant.BATTERY_ID }
    }

    private fun Reading.value(metric: Metric, channel: Int = 0): Double =
        samples.first { it.metric == metric && it.channel == channel }.value

    private fun cellMillivolts(battery: Reading): IntArray {
        val cells = IntArray(CELLS) { (battery.value(Metric.CELL_VOLTAGE, it) * 1000).roundToInt() }
        if (isAlarmActive()) cells[1] = ALARM_CELL_MV
        return cells
    }

    private fun basicInfo(battery: Reading): ByteArray {
        val cells = cellMillivolts(battery)
        val packCentivolts = cells.sum() / 10
        val alarm = isAlarmActive()
        val out = ArrayList<Byte>()
        fun u16(v: Int) {
            out += (v shr 8).toByte()
            out += v.toByte()
        }
        u16(packCentivolts)
        u16((battery.value(Metric.BATTERY_CURRENT) * 100).roundToInt())
        u16((battery.value(Metric.REMAINING_CAPACITY) * 100).roundToInt())
        u16((battery.value(Metric.NOMINAL_CAPACITY) * 100).roundToInt())
        u16(battery.value(Metric.CYCLES).roundToInt())
        u16(PRODUCTION_DATE)
        u16(0)
        u16(0)
        u16(if (alarm) 1 else 0)
        out += 0x10
        out += battery.value(Metric.SOC).roundToInt().toByte()
        out += (if (alarm) 0x02 else 0x03).toByte()
        out += CELLS.toByte()
        out += 2
        for (probe in 0..1) u16((battery.value(Metric.TEMPERATURE, probe) * 10).roundToInt() + 2731)
        return out.toByteArray()
    }

    private fun cellVoltages(battery: Reading): ByteArray {
        val out = ArrayList<Byte>()
        for (mv in cellMillivolts(battery)) {
            out += (mv shr 8).toByte()
            out += mv.toByte()
        }
        return out.toByteArray()
    }

    private fun response(register: Int, data: ByteArray): ByteArray {
        val body = byteArrayOf(0x00, data.size.toByte()) + data
        val checksum = (0x10000 - body.sumOf { it.toInt() and 0xFF }) and 0xFFFF
        return byteArrayOf(0xDD.toByte(), register.toByte()) + body +
            byteArrayOf((checksum shr 8).toByte(), checksum.toByte(), 0x77)
    }

    private companion object {
        const val CELLS = 4
        const val CHUNK = 20
        const val ALARM_CELL_MV = 3700
        const val PRODUCTION_DATE = 0x2C7C
        const val HARDWARE_VERSION = "SIM-JBD-4S"
    }
}
