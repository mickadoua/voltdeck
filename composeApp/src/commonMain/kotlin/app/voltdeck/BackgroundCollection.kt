package app.voltdeck

import kotlin.time.Instant

/** Issue de la dernière tentative de reprise de la collecte au démarrage du téléphone. */
enum class BootOutcome { STARTED, SETTING_OFF, STOPPED_BY_USER, NO_DEVICE, NO_PERMISSION, REFUSED, FAILED }

/**
 * [detail] : type de l'exception quand le démarrage a été refusé ([BootOutcome.REFUSED]) ou a échoué ([BootOutcome.FAILED]), jamais de message ni de donnée.
 * [bootCount] : compteur de démarrages du téléphone au moment de la tentative, pour savoir de quel démarrage elle date.
 */
data class BootAttempt(val at: Instant, val outcome: BootOutcome, val detail: String? = null, val bootCount: Int? = null)

/** Collecte écran éteint (service Android). Sans support de la plateforme, la collecte s'arrête avec l'application. */
interface BackgroundCollection {
    val supported: Boolean
    var enabled: Boolean

    /** Relance la collecte au redémarrage du téléphone. Désactivé par défaut : l'utilisateur doit le demander. */
    var restartAfterBoot: Boolean

    /** Compteur de démarrages du téléphone, `null` si le système ne le donne pas. */
    val bootCount: Int?

    /** Compteur de démarrages au moment où [restartAfterBoot] a été activé, `null` si désactivé ou inconnu. */
    val restartEnabledAtBootCount: Int?

    /** Dernière tentative de reprise au démarrage du téléphone, `null` si le téléphone n'a pas redémarré depuis l'installation. */
    val lastBootAttempt: BootAttempt?

    /** Dernier refus du système au démarrage du service au premier plan, `null` s'il n'y en a pas eu. Jamais effacé par un démarrage réussi. */
    val lastServiceFailure: BootAttempt?

    /** Dernier démarrage réussi du service au premier plan, `null` s'il n'a jamais démarré. */
    val lastServiceStart: Instant?

    /** Démarre ou arrête le service selon [wanted] (mode réel avec au moins un équipement) et le réglage [enabled]. */
    fun sync(wanted: Boolean)
}

object NoBackgroundCollection : BackgroundCollection {
    override val supported = false
    override var enabled = false
    override var restartAfterBoot = false
    override val bootCount: Int? = null
    override val restartEnabledAtBootCount: Int? = null
    override val lastBootAttempt: BootAttempt? = null
    override val lastServiceFailure: BootAttempt? = null
    override val lastServiceStart: Instant? = null

    override fun sync(wanted: Boolean) = Unit
}
