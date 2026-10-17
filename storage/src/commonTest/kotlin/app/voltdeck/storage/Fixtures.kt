package app.voltdeck.storage

import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Metric
import app.voltdeck.core.Reading
import app.voltdeck.core.Sample
import app.voltdeck.storage.db.VoltdeckDatabase
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Instant

internal val UTC = TimeZone.UTC
internal val PARIS = TimeZone.of("Europe/Paris")
internal val EPOCH_2026 = Instant.parse("2026-03-10T00:00:00Z")
private val LONG_AGO = Instant.parse("2020-01-01T00:00:00Z")

internal val BMS = DeviceId("bms-1")
internal val MPPT = DeviceId("mppt-1")
internal val SHUNT = DeviceId("shunt-1")
internal val DCDC = DeviceId("dcdc-1")

internal fun VoltdeckDatabase.addDevice(id: DeviceId, role: DeviceRole, createdAt: Instant = LONG_AGO) {
    DeviceStore(this).register(id, "test", role, id.value, "address-${id.value}", null, createdAt)
}

internal fun reading(id: DeviceId, at: Instant, vararg samples: Pair<Metric, Double>) =
    Reading(id, at, samples.map { Sample(it.first, 0, it.second) })

internal fun VoltdeckDatabase.stored(level: Level, id: DeviceId, metric: Metric, zone: TimeZone = UTC): List<AggRow> =
    AggregateReader(this).stored(level, SeriesKey(id, metric), Instant.fromEpochSeconds(0), Instant.parse("2100-01-01T00:00:00Z"), zone)

/** Toutes les lignes de tous les niveaux pour les séries données : sert à comparer deux états de la base. */
internal fun VoltdeckDatabase.snapshot(devices: List<DeviceId>, zone: TimeZone = UTC): List<String> =
    Level.entries.flatMap { level ->
        devices.flatMap { id ->
            Metric.entries.flatMap { metric ->
                stored(level, id, metric, zone).map { "$level $id $metric ${it.start} ${it.stats}" }
            }
        }
    }

/**
 * Alimente un agrégateur de [from] à [to] inclus, tous les [step], avec les mesures de chaque équipement, puis écrit tout.
 * Une fonction qui renvoie `null` à un instant = l'équipement ne parle pas à cet instant.
 */
internal fun VoltdeckDatabase.feed(
    from: Instant,
    to: Instant,
    step: Duration,
    sources: Map<DeviceId, (Instant) -> Map<Metric, Double>?>,
    bucket: Level = Level.MINUTE,
) {
    val aggregator = MinuteAggregator(this, bucket)
    var t = from
    while (t <= to) {
        for ((id, source) in sources) {
            val values = source(t) ?: continue
            aggregator.add(Reading(id, t, values.map { Sample(it.key, 0, it.value) }))
        }
        t += step
    }
    aggregator.flushAll()
}
