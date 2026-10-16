package app.voltdeck.core

import kotlin.time.Instant

data class Sample(val metric: Metric, val channel: Int = 0, val value: Double)

/** Un instantané décodé d'un équipement. Un champ absent du protocole est absent de `samples`, jamais à 0. */
data class Reading(val deviceId: DeviceId, val at: Instant, val samples: List<Sample>)
