package app.voltdeck

import kotlin.test.Test
import app.voltdeck.storage.MinuteCoverage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DiagnosticTest {
    @Test
    fun agesAreReadable() {
        assertEquals("à l'instant", ageText(300.milliseconds))
        assertEquals("il y a 12 s", ageText(12.seconds))
        assertEquals("il y a 3 min", ageText(3.minutes + 20.seconds))
        assertEquals("il y a 2 h", ageText(2.hours + 5.minutes))
    }

    private val t0 = Instant.parse("2026-06-21T10:00:00Z")

    @Test
    fun ratePerMinuteCountsOnlyTheLastMinute() {
        var times = emptyList<Instant>()
        (0 until 120).forEach { times = ReadingRate.record(times, t0 + it.seconds) }
        assertEquals(60, ReadingRate.perMinute(times, t0 + 119.seconds))
        assertEquals(30, ReadingRate.perMinute(times, t0 + 149.seconds), "la cadence retombe quand les annonces cessent")
        assertEquals(0, ReadingRate.perMinute(times, t0 + 3.minutes))
        assertTrue(times.size <= 61, "la liste ne grossit pas sans limite : ${times.size}")
    }

    @Test
    fun coverageIsShownAsPercentAndMinutes() {
        assertEquals("97 % (1397 sur 1440 min)", coverageText(MinuteCoverage(1397, 1440)))
        assertEquals("94 % (94 sur 100 min)", coverageText(MinuteCoverage(94, 100)), "jamais arrondi vers le haut au-dessus de l'objectif")
        assertEquals("pas encore de mesure", coverageText(MinuteCoverage(0, 0)))
        assertEquals("pas encore de mesure", coverageText(null))
    }

    @Test
    fun coverageUnderTheTargetIsFlaggedOnlyOnceItMeansSomething() {
        assertTrue(coverageBelowTarget(MinuteCoverage(940, 1000)))
        assertFalse(coverageBelowTarget(MinuteCoverage(950, 1000)), "95 % pile atteint l'objectif")
        assertFalse(coverageBelowTarget(MinuteCoverage(1, 5)), "5 minutes de démarrage ne sont pas un trou")
        assertFalse(coverageBelowTarget(null))
    }

    @Test
    fun aBootAttemptTellsRefusedByAndroidApartFromNeverTried() {
        val at = t0 - 5.minutes
        assertEquals("aucune depuis l'installation", bootAttemptText(null, t0))
        assertEquals("service lancé, il y a 5 min", bootAttemptText(BootAttempt(at, BootOutcome.STARTED), t0))
        assertEquals("non tentée : réglage désactivé, il y a 5 min", bootAttemptText(BootAttempt(at, BootOutcome.SETTING_OFF), t0))
        assertEquals(
            "refusée par Android (IllegalStateException), il y a 5 min",
            bootAttemptText(BootAttempt(at, BootOutcome.REFUSED, "IllegalStateException"), t0),
        )
    }

    @Test
    fun aRebootWithoutAnyTraceIsFlaggedOnlyWhenTheSettingWasOnBeforeIt() {
        val seen = BootAttempt(t0, BootOutcome.STARTED, bootCount = 5)
        assertTrue(bootReceiverNotCalled(true, enabledAtBootCount = 4, currentBootCount = 5, attempt = null), "redémarré, rien noté")
        assertTrue(bootReceiverNotCalled(true, 4, 6, seen), "la trace date d'un démarrage précédent")
        assertFalse(bootReceiverNotCalled(true, 4, 5, seen), "le récepteur a bien été appelé à ce démarrage")
        assertFalse(bootReceiverNotCalled(true, 5, 5, null), "pas de redémarrage depuis l'activation du réglage")
        assertFalse(bootReceiverNotCalled(false, 4, 5, null), "réglage désactivé : rien n'était attendu")
        assertFalse(bootReceiverNotCalled(true, null, 5, null), "compteur inconnu à l'activation : pas de conclusion")
        assertFalse(bootReceiverNotCalled(true, 4, null, null), "compteur illisible : pas de conclusion")
    }

    @Test
    fun onlyAnAndroidRefusalOrARevokedPermissionIsFlaggedAsAProblem() {
        val flagged = BootOutcome.entries.filter { bootAttemptIsProblem(BootAttempt(t0, it)) }
        assertEquals(listOf(BootOutcome.NO_PERMISSION, BootOutcome.REFUSED, BootOutcome.FAILED), flagged)
        assertFalse(bootAttemptIsProblem(null))
    }

    private fun refusal(at: Instant, boot: Int? = 12, sinceBoot: Int? = 30, outcome: BootOutcome = BootOutcome.REFUSED) =
        BootAttempt(at, outcome, "ForegroundServiceStartNotAllowedException", boot, sinceBoot?.seconds)

    private fun start(at: Instant, boot: Int? = 12, sinceBoot: Int? = 60) = BootAttempt(at, BootOutcome.STARTED, null, boot, sinceBoot?.seconds)

    @Test
    fun aRefusalStaysVisibleAfterTheServiceRestartsButOnlyAlertsWhileItIsDown() {
        val refusal = refusal(t0 - 4.hours)
        assertTrue(serviceFailureUnresolved(refusal, null))
        assertFalse(serviceFailureUnresolved(refusal, start(t0 - 3.hours)))
        assertFalse(serviceFailureUnresolved(null, start(t0)))
        assertEquals(
            "ForegroundServiceStartNotAllowedException, il y a 4 h, démarrage n° 12, pas relancé depuis",
            serviceFailureText(refusal, null, t0),
        )
        assertEquals(
            "ForegroundServiceStartNotAllowedException, il y a 4 h, démarrage n° 12, relancé il y a 2 h",
            serviceFailureText(refusal, start(t0 - 2.hours), t0),
        )
    }

    @Test
    fun withinOnePhoneBootTheElapsedTimeOrdersTheEventsNotTheWallClock() {
        assertTrue(
            serviceFailureUnresolved(refusal(t0 - 20.hours, sinceBoot = 30), start(t0 - 12.hours, sinceBoot = 10)),
            "un démarrage antérieur au refus dans le même démarrage du téléphone ne le résout pas, même si l'horloge murale dit l'inverse",
        )
        assertFalse(serviceFailureUnresolved(refusal(t0 - 20.hours, sinceBoot = 30), start(t0 - 30.hours, sinceBoot = 600)), "horloge réglée en arrière après le refus")
    }

    @Test
    fun aLaterPhoneBootDecidesWithoutLookingAtTheWallClock() {
        assertFalse(
            serviceFailureUnresolved(refusal(t0, boot = 12), start(t0 - 10.hours, boot = 13)),
            "démarrage réussi au démarrage suivant : résolu même si l'horloge est en retard",
        )
        assertTrue(
            serviceFailureUnresolved(refusal(t0 - 10.hours, boot = 13), start(t0, boot = 12)),
            "démarrage réussi pendant un démarrage antérieur : non résolu même si l'horloge est en avance",
        )
    }

    @Test
    fun withoutABootCounterTheWallClockIsTheOnlyFallback() {
        assertTrue(serviceFailureUnresolved(refusal(t0, boot = null), start(t0 - 1.hours, boot = 12)))
        assertFalse(serviceFailureUnresolved(refusal(t0 - 1.hours, boot = 12), start(t0, boot = null)))
        assertFalse(serviceFailureUnresolved(refusal(t0 - 1.hours, sinceBoot = null), start(t0, sinceBoot = null)), "même démarrage sans temps écoulé : horloge")
    }

    @Test
    fun anUnexpectedFailureIsNotPresentedAsARefusalByAndroid() {
        val refused = refusal(t0)
        val failed = refusal(t0, outcome = BootOutcome.FAILED)
        assertEquals("Service refusé par Android", serviceFailureLabel(refused))
        assertEquals("Android a refusé le service de collecte", serviceFailurePill(refused))
        assertEquals("Échec inattendu du service", serviceFailureLabel(failed))
        assertEquals("Le service a échoué de façon inattendue : à signaler", serviceFailurePill(failed))
    }
}
