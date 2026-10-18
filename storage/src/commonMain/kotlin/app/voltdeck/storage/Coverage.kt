package app.voltdeck.storage

import app.voltdeck.core.DeviceId
import app.voltdeck.storage.db.VoltdeckDatabase
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** [covered] minutes portant au moins une mesure, sur [expected] minutes attendues. */
data class MinuteCoverage(val covered: Int, val expected: Int) {
    /** Entre 0 et 1, `null` tant qu'aucune minute n'est attendue. */
    val ratio: Double? get() = if (expected == 0) null else covered.toDouble() / expected
}

/**
 * Couverture des minutes d'un équipement : part des minutes de la fenêtre qui ont au moins une mesure brute en base
 * (l'énergie intégrée n'en est pas une : elle déborde sur les minutes voisines d'un trou court).
 *
 * - La fenêtre se termine avant les minutes pas encore écrites : une minute close n'est écrite qu'au flush suivant,
 *   [flushDelay] plus tard au plus. Sans cette marge, la dernière minute compterait comme un trou.
 * - Elle ne commence pas avant la première mesure de l'équipement : un équipement ajouté il y a 2 h n'a pas 22 h de trou.
 */
class CoverageReader(private val database: VoltdeckDatabase, private val flushDelay: Duration = FLUSH_DELAY) {
    fun minutes(device: DeviceId, now: Instant, window: Duration = 24.hours): MinuteCoverage {
        val end = floorTo((now - flushDelay).epochSeconds, MINUTE_SECONDS)
        val first = database.minuteQueries.firstRawMinute(device.value, RAW_EXCLUDED).executeAsOne().first_bucket ?: return MinuteCoverage(0, 0)
        val start = maxOf(end - window.inWholeSeconds, first)
        if (end <= start) return MinuteCoverage(0, 0)
        val covered = database.minuteQueries.countRawMinutes(device.value, RAW_EXCLUDED, start, end).executeAsOne()
        return MinuteCoverage(covered.toInt(), ((end - start) / MINUTE_SECONDS).toInt())
    }

    private companion object {
        val FLUSH_DELAY = 90.seconds
        val RAW_EXCLUDED = DERIVED_METRICS.map { it.name }
    }
}
