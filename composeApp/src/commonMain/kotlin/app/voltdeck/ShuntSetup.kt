package app.voltdeck

import app.voltdeck.core.DeviceId
import app.voltdeck.core.Metric
import app.voltdeck.core.Reading

/** Où le shunt est branché dans l'installation. Réglage local de l'app : rien n'est écrit dans le shunt. */
enum class ShuntPlacement { BATTERY, LOAD, SOURCE, IGNORED }

enum class Circuit(val label: String, val isSource: Boolean) {
    FRIDGE("Frigo", false),
    INVERTER("Onduleur", false),
    LIGHTING("Éclairage", false),
    WATER_PUMP("Pompe à eau", false),
    OTHER_LOAD("Consommation", false),
    ALTERNATOR("Alternateur", true),
    MAINS_CHARGER("Chargeur secteur", true),
    WIND("Éolienne", true),
    SOLAR("Solaire", true),
    OTHER_SOURCE("Source", true),
    ;

    companion object {
        fun forPlacement(placement: ShuntPlacement): List<Circuit> = when (placement) {
            ShuntPlacement.LOAD -> entries.filter { !it.isSource }
            ShuntPlacement.SOURCE -> entries.filter { it.isSource }
            else -> emptyList()
        }
    }
}

/**
 * [capacityAh] : capacité nominale de la batterie saisie par l'utilisateur, seulement pour un shunt sur le négatif de la batterie.
 * Sert à estimer les cycles quand aucun BMS ne les compte ; sans elle, l'estimation est masquée.
 */
data class ShuntSetup(
    val placement: ShuntPlacement,
    val circuit: Circuit? = null,
    val name: String? = null,
    val capacityAh: Double? = null,
) {
    init {
        require(capacityAh == null || placement == ShuntPlacement.BATTERY) { "la capacité n'a de sens que pour le négatif de la batterie" }
        require(capacityAh == null || isValidCapacity(capacityAh)) { "capacité hors limites : $capacityAh" }
        require((placement == ShuntPlacement.LOAD || placement == ShuntPlacement.SOURCE) == (circuit != null)) {
            "un circuit est requis pour LOAD et SOURCE, interdit sinon"
        }
        require(circuit == null || circuit.isSource == (placement == ShuntPlacement.SOURCE)) { "circuit incohérent avec $placement" }
    }

    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: circuit?.label ?: "Shunt"

    /** Sans capacité, le texte reste celui d'avant (réglages déjà enregistrés) ; avec, circuit et nom vides gardent leur place. */
    fun encode(): String =
        if (capacityAh == null) {
            listOfNotNull(placement.name, circuit?.name, name?.takeIf { it.isNotBlank() }).joinToString(SEPARATOR)
        } else {
            listOf(placement.name, circuit?.name.orEmpty(), name?.takeIf { it.isNotBlank() }.orEmpty(), capacityAh.toString()).joinToString(SEPARATOR)
        }

    companion object {
        /** Réglage par défaut d'un shunt qui s'annonce en moniteur de batterie. */
        val DEFAULT = ShuntSetup(ShuntPlacement.BATTERY)

        private const val SEPARATOR = "\u001F"

        const val MAX_CAPACITY_AH = 10_000.0

        fun isValidCapacity(ah: Double) = ah.isFinite() && ah >= 1.0 && ah <= MAX_CAPACITY_AH

        fun decode(text: String): ShuntSetup? {
            val parts = text.split(SEPARATOR)
            val placement = ShuntPlacement.entries.firstOrNull { it.name == parts[0] } ?: return null
            val circuit = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { name -> Circuit.entries.firstOrNull { it.name == name } ?: return null }
            val capacity = parts.getOrNull(3)?.toDoubleOrNull()?.takeIf(::isValidCapacity)
            return runCatching { ShuntSetup(placement, circuit, parts.getOrNull(2)?.takeIf { it.isNotEmpty() }, capacity) }.getOrNull()
        }
    }
}

/**
 * Position annoncée par un shunt en mode « DC energy meter » (`METER_TYPE`), `null` pour un moniteur de batterie.
 * Sert tant que l'utilisateur n'a rien réglé lui-même.
 */
fun detectedSetup(reading: Reading?): ShuntSetup? {
    val type = reading?.samples?.firstOrNull { it.metric == Metric.METER_TYPE }?.value?.toInt() ?: return null
    return when {
        type < 0 -> ShuntSetup(
            ShuntPlacement.SOURCE,
            when (type) {
                -9 -> Circuit.SOLAR
                -8 -> Circuit.WIND
                -6 -> Circuit.ALTERNATOR
                -2 -> Circuit.MAINS_CHARGER
                else -> Circuit.OTHER_SOURCE
            },
            METER_NAMES[type],
        )
        type > 0 -> ShuntSetup(
            ShuntPlacement.LOAD,
            when (type) {
                3 -> Circuit.FRIDGE
                4 -> Circuit.WATER_PUMP
                7 -> Circuit.INVERTER
                else -> Circuit.OTHER_LOAD
            },
            METER_NAMES[type],
        )
        else -> null
    }
}

private val METER_NAMES = mapOf(
    -7 to "Générateur d'arbre", -5 to "Pile à combustible", -4 to "Hydrogénérateur", -3 to "Chargeur DC-DC",
    2 to "Propulsion", 5 to "Pompe de cale", 6 to "Système DC", 8 to "Chauffe-eau",
)

/** Réglages des shunts, par équipement. Les implémentations de production sont SharedPreferences et NSUserDefaults. */
interface ShuntSetupStore {
    fun get(device: DeviceId): ShuntSetup?

    fun put(device: DeviceId, setup: ShuntSetup)
}

class InMemoryShuntSetupStore : ShuntSetupStore {
    private val setups = HashMap<DeviceId, ShuntSetup>()

    override fun get(device: DeviceId): ShuntSetup? = setups[device]

    override fun put(device: DeviceId, setup: ShuntSetup) {
        setups[device] = setup
    }
}
