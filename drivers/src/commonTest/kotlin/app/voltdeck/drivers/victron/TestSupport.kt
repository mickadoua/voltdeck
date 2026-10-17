// TEST ONLY — les clés et annonces ci-dessous sont des vecteurs de test publics, pas des secrets.
//
// Sources du format : Victron Energy, « Extra manufacturer Data », version du 2022-12-14,
// https://communityarchive.victronenergy.com/storage/attachments/extra-manufacturer-data-2022-12-14.pdf
// Sources des vecteurs : keshavdv/victron-ble (Unlicense), tests/test_battery_monitor.py, tests/test_solar_charger.py,
// tests/test_dcdc_converter.py ; rajlaud/victron-ble-ha-parser (Apache-2.0), tests/ (mêmes annonces et mêmes clés).
// Les valeurs attendues sont celles que ces projets affirment ; les écarts voulus sont signalés en commentaire.

package app.voltdeck.drivers.victron

import app.voltdeck.core.Metric
import app.voltdeck.core.Sample

internal fun hex(text: String): ByteArray {
    require(text.length % 2 == 0)
    return ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

internal fun keyOf(text: String): VictronKey = (VictronKey.parse(text) as KeyParse.Valid).key

internal fun List<Sample>.value(metric: Metric, channel: Int = 0): Double? =
    firstOrNull { it.metric == metric && it.channel == channel }?.value

internal fun List<Sample>.metrics(): Set<Metric> = map { it.metric }.toSet()

internal fun decoded(data: ByteArray, key: VictronKey): VictronDecodeResult.Decoded =
    VictronDecoder.decode(data, key) as? VictronDecodeResult.Decoded
        ?: error("annonce non décodée")

/** Le vecteur [advHex] re-chiffré avec un autre [nonce] : une trame distincte qui porte les mêmes mesures. */
internal fun withNonce(advHex: String, keyHex: String, nonce: Int): ByteArray {
    val data = hex(advHex)
    val key = keyOf(keyHex)
    val oldNonce = (data[5].toInt() and 0xFF) or ((data[6].toInt() and 0xFF) shl 8)
    val plain = VictronCrypto.decryptFirstBlock(key, oldNonce, data.copyOfRange(8, data.size))
    val out = data.copyOf()
    out[5] = nonce.toByte()
    out[6] = (nonce shr 8).toByte()
    VictronCrypto.encryptFirstBlock(key, nonce, plain).copyInto(out, 8)
    return out
}

/** [count] trames distinctes du SmartShunt de référence. */
internal fun shuntFrames(count: Int): List<ByteArray> =
    List(count) { withNonce(Vectors.BATTERY_MONITOR_ADV, Vectors.BATTERY_MONITOR_KEY, 0x0100 + it) }

internal object Vectors {
    const val BATTERY_MONITOR_ADV = "100289a302b040af925d09a4d89aa0128bdef48c6298a9"
    const val BATTERY_MONITOR_KEY = "aff4d0995b7d1e176c0c33ecb9e70dcd"
    const val BATTERY_MONITOR_WRONG_KEY_ADV = "100289a302bb01af129087600b9b97bc2c32867c8238da"
    const val SOLAR_ADV = "100242a0016207adceb37b605d7e0ee21b24df5c"
    const val SOLAR_KEY = "adeccb947395801a4dd45a2eaa44bf17"
    const val DCDC_ADV = "1000c0a304121d64ca8d442b90bbdf6a8cba"
    const val DCDC_KEY = "64ba49f1a8562e45197a8e1fe50d7658"
}
