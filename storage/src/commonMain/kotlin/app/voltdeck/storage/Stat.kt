package app.voltdeck.storage

/** Nature d'un résultat : ce que l'écran peut en affirmer (VOL-1 §5.4). Rien n'est inventé : l'absence est [UNAVAILABLE]. */
enum class Nature {
    /** Lu tel quel dans une trame d'un équipement. */
    MEASURED,

    /** Calculé par l'équipement lui-même (rendement du jour du MPPT, autonomie du shunt, compteur de cycles du BMS). */
    DEVICE_COMPUTED,

    /** Calculé par l'application à partir de mesures, sans hypothèse (énergie intégrée, consommation). */
    CALCULATED,

    /** Dépend d'une hypothèse (moyenne passée, capacité saisie) : à afficher « estimé ». */
    ESTIMATED,

    /** Pas de valeur : [Stat.absence] dit pourquoi. */
    UNAVAILABLE,
}

/** Pourquoi un résultat est absent, pour que l'écran l'explique plutôt que d'afficher un zéro. */
enum class Absence {
    NO_DATA,
    STALE,
    NO_SOURCE,
    NO_FLOW_SENSOR,
    INCOMPLETE,
    INCONSISTENT,
    INSUFFICIENT_HISTORY,
    NET_CHARGE,
    NO_CAPACITY,
}

/**
 * Un résultat de [SystemStats]. [value] est `null` si et seulement si [nature] est [Nature.UNAVAILABLE].
 * [coverage] (0..1), quand il est renseigné, est la part de la période réellement couverte par le calcul.
 */
data class Stat(
    val value: Double?,
    val unit: String,
    val nature: Nature,
    val coverage: Double? = null,
    val absence: Absence? = null,
) {
    init {
        require((value == null) == (nature == Nature.UNAVAILABLE)) { "value est absent exactement quand la nature est UNAVAILABLE" }
        require((absence != null) == (nature == Nature.UNAVAILABLE)) { "absence est renseignée exactement quand la nature est UNAVAILABLE" }
        require(value == null || value.isFinite()) { "value doit être finie" }
        require(coverage == null || coverage in 0.0..1.0) { "coverage doit être dans 0..1" }
    }

    companion object {
        fun unavailable(unit: String, absence: Absence) = Stat(null, unit, Nature.UNAVAILABLE, absence = absence)
    }
}
