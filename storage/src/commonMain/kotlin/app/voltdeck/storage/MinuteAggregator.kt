package app.voltdeck.storage

import app.voltdeck.core.DeviceId
import app.voltdeck.core.Metric
import app.voltdeck.core.Reading
import app.voltdeck.storage.db.VoltdeckDatabase
import kotlin.time.Instant

/**
 * Agrège les [Reading] à la minute, en mémoire, puis les écrit : une transaction par minute close (VOL-1 §5.2).
 *
 * - Par (appareil, métrique, canal) et par minute : `count`, `min`, `max`, `sum`, `last`.
 * - Énergie à pleine résolution : Ah entrants et sortants (courant batterie) et Wh entrants et sortants (tension × courant),
 *   intégrés par trapèzes entre deux mesures successives, jamais au-delà d'un écart de 60 s ; énergie solaire sur `PV_POWER`.
 *   Ces métriques dérivées sont stockées comme les autres, seule leur colonne `sum` a un sens.
 * - Une ligne minute déjà en base (redémarrage, mesure tardive) est **fusionnée** (`count` et `sum` additionnés, `min`/`max`
 *   combinés), jamais écrasée. Un cumul n'est retiré de la mémoire qu'après la validation de sa transaction : il n'est donc
 *   écrit qu'une fois, et une écriture qui échoue est retentée au flush suivant sans rien perdre ni doubler.
 * - Une minute qui n'a pas été écrite quand l'app est tuée est perdue (moins d'une minute) ; son `count` plus bas le montre.
 *
 * Avec [bucket] = [Level.HOUR], les mêmes cumuls sont écrits directement dans les heures : pour importer un long historique
 * sans des millions de lignes minute.
 *
 * Non thread-safe : le collecteur appelle [add] et les flush depuis une seule coroutine.
 */
class MinuteAggregator(private val database: VoltdeckDatabase, private val bucket: Level = Level.MINUTE) {
    init {
        require(bucket == Level.MINUTE || bucket == Level.HOUR) { "niveau $bucket non pris en charge" }
    }

    private val step = if (bucket == Level.MINUTE) MINUTE_SECONDS else HOUR_SECONDS

    private data class Slot(val minute: Long, val deviceId: String, val metric: Metric, val channel: Int)

    private class Chains {
        val ampere = EnergyIntegrator()
        val watt = EnergyIntegrator()
        val photovoltaic = EnergyIntegrator()
    }

    private val pending = HashMap<Slot, AggStats>()
    private val chains = HashMap<DeviceId, Chains>()

    /**
     * Ajoute un relevé. Écrit ensuite les minutes antérieures à celle du relevé : elles sont closes, puisque
     * les relevés arrivent dans l'ordre.
     */
    fun add(reading: Reading) {
        val minute = floorTo(reading.at.epochSeconds, step)
        val id = reading.deviceId.value
        for (sample in reading.samples) {
            if (sample.metric in DERIVED_METRICS) continue
            accumulate(Slot(minute, id, sample.metric, sample.channel), sample.value)
        }
        integrate(reading)
        flushBefore(minute)
    }

    /** Écrit les minutes closes à l'instant [now] (à appeler périodiquement quand plus aucun relevé n'arrive). */
    fun flushClosed(now: Instant) = flushBefore(floorTo(now.epochSeconds, step))

    /** Écrit tout, minute en cours comprise (arrêt propre). Un relevé tardif de cette minute sera fusionné. */
    fun flushAll() = flushBefore(Long.MAX_VALUE)

    /** Nombre de cumuls en mémoire, en attente d'écriture. */
    val pendingCount: Int get() = pending.size

    private fun accumulate(slot: Slot, value: Double) {
        val previous = pending[slot]
        pending[slot] = if (previous == null) AggStats.of(value) else previous.merge(AggStats.of(value))
    }

    private fun integrate(reading: Reading) {
        val current = reading.samples.firstOrNull { it.metric == Metric.BATTERY_CURRENT && it.channel == 0 }?.value
        val voltage = reading.samples.firstOrNull { it.metric == Metric.BATTERY_VOLTAGE && it.channel == 0 }?.value
        val pv = reading.samples.firstOrNull { it.metric == Metric.PV_POWER && it.channel == 0 }?.value
        if (current == null && pv == null) {
            chains.remove(reading.deviceId)
            return
        }
        val device = chains.getOrPut(reading.deviceId) { Chains() }
        val id = reading.deviceId.value
        if (current != null) {
            device.ampere.add(reading.at, current) { minute, positive, negative ->
                accumulate(Slot(floorTo(minute, step), id, Metric.CHARGE_IN_AH, 0), positive / 3600.0)
                accumulate(Slot(floorTo(minute, step), id, Metric.CHARGE_OUT_AH, 0), negative / 3600.0)
            }
        } else {
            device.ampere.reset()
        }
        if (current != null && voltage != null) {
            device.watt.add(reading.at, current * voltage) { minute, positive, negative ->
                accumulate(Slot(floorTo(minute, step), id, Metric.ENERGY_IN_WH, 0), positive / 3600.0)
                accumulate(Slot(floorTo(minute, step), id, Metric.ENERGY_OUT_WH, 0), negative / 3600.0)
            }
        } else {
            device.watt.reset()
        }
        if (pv != null) {
            device.photovoltaic.add(reading.at, pv) { minute, positive, _ ->
                accumulate(Slot(floorTo(minute, step), id, Metric.PV_ENERGY_WH, 0), positive / 3600.0)
            }
        } else {
            device.photovoltaic.reset()
        }
    }

    private fun flushBefore(minuteExclusive: Long) {
        // Copie en paires : sur Kotlin/Native, une entrée de HashMap lève ConcurrentModificationException dès que la map a changé.
        val byMinute = pending.entries.filter { it.key.minute < minuteExclusive }.map { it.key to it.value }.groupBy { it.first.minute }
        for (minute in byMinute.keys.sorted()) {
            val entries = byMinute.getValue(minute)
            database.transaction {
                for ((slot, stats) in entries) persist(slot, stats)
            }
            for ((slot, _) in entries) pending.remove(slot)
        }
    }

    private fun persist(slot: Slot, stats: AggStats) {
        val channel = slot.channel.toLong()
        val merged = stored(slot)?.merge(stats) ?: stats
        if (bucket == Level.MINUTE) {
            database.minuteQueries.upsertMinute(
                slot.deviceId, slot.metric.name, channel, slot.minute, merged.count, merged.min, merged.max, merged.sum, merged.last,
            )
        } else {
            database.hourQueries.upsertHour(
                slot.deviceId, slot.metric.name, channel, slot.minute, merged.count, merged.min, merged.max, merged.sum, merged.last,
            )
        }
    }

    private fun stored(slot: Slot): AggStats? {
        val channel = slot.channel.toLong()
        return if (bucket == Level.MINUTE) {
            database.minuteQueries.selectMinuteOne(slot.deviceId, slot.metric.name, channel, slot.minute).executeAsOneOrNull()
                ?.let { AggStats(it.count, it.min, it.max, it.sum, it.last) }
        } else {
            database.hourQueries.selectHourOne(slot.deviceId, slot.metric.name, channel, slot.minute).executeAsOneOrNull()
                ?.let { AggStats(it.count, it.min, it.max, it.sum, it.last) }
        }
    }
}
