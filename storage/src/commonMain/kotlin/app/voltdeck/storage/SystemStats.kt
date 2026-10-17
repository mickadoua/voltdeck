package app.voltdeck.storage

import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Metric
import app.voltdeck.storage.db.VoltdeckDatabase
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Statistiques du système, calculées sur les agrégats (VOL-1 §11, points 4, 5 et 6).
 * Chaque résultat dit sa [Nature] ; ce qui ne peut pas être établi est [Nature.UNAVAILABLE], jamais un zéro ni une valeur devinée.
 *
 * Les équipements pris en compte sont ceux de [DeviceStore] actifs pendant la période demandée.
 */
class SystemStats(
    database: VoltdeckDatabase,
    /** Faux pour un shunt monté sur un circuit (consommation ou source) : il ne mesure pas le flux de la batterie. */
    private val measuresBattery: (DeviceId) -> Boolean = { true },
) {
    private val devices = DeviceStore(database)
    private val reader = AggregateReader(database)

    /**
     * Production solaire du jour local [day], en kWh, tous MPPT confondus.
     * Par MPPT : dernier `YIELD_TODAY` du jour ([Nature.DEVICE_COMPUTED]), sinon l'énergie intégrée sur `PV_POWER`
     * ([Nature.CALCULATED], côté panneaux). Un MPPT sans aucune des deux mesures ne compte pas et abaisse [Stat.coverage].
     */
    fun solarProduction(day: LocalDate, zone: TimeZone): Stat {
        val from = day.start(zone)
        val to = day.nextDay().start(zone)
        val chargers = activeDevices(DeviceRole.SOLAR_CHARGER, from, to)
        if (chargers.isEmpty()) return Stat.unavailable("kWh", Absence.NO_SOURCE)
        var total = 0.0
        var counted = 0
        var calculated = false
        for (charger in chargers) {
            val yieldToday = reader.daily(SeriesKey(charger.id, Metric.YIELD_TODAY), from, to, zone).firstOrNull()
            if (yieldToday != null) {
                total += yieldToday.stats.last
                counted++
                continue
            }
            val integrated = reader.daily(SeriesKey(charger.id, Metric.PV_ENERGY_WH), from, to, zone).firstOrNull() ?: continue
            total += integrated.stats.sum / 1000.0
            counted++
            calculated = true
        }
        if (counted == 0) return Stat.unavailable("kWh", Absence.NO_DATA)
        return Stat(
            value = total,
            unit = "kWh",
            nature = if (calculated) Nature.CALCULATED else Nature.DEVICE_COMPUTED,
            coverage = counted.toDouble() / chargers.size,
        )
    }

    /**
     * Énergie consommée le jour local [day], en Wh : somme des sources mesurées côté batterie (tension × courant batterie du MPPT,
     * jamais `PV_POWER`) moins le flux net de la batterie (shunt, sinon BMS), période par période.
     *
     * Une période (minute, ou heure pour un jour sans minutes : purgées ou jamais écrites) est **incomplète**, donc écartée, si :
     * un MPPT n'a aucune mesure d'énergie dans la période, ou un DC-DC n'a pas d'état connu ou peut débiter (état > 2 ; le DC-DC
     * Orion Smart n'annonce aucun courant, sa contribution est invisible). [Stat.coverage] est la part des périodes observées
     * par le flux batterie qui sont complètes. Aucune extrapolation.
     *
     * Hypothèses non vérifiées sur matériel (R12) : la sortie load du MPPT n'est pas ajoutée aux sources ; les états 0 (arrêt),
     * 1 (veille) et 2 (défaut) sont ceux où le DC-DC ne débite pas. Hors tolérance, un total négatif (sources < flux entrant)
     * est refusé comme [Absence.INCONSISTENT] : c'est le signe qu'une de ces hypothèses est fausse.
     * Pour la même raison, sans aucun MPPT ni DC-DC enregistré, la consommation est absente : un chargeur inconnu fausserait tout.
     */
    fun consumption(day: LocalDate, zone: TimeZone, now: Instant): Stat {
        val from = day.start(zone)
        val to = day.nextDay().start(zone)
        val flowDevice = flowDevice(from, to, hasHours(Metric.ENERGY_IN_WH, from, to))
            ?: return Stat.unavailable("Wh", Absence.NO_FLOW_SENSOR)
        val sources = activeDevices(DeviceRole.SOLAR_CHARGER, from, to)
        val converters = activeDevices(DeviceRole.DCDC, from, to)
        if (sources.isEmpty() && converters.isEmpty()) return Stat.unavailable("Wh", Absence.NO_SOURCE)

        val recent = from.epochSeconds >= (now - MINUTE_RETENTION).hourStart()
        val level = if (recent && rowsOf(Level.MINUTE, SeriesKey(flowDevice.id, Metric.ENERGY_IN_WH), from, to, zone).isNotEmpty()) Level.MINUTE else Level.HOUR
        fun series(id: DeviceId, metric: Metric) = rowsOf(level, SeriesKey(id, metric), from, to, zone)

        val flowIn = series(flowDevice.id, Metric.ENERGY_IN_WH)
        val flowOut = series(flowDevice.id, Metric.ENERGY_OUT_WH)
        val sourceEnergy = sources.map { series(it.id, Metric.ENERGY_IN_WH) to series(it.id, Metric.ENERGY_OUT_WH) }
        val converterStates = converters.map { series(it.id, Metric.DEVICE_STATE) }

        val observed = flowIn.keys.sorted()
        if (observed.isEmpty()) return Stat.unavailable("Wh", Absence.NO_DATA)
        var complete = 0
        var total = 0.0
        for (bucket in observed) {
            if (sourceEnergy.any { (energyIn, _) -> bucket !in energyIn }) continue
            if (converterStates.any { states -> (states[bucket]?.max ?: Double.MAX_VALUE) > DCDC_NOT_DELIVERING_MAX_STATE }) continue
            val produced = sourceEnergy.sumOf { (energyIn, energyOut) -> energyIn.getValue(bucket).sum - (energyOut[bucket]?.sum ?: 0.0) }
            val net = flowIn.getValue(bucket).sum - (flowOut[bucket]?.sum ?: 0.0)
            total += produced - net
            complete++
        }
        if (complete == 0) return Stat.unavailable("Wh", Absence.INCOMPLETE)
        if (total < -NEGATIVE_TOLERANCE_WH) return Stat.unavailable("Wh", Absence.INCONSISTENT)
        return Stat(total, "Wh", Nature.CALCULATED, coverage = complete.toDouble() / observed.size)
    }

    /**
     * Autonomie restante, en heures.
     * Avec un shunt qui donne un `TIME_TO_GO` récent : celui du shunt ([Nature.DEVICE_COMPUTED]).
     * Sinon : capacité restante du BMS ÷ décharge nette moyenne des 24 dernières heures pleines ([Nature.ESTIMATED]).
     * L'estimation est absente sous [MIN_ESTIMATE_HOURS] heures de données, ou si la batterie se charge en moyenne.
     */
    fun autonomy(now: Instant, maxAge: Duration = 10.minutes): Stat {
        val shunt = activeDevices(DeviceRole.SHUNT, now, now).firstOrNull { measuresBattery(it.id) }
        if (shunt != null) {
            val timeToGo = freshLatest(SeriesKey(shunt.id, Metric.TIME_TO_GO), now, maxAge)
            if (timeToGo != null) return Stat(timeToGo.stats.last / 60.0, "h", Nature.DEVICE_COMPUTED)
        }
        val window = now - 25.hours
        val flowDevice = flowDevice(window, now, hasHours(Metric.CHARGE_IN_AH, window, now))
            ?: return Stat.unavailable("h", Absence.NO_FLOW_SENSOR)
        val battery = activeDevices(DeviceRole.BATTERY, now, now)
            .firstNotNullOfOrNull { b -> latest(SeriesKey(b.id, Metric.REMAINING_CAPACITY)) }
            ?: return Stat.unavailable("h", Absence.NO_DATA)
        if (now - battery.start > maxAge + 1.minutes) return Stat.unavailable("h", Absence.STALE)

        val windowEnd = instantOf(now.hourStart())
        val windowStart = windowEnd - 24.hours
        val chargeIn = reader.hourly(SeriesKey(flowDevice.id, Metric.CHARGE_IN_AH), windowStart, windowEnd).associate { it.start to it.stats.sum }
        val chargeOut = reader.hourly(SeriesKey(flowDevice.id, Metric.CHARGE_OUT_AH), windowStart, windowEnd).associate { it.start to it.stats.sum }
        val hours = chargeIn.keys.filter { it in chargeOut }
        if (hours.size < MIN_ESTIMATE_HOURS) return Stat.unavailable("h", Absence.INSUFFICIENT_HISTORY)
        val netDischargeAh = hours.sumOf { chargeOut.getValue(it) - chargeIn.getValue(it) }
        if (netDischargeAh <= 0.0) return Stat.unavailable("h", Absence.NET_CHARGE)
        val averageAmps = netDischargeAh / hours.size
        return Stat(battery.stats.last / averageAmps, "h", Nature.ESTIMATED, coverage = hours.size / 24.0)
    }

    /**
     * Nombre de cycles. Compteur du BMS tel quel s'il en existe un ([Nature.DEVICE_COMPUTED] ; plusieurs batteries : le plus élevé),
     * sinon cycles équivalents du flux batterie = Ah déchargés depuis l'enregistrement du shunt ÷ [nominalCapacityAh]
     * ([Nature.ESTIMATED]). Sans capacité nominale saisie, l'estimation est absente.
     */
    fun cycles(nominalCapacityAh: Double?, now: Instant, zone: TimeZone): Stat {
        val counters = devices.all().filter { it.role == DeviceRole.BATTERY && it.archivedAt == null }
            .mapNotNull { latest(SeriesKey(it.id, Metric.CYCLES))?.stats?.last }
        if (counters.isNotEmpty()) return Stat(counters.max(), "cycles", Nature.DEVICE_COMPUTED)

        val flowDevice = flowDevice(now, now) { latest(SeriesKey(it.id, Metric.CHARGE_OUT_AH)) != null }
            ?: return Stat.unavailable("cycles", Absence.NO_FLOW_SENSOR)
        if (nominalCapacityAh == null || nominalCapacityAh <= 0.0) return Stat.unavailable("cycles", Absence.NO_CAPACITY)
        val firstMonth = flowDevice.createdAt.localDate(zone).firstOfMonth().start(zone)
        val discharged = reader.monthly(SeriesKey(flowDevice.id, Metric.CHARGE_OUT_AH), firstMonth, now + 1.hours, zone)
        if (discharged.isEmpty()) return Stat.unavailable("cycles", Absence.NO_DATA)
        return Stat(discharged.sumOf { it.stats.sum } / nominalCapacityAh, "cycles", Nature.ESTIMATED)
    }

    /** Dernière valeur reçue de [metric], telle quelle ([Nature.MEASURED]) ; absente si elle a plus de [maxAge]. */
    fun measured(deviceId: DeviceId, metric: Metric, now: Instant, channel: Int = 0, maxAge: Duration = 10.minutes): Stat {
        val row = latest(SeriesKey(deviceId, metric, channel)) ?: return Stat.unavailable(metric.unit, Absence.NO_DATA)
        if (now - row.start > maxAge + 1.minutes) return Stat.unavailable(metric.unit, Absence.STALE)
        return Stat(row.stats.last, metric.unit, Nature.MEASURED)
    }

    private fun activeDevices(role: DeviceRole, from: Instant, to: Instant): List<DeviceInfo> =
        devices.all().filter { it.role == role && it.isActiveDuring(from, to.coerceAtLeast(from + 1.seconds)) }

    /**
     * Capteur du flux net de la batterie : le shunt monté sur la batterie s'il existe, sinon le BMS (VOL-1 §11-5). Un shunt enregistré
     * qui n'a aucune mesure utile sur la période cède la place au BMS qui en a ; si aucun n'en a, le premier candidat.
     */
    private fun flowDevice(from: Instant, to: Instant, hasData: (DeviceInfo) -> Boolean): DeviceInfo? {
        val candidates = activeDevices(DeviceRole.SHUNT, from, to).filter { measuresBattery(it.id) } + activeDevices(DeviceRole.BATTERY, from, to)
        return candidates.firstOrNull(hasData) ?: candidates.firstOrNull()
    }

    private fun hasHours(metric: Metric, from: Instant, to: Instant): (DeviceInfo) -> Boolean =
        { reader.hourly(SeriesKey(it.id, metric), from, to).isNotEmpty() }

    private fun latest(key: SeriesKey): AggRow? = reader.latest(key, TimeZone.UTC)

    private fun freshLatest(key: SeriesKey, now: Instant, maxAge: Duration): AggRow? =
        latest(key)?.takeIf { now - it.start <= maxAge + 1.minutes }

    private fun rowsOf(level: Level, key: SeriesKey, from: Instant, to: Instant, zone: TimeZone): Map<Instant, AggStats> {
        val rows = if (level == Level.MINUTE) reader.stored(Level.MINUTE, key, from, to, zone) else reader.hourly(key, from, to)
        return rows.associate { it.start to it.stats }
    }

    companion object {
        /** Au-delà de cet état, un DC-DC peut débiter. 0 arrêt, 1 veille, 2 défaut : choix prudent, à confirmer par capture. */
        const val DCDC_NOT_DELIVERING_MAX_STATE = 2.0
        const val MIN_ESTIMATE_HOURS = 6
        const val NEGATIVE_TOLERANCE_WH = 1.0
    }
}
