/*
 * Source du format : Victron Energy, « Extra manufacturer Data », version imprimée le 2022-12-14,
 * https://communityarchive.victronenergy.com/storage/attachments/extra-manufacturer-data-2022-12-14.pdf
 * Vecteurs de test : keshavdv/victron-ble (Unlicense), dossier tests/, et rajlaud/victron-ble-ha-parser (Apache-2.0),
 * tests/. Aucun code d'esphome-victron_ble (GPL-3.0) ni victron-hacs (sans licence) n'est utilisé.
 */

package app.voltdeck.drivers.victron

import app.voltdeck.ble.ConnectionPolicy
import app.voltdeck.core.DeviceRole

/** Type d'enregistrement Victron (octet 4 de la donnée constructeur). Seuls les types décodés sont listés. */
enum class VictronRecordType(val code: Int, val role: DeviceRole, val minPayloadBytes: Int) {
    SOLAR_CHARGER(0x01, DeviceRole.SOLAR_CHARGER, minPayloadBytes = 12),
    BATTERY_MONITOR(0x02, DeviceRole.SHUNT, minPayloadBytes = 15),
    DCDC_CONVERTER(0x04, DeviceRole.DCDC, minPayloadBytes = 10);

    companion object {
        fun fromCode(code: Int): VictronRecordType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * En-tête d'une annonce Victron « Product Advertisement ». Disposition (donnée constructeur, Company ID 0x02E1
 * déjà retiré) :
 *
 *     octet 0      0x10 (Product Advertisement)
 *     octet 1      non documenté, ignoré
 *     octets 2-3   identifiant de modèle, petit-boutiste
 *     octet 4      type d'enregistrement
 *     octets 5-6   nonce, petit-boutiste
 *     octet 7      octet 0 de la clé de chiffrement (contrôle de clé)
 *     octets 8..   charge utile chiffrée
 *
 * Le PDF numérote à partir du type d'enregistrement (bit 0) ; la disposition ci-dessus, avec les octets 0 à 3 en
 * amont, est celle des vecteurs réels de keshavdv et rajlaud. Elle corrige la note VOL-1 §2.1.
 */
internal class VictronHeader(
    val modelId: Int,
    val recordCode: Int,
    val nonce: Int,
    val keyCheckByte: Int,
    val payload: ByteArray,
) {
    val type: VictronRecordType? get() = VictronRecordType.fromCode(recordCode)

    companion object {
        const val PRODUCT_ADVERTISEMENT = 0x10
        const val SIZE_BYTES = 8
        const val COMPANY_ID = ConnectionPolicy.VICTRON_COMPANY_ID

        fun isProductAdvertisement(data: ByteArray): Boolean = data.isNotEmpty() && (data[0].toInt() and 0xFF) == PRODUCT_ADVERTISEMENT

        /** `null` si [data] est trop court pour contenir l'en-tête. La longueur de la charge utile n'est jamais bornée par le haut. */
        fun parse(data: ByteArray): VictronHeader? {
            if (data.size < SIZE_BYTES) return null
            fun u(i: Int) = data[i].toInt() and 0xFF
            return VictronHeader(
                modelId = u(2) or (u(3) shl 8),
                recordCode = u(4),
                nonce = u(5) or (u(6) shl 8),
                keyCheckByte = u(7),
                payload = data.copyOfRange(SIZE_BYTES, data.size),
            )
        }
    }
}
