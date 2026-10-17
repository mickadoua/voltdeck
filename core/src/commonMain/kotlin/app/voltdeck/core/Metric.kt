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

    /**
     * Victron « DC energy meter » : ce que mesure le shunt monté sur un circuit. Négatif = source (−9 solaire, −8 éolien,
     * −7 générateur d'arbre, −6 alternateur, −5 pile à combustible, −4 hydrogénérateur, −3 DC-DC, −2 chargeur secteur,
     * −1 source), positif = consommation (1 générique, 2 propulsion, 3 frigo, 4 pompe à eau, 5 pompe de cale,
     * 6 système DC, 7 onduleur, 8 chauffe-eau).
     */
    METER_TYPE("code"),
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
