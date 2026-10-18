package app.voltdeck

import kotlin.time.Instant

/** Issue de la dernière tentative de reprise de la collecte au démarrage du téléphone. */
enum class BootOutcome { STARTED, SETTING_OFF, STOPPED_BY_USER, NO_DEVICE, NO_PERMISSION, REFUSED }

/** [detail] : type de l'exception quand Android a refusé le démarrage ([BootOutcome.REFUSED]), jamais de message ni de donnée. */
data class BootAttempt(val at: Instant, val outcome: BootOutcome, val detail: String? = null)

/** Collecte écran éteint (service Android). Sans support de la plateforme, la collecte s'arrête avec l'application. */
interface BackgroundCollection {
    val supported: Boolean
    var enabled: Boolean

    /** Relance la collecte au redémarrage du téléphone. Désactivé par défaut : l'utilisateur doit le demander. */
    var restartAfterBoot: Boolean

    /** Dernière tentative de reprise au démarrage du téléphone, `null` si le téléphone n'a pas redémarré depuis l'installation. */
    val lastBootAttempt: BootAttempt?

    /** Dernier refus du système au démarrage du service au premier plan, `null` s'il n'y en a pas eu. */
    val lastServiceFailure: BootAttempt?

    /** Démarre ou arrête le service selon [wanted] (mode réel avec au moins un équipement) et le réglage [enabled]. */
    fun sync(wanted: Boolean)
}

object NoBackgroundCollection : BackgroundCollection {
    override val supported = false
    override var enabled = false
    override var restartAfterBoot = false
    override val lastBootAttempt: BootAttempt? = null
    override val lastServiceFailure: BootAttempt? = null

    override fun sync(wanted: Boolean) = Unit
}
