package app.voltdeck

import app.voltdeck.core.DeviceId

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

data class ShuntSetup(val placement: ShuntPlacement, val circuit: Circuit? = null, val name: String? = null) {
    init {
        require((placement == ShuntPlacement.LOAD || placement == ShuntPlacement.SOURCE) == (circuit != null)) {
            "un circuit est requis pour LOAD et SOURCE, interdit sinon"
        }
        require(circuit == null || circuit.isSource == (placement == ShuntPlacement.SOURCE)) { "circuit incohérent avec $placement" }
    }

    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: circuit?.label ?: "Shunt"

    fun encode(): String = listOfNotNull(placement.name, circuit?.name, name?.takeIf { it.isNotBlank() }).joinToString(SEPARATOR)

    companion object {
        /** Réglage par défaut d'un shunt qui s'annonce en moniteur de batterie. */
        val DEFAULT = ShuntSetup(ShuntPlacement.BATTERY)

        private const val SEPARATOR = "\u001F"

        fun decode(text: String): ShuntSetup? {
            val parts = text.split(SEPARATOR)
            val placement = ShuntPlacement.entries.firstOrNull { it.name == parts[0] } ?: return null
            val circuit = parts.getOrNull(1)?.let { name -> Circuit.entries.firstOrNull { it.name == name } ?: return null }
            return runCatching { ShuntSetup(placement, circuit, parts.getOrNull(2)) }.getOrNull()
        }
    }
}

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
