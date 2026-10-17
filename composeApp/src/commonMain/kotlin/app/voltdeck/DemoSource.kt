package app.voltdeck

import app.voltdeck.core.Reading
import app.voltdeck.simulator.AcceleratedClock
import app.voltdeck.simulator.SimulatedPlant
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

const val DEMO_SPEED = 60.0
private const val DEMO_SEED = 1L

/** Début de la journée simulée : 06 h 30 locale du jour courant, juste avant le lever du soleil simulé. */
fun demoStart(now: Instant, zone: TimeZone): Instant =
    now.toLocalDateTime(zone).date.atStartOfDayIn(zone) + 6.hours + 30.minutes

/** Flux de mesures simulées, accéléré ×[DEMO_SPEED] : une seconde réelle = une minute simulée. */
fun demoReadings(real: Clock, zone: TimeZone = TimeZone.currentSystemDefault()): Flow<Reading> {
    val start = demoStart(real.now(), zone)
    return SimulatedPlant(DEMO_SEED, start, zone).readings(AcceleratedClock(real, start, DEMO_SPEED))
}
