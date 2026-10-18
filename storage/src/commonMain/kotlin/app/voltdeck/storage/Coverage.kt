package app.voltdeck.storage

import app.voltdeck.core.DeviceId
import app.voltdeck.core.Metric
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
 * (l'énergie intégrée n'en est pas une : elle déborde sur les minutes voisines d'un trou court ; les cellules et les
 * sondes non plus, pour que la lecture reste bornée par la clé primaire).
 *
 * - La fenêtre se termine avant les minutes pas encore écrites : une minute close n'est écrite qu'au flush suivant,
 *   [flushDelay] plus tard au plus. Sans cette marge, la dernière minute compterait comme un trou. [flushDelay] doit
 *   rester supérieur à la période de flush de la collecte plus une minute.
 * - Elle ne commence pas avant l'enregistrement de l'équipement : un équipement ajouté il y a 2 h n'a pas 22 h de trou,
 *   mais un équipement enregistré qui n'a rien écrit depuis des heures montre bien ce trou. [notBefore] relève ce début
 *   quand l'enregistrement ne dit rien de la collecte (la démo enregistre ses équipements au début d'un historique simulé).
 */
class CoverageReader(private val database: VoltdeckDatabase, private val flushDelay: Duration = DEFAULT_FLUSH_DELAY) {
    fun minutes(device: DeviceId, now: Instant, window: Duration = 24.hours, notBefore: Instant? = null): MinuteCoverage {
        val end = floorTo((now - flushDelay).epochSeconds, MINUTE_SECONDS)
        val created = database.deviceQueries.selectCreatedAt(device.value).executeAsOneOrNull() ?: return MinuteCoverage(0, 0)
        val start = maxOf(end - window.inWholeSeconds, floorTo(created, MINUTE_SECONDS), notBefore?.let { floorTo(it.epochSeconds, MINUTE_SECONDS) } ?: 0L)
        if (end <= start) return MinuteCoverage(0, 0)
        val covered = database.minuteQueries.countRawMinutes(device.value, REFERENCE_METRICS, start, end).executeAsOne()
        return MinuteCoverage(covered.toInt(), ((end - start) / MINUTE_SECONDS).toInt())
    }

    companion object {
        /** Une minute close est écrite au flush suivant : période de flush de la collecte plus une minute. */
        val DEFAULT_FLUSH_DELAY = 90.seconds
        private val REFERENCE_METRICS = (Metric.entries - DERIVED_METRICS - INDEXED_METRICS).map { it.name }
    }
}
