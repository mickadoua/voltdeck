package app.voltdeck

import app.voltdeck.core.Reading
import app.voltdeck.drivers.KnownDevice
import app.voltdeck.drivers.jbd.JbdDriver
import app.voltdeck.simulator.AcceleratedClock
import app.voltdeck.simulator.JbdScenario
import app.voltdeck.simulator.SimulatedPlant
import app.voltdeck.simulator.SimulatedTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

const val DEMO_SPEED = 60.0
private const val DEMO_SEED = 1L

/** Début de la journée simulée : 06 h 30 locale du jour courant, juste avant le lever du soleil simulé. */
fun demoStart(now: Instant, zone: TimeZone): Instant =
    now.toLocalDateTime(zone).date.atStartOfDayIn(zone) + 6.hours + 30.minutes

/**
 * Flux de mesures simulées, accéléré ×[DEMO_SPEED] : une seconde réelle = une minute simulée.
 * La batterie passe par le vrai [JbdDriver] (trames JBD simulées, découpées en notifications de 20 octets) ; le MPPT
 * vient directement du simulateur en attendant son pilote Victron (VOL-5). À collecter depuis un seul thread : le
 * simulateur d'installation n'est pas thread-safe.
 */
fun demoReadings(
    real: Clock,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    jbdScenario: JbdScenario = JbdScenario.Nominal,
): Flow<Reading> = channelFlow {
    val start = demoStart(real.now(), zone)
    val clock = AcceleratedClock(real, start, DEMO_SPEED)
    val plant = SimulatedPlant(DEMO_SEED, start, zone)
    val transport = SimulatedTransport(jbdScenario = jbdScenario, clock = clock, plant = plant)
    val battery = KnownDevice(SimulatedPlant.BATTERY_ID, SimulatedTransport.BATTERY_ID)
    launch {
        JbdDriver(clock, pollInterval = 1.seconds).readings(battery, transport).collect { send(it) }
    }
    while (true) {
        plant.readingsAt(clock.now()).filter { it.deviceId == SimulatedPlant.MPPT_ID }.forEach { send(it) }
        delay(1.seconds)
    }
}
