package app.voltdeck.core

/** Mesures connues de l'application. Convention de signe : courant > 0 = la batterie se charge. */
enum class Metric(val unit: String) {
    BATTERY_VOLTAGE("V"),
    BATTERY_CURRENT("A"),
    SOC("%"),
    REMAINING_CAPACITY("Ah"),
    NOMINAL_CAPACITY("Ah"),
    CONSUMED_AH("Ah"),
    CYCLES("count"),
    TIME_TO_GO("min"),
    PV_POWER("W"),
    YIELD_TODAY("kWh"),
    LOAD_CURRENT("A"),
    INPUT_VOLTAGE("V"),
    OUTPUT_VOLTAGE("V"),

    /** Indexé : `channel` = numéro de sonde. */
    TEMPERATURE("°C"),

    /** Indexé : `channel` = numéro de cellule. */
    CELL_VOLTAGE("V"),

    /** SmartShunt, selon le type d'entrée auxiliaire. */
    MID_VOLTAGE("V"),
    AUX_VOLTAGE("V"),
    DEVICE_STATE("code"),
    ERROR_CODE("code"),
    PROTECTION_FLAGS("bits"),
    ALARM_REASON("bits"),
    OFF_REASON("bits"),

    // Métriques dérivées par le stockage (VOL-7), à pleine résolution : un pilote ne les émet jamais.
    // Seule la colonne `sum` des agrégats a un sens pour elles.
    /** Énergie entrée dans la batterie (ou fournie côté batterie pour un MPPT), intégrée sur V × I. */
    ENERGY_IN_WH("Wh"),

    /** Énergie sortie de la batterie, intégrée sur V × I. */
    ENERGY_OUT_WH("Wh"),
    CHARGE_IN_AH("Ah"),
    CHARGE_OUT_AH("Ah"),

    /** Énergie solaire intégrée sur `PV_POWER` (côté panneaux, pertes du convertisseur comprises). */
    PV_ENERGY_WH("Wh"),
}
