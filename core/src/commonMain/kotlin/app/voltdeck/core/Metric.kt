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
    /**
     * Chargeur Victron (MPPT, DC/DC) : énumération "Device state" du PDF Extra manufacturer Data
     * (0 arrêt, 3 bulk, 4 absorption, 5 float…). Jamais émise par un BMS.
     */
    DEVICE_STATE("code"),

    /**
     * BMS : état des FET. Bit 0 = FET de charge fermé (charge permise), bit 1 = FET de décharge fermé.
     * Autres bits réservés, conservés tels quels. Source JBD : registre 0x03, octet d'état des FET.
     */
    FET_STATE("bits"),
    ERROR_CODE("code"),
    PROTECTION_FLAGS("bits"),
    ALARM_REASON("bits"),
    OFF_REASON("bits"),
}
