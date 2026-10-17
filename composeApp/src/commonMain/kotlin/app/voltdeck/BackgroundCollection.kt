package app.voltdeck

/** Collecte écran éteint (service Android). Sans support de la plateforme, la collecte s'arrête avec l'application. */
interface BackgroundCollection {
    val supported: Boolean
    var enabled: Boolean

    /** Démarre ou arrête le service selon [wanted] (mode réel avec au moins un équipement) et le réglage [enabled]. */
    fun sync(wanted: Boolean)
}

object NoBackgroundCollection : BackgroundCollection {
    override val supported = false
    override var enabled = false

    override fun sync(wanted: Boolean) = Unit
}
