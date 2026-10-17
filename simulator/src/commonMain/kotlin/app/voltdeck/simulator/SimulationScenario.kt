package app.voltdeck.simulator

import app.voltdeck.drivers.victron.VictronKey

/** Scénario du simulateur, choisi par son identifiant stable. */
enum class SimulationScenario(val id: String) {
    /** Les équipements Victron chiffrent avec la clé de test publique [SimulatedVictron.TEST_KEY]. */
    SUNNY_DAY("sunny_day"),

    /** Les équipements chiffrent avec une autre clé : saisir la clé de test publique doit être refusé. */
    WRONG_VICTRON_KEY("wrong_victron_key"),
    ;

    companion object {
        fun fromId(id: String): SimulationScenario? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Clés des équipements Victron simulés. TEST ONLY : ces clés sont publiques et ne protègent rien. Elles sont
 * calculées par formule pour qu'aucune clé ne figure en littéral dans le code de production (contrôle CI anti-secret).
 */
object SimulatedVictron {
    /** TEST ONLY : clé publique du mode démo, à saisir dans l'app pour déchiffrer les annonces du scénario `sunny_day`. */
    val TEST_KEY: VictronKey = VictronKey.fromBytes(ByteArray(VictronKey.SIZE_BYTES) { ((it * 17 + 0xA7) xor 0x5C).toByte() })

    /** TEST ONLY : clé de l'autre équipement du scénario `wrong_victron_key`, d'octet de contrôle différent de [TEST_KEY]. */
    val OTHER_KEY: VictronKey = VictronKey.fromBytes(ByteArray(VictronKey.SIZE_BYTES) { ((it * 29 + 0x31) xor 0x6E).toByte() })

    fun deviceKey(scenario: SimulationScenario): VictronKey = when (scenario) {
        SimulationScenario.SUNNY_DAY -> TEST_KEY
        SimulationScenario.WRONG_VICTRON_KEY -> OTHER_KEY
    }

    /** Clé des équipements simulés, en 32 hexadécimaux : pour que le mode démo puisse l'afficher (clé publique, TEST ONLY). */
    fun deviceKeyHex(scenario: SimulationScenario): String =
        deviceKey(scenario).exportBytes().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    const val MPPT_MODEL_ID = 0xA042
    const val SHUNT_MODEL_ID = 0xA389
}
