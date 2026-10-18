package app.voltdeck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.drivers.victron.VictronLinkStatus
import app.voltdeck.storage.MinuteCoverage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Âge lisible d'une mesure : « il y a 12 s », « il y a 3 min ». */
fun ageText(age: Duration): String = when {
    age < 1.seconds -> "à l'instant"
    age.inWholeMinutes < 1 -> "il y a ${age.inWholeSeconds} s"
    age.inWholeHours < 1 -> "il y a ${age.inWholeMinutes} min"
    else -> "il y a ${age.inWholeHours} h"
}

/** Objectif de couverture des minutes (VOL-8). */
const val COVERAGE_TARGET = 0.95

/** « 97 % (1397 sur 1440 min) », ou une explication tant qu'aucune minute n'est attendue. */
fun coverageText(coverage: MinuteCoverage?): String {
    val ratio = coverage?.ratio ?: return "pas encore de mesure"
    return "${(ratio * 100).toInt()} % (${coverage.covered} sur ${coverage.expected} min)"
}

/** Vrai si la couverture est sous l'objectif sur une durée assez longue pour être un vrai trou et pas un démarrage. */
fun coverageBelowTarget(coverage: MinuteCoverage?): Boolean =
    coverage != null && coverage.expected >= MIN_MINUTES_JUDGED && (coverage.ratio ?: 1.0) < COVERAGE_TARGET

private const val MIN_MINUTES_JUDGED = 30

/** Ce que Diagnostic dit d'une tentative de reprise au démarrage : de quoi distinguer « refusé par Android » de « jamais tenté ». */
fun bootAttemptText(attempt: BootAttempt?, now: Instant): String {
    if (attempt == null) return "aucune depuis l'installation"
    val what = when (attempt.outcome) {
        BootOutcome.STARTED -> "service lancé"
        BootOutcome.SETTING_OFF -> "non tentée : réglage désactivé"
        BootOutcome.STOPPED_BY_USER -> "non tentée : collecte arrêtée"
        BootOutcome.NO_DEVICE -> "non tentée : aucun équipement"
        BootOutcome.NO_PERMISSION -> "non tentée : permission Bluetooth retirée"
        BootOutcome.REFUSED -> "refusée par Android${attempt.detail?.let { " ($it)" }.orEmpty()}"
        BootOutcome.FAILED -> "échec inattendu${attempt.detail?.let { " ($it)" }.orEmpty()}"
    }
    return "$what, ${ageText(now - attempt.at)}"
}

/** Un refus d'Android ou un droit retiré demandent une action de l'utilisateur (restriction du constructeur, droit à rendre). */
fun bootAttemptIsProblem(attempt: BootAttempt?) =
    attempt != null && (attempt.outcome == BootOutcome.REFUSED || attempt.outcome == BootOutcome.FAILED || attempt.outcome == BootOutcome.NO_PERMISSION)

/**
 * Le service ne tourne toujours pas depuis son dernier échec. Un échec suivi d'un démarrage reste affiché, mais n'alerte plus.
 * L'ordre ne se fie à l'horloge murale que faute d'autre repère : elle peut être en retard ou en avance au démarrage du
 * téléphone (heure réseau pas encore reçue). On compare d'abord les compteurs de démarrages, puis, dans un même
 * démarrage, le temps écoulé depuis le démarrage.
 */
fun serviceFailureUnresolved(failure: BootAttempt?, lastStart: BootAttempt?): Boolean {
    if (failure == null) return false
    if (lastStart == null) return true
    val failureBoot = failure.bootCount
    val startBoot = lastStart.bootCount
    if (failureBoot != null && startBoot != null) {
        if (startBoot != failureBoot) return startBoot < failureBoot
        val failureElapsed = failure.sinceBoot
        val startElapsed = lastStart.sinceBoot
        if (failureElapsed != null && startElapsed != null) return startElapsed < failureElapsed
    }
    return lastStart.at < failure.at
}

fun serviceFailureLabel(failure: BootAttempt) =
    if (failure.outcome == BootOutcome.FAILED) "Échec inattendu du service" else "Service refusé par Android"

fun serviceFailurePill(failure: BootAttempt) =
    if (failure.outcome == BootOutcome.FAILED) "Le service a échoué de façon inattendue : à signaler" else "Android a refusé le service de collecte"

/** Quand l'échec a eu lieu, à quel démarrage du téléphone il se rattache, et quand le service a redémarré depuis. */
fun serviceFailureText(failure: BootAttempt, lastStart: BootAttempt?, now: Instant): String {
    val kind = failure.detail ?: "refus"
    val boot = failure.bootCount?.let { ", démarrage n° $it" }.orEmpty()
    val since = when {
        lastStart == null || serviceFailureUnresolved(failure, lastStart) -> ", pas relancé depuis"
        else -> ", relancé ${ageText(now - lastStart.at)}"
    }
    return "$kind, ${ageText(now - failure.at)}$boot$since"
}

/**
 * Le téléphone a redémarré depuis l'activation du réglage et le récepteur n'a rien noté pour ce démarrage : Android ne
 * l'a pas appelé (démarrage automatique bloqué par le constructeur, application arrêtée de force). Sans le compteur de
 * démarrages, on ne peut pas conclure et on ne crie pas.
 */
fun bootReceiverNotCalled(restartAfterBoot: Boolean, enabledAtBootCount: Int?, currentBootCount: Int?, attempt: BootAttempt?): Boolean {
    if (!restartAfterBoot || enabledAtBootCount == null || currentBootCount == null) return false
    if (currentBootCount <= enabledAtBootCount) return false
    return attempt?.bootCount != currentBootCount
}

@Composable
fun DiagnosticScreen(runtime: VoltdeckRuntime, background: BackgroundCollection, onBack: () -> Unit) {
    val p = LocalPalette.current
    val session by runtime.session.collectAsState()
    val latest by runtime.latest.collectAsState()
    val recorder by runtime.recorderState.collectAsState()
    val database by runtime.database.collectAsState()
    val statuses by session.linkStatus.collectAsState()
    val counters by session.jbdCounters.collectAsState()
    val scanError by session.scanError.collectAsState()
    var now by remember { mutableStateOf(session.clock.now()) }
    LaunchedEffect(session) {
        while (true) {
            now = session.clock.now()
            delay(1.seconds)
        }
    }
    var coverage by remember { mutableStateOf<Map<DeviceId, MinuteCoverage?>>(emptyMap()) }
    LaunchedEffect(session, database) {
        while (true) {
            val at = session.clock.now()
            coverage = withContext(Dispatchers.Default) { session.devices.associate { it.deviceId to runtime.coverage(it.deviceId, at) } }
            delay(30.seconds)
        }
    }
    val demo = session is DemoSession

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundButton("Retour", onBack, BackIcon)
            Txt("Diagnostic", 24.sp, FontWeight.Bold)
        }

        Card(Modifier.fillMaxWidth()) {
            Txt("Collecte", 17.sp, FontWeight.Bold)
            Line("Source", if (demo) "Simulateur (horloge ×${DEMO_SPEED.toInt()})" else "Équipements réels")
            Line("Base de données", if (database == null) "aucune" else if (demo) "démo, repart à zéro" else "réelle")
            Line("Dernière écriture", recorder.lastWrite?.let { ageText(now - it) } ?: "aucune")
            Line("Cumuls en attente d'écriture", recorder.pending.toString())
            recorder.error?.let { Pill(it, Tone.CRIT) }
        }

        Card(Modifier.fillMaxWidth()) {
            Txt("Bluetooth", 17.sp, FontWeight.Bold)
            if (scanError == null) Pill(if (demo) "Simulé" else "Scan actif", Tone.GOOD) else Pill(scanError!!, Tone.WARN)
            Line("Trames Victron tronquées", session.truncatedVictronFrames.toString())
        }

        if (background.supported) {
            val bootAttempt = background.lastBootAttempt
            val serviceFailure = background.lastServiceFailure
            val wallNow = Clock.System.now()
            Card(Modifier.fillMaxWidth()) {
                Txt("Reprise après redémarrage", 17.sp, FontWeight.Bold)
                Line("Réglage", if (background.restartAfterBoot) "activé" else "désactivé")
                Line("Dernière tentative", bootAttemptText(bootAttempt, wallNow))
                if (bootAttemptIsProblem(bootAttempt)) Pill("La collecte n'a pas repris seule", Tone.WARN)
                if (bootReceiverNotCalled(background.restartAfterBoot, background.restartEnabledAtBootCount, background.bootCount, bootAttempt)) {
                    Pill("Voltdeck n'a pas été appelé au dernier démarrage du téléphone", Tone.WARN)
                }
                serviceFailure?.let {
                    Line(serviceFailureLabel(it), serviceFailureText(it, background.lastServiceStart, wallNow))
                    if (serviceFailureUnresolved(it, background.lastServiceStart)) Pill(serviceFailurePill(it), Tone.WARN)
                }
            }
        }

        session.devices.forEach { device ->
            val reading = latest[device.deviceId]
            Card(Modifier.fillMaxWidth()) {
                Txt(session.title(device.deviceId), 17.sp, FontWeight.Bold)
                Line("Lecture", if (device.role == DeviceRole.BATTERY) "connexion (JBD)" else "écoute des annonces (Victron)")
                Line("Dernière mesure", reading?.let { ageText(now - it.at) } ?: "aucune")
                Line(
                    if (device.role == DeviceRole.BATTERY) "Mesures par minute" else "Annonces par minute",
                    runtime.readingsPerMinute(device.deviceId, now).toString(),
                )
                Line("Couverture des minutes (24 h)", coverageText(coverage[device.deviceId]))
                if (coverageBelowTarget(coverage[device.deviceId])) Pill("Sous l'objectif de ${(COVERAGE_TARGET * 100).toInt()} %", Tone.WARN)
                if (device.role == DeviceRole.BATTERY) {
                    val c = counters[device.deviceId]
                    Line("Mesures", (c?.readings ?: 0).toString())
                    Line("Trames rejetées", (c?.rejectedFrames ?: 0).toString())
                    Line("Délais dépassés", (c?.timeouts ?: 0).toString())
                    Line("Requêtes répétées", (c?.writeRetries ?: 0).toString())
                    Line("Reconnexions", (c?.reconnections ?: 0).toString())
                } else {
                    val status = statuses[device.deviceId] ?: VictronLinkStatus.WAITING
                    Line(
                        "Clé",
                        when (status) {
                            VictronLinkStatus.OK -> "bonne"
                            VictronLinkStatus.WAITING -> "en attente d'annonces"
                            VictronLinkStatus.NO_KEY -> "absente"
                            VictronLinkStatus.WRONG_KEY -> "refusée"
                        },
                    )
                }
                if (reading != null && now - reading.at > STALE) Pill("Plus de mesure depuis ${ageText(now - reading.at).removePrefix("il y a ")}", Tone.WARN)
            }
        }
        Txt("Aucune adresse ni clé n'est affichée ici : le diagnostic peut être partagé tel quel.", 13.sp, color = p.muted)
    }
}

private val STALE = 2.minutes

@Composable
private fun Line(label: String, value: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Txt(label, 14.sp, color = p.ink2, modifier = Modifier.weight(1f))
        Txt(value, 14.sp, FontWeight.SemiBold)
    }
}
