package app.voltdeck

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Cadence des mesures reçues, sur une minute glissante. Les listes sont immuables : l'écran peut les lire pendant que le collecteur les remplace. */
object ReadingRate {
    private val WINDOW = 1.minutes

    /** Ajoute une réception à [at] et oublie celles qui ont plus d'une minute. */
    fun record(times: List<Instant>, at: Instant): List<Instant> = times.filter { at - it < WINDOW } + at

    fun perMinute(times: List<Instant>, now: Instant): Int = times.count { now - it < WINDOW }
}
