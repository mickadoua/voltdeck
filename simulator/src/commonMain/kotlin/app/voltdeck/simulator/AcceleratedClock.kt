package app.voltdeck.simulator

import kotlin.time.Clock
import kotlin.time.Instant

/** Horloge simulée qui s'écoule [factor] fois plus vite que [real] à partir de [simulatedStart]. */
class AcceleratedClock(
    private val real: Clock,
    private val simulatedStart: Instant,
    private val factor: Double = 60.0,
    private val realStart: Instant = real.now(),
) : Clock {
    init {
        require(factor > 0.0) { "factor doit être positif" }
    }

    override fun now(): Instant = simulatedStart + (real.now() - realStart) * factor
}
