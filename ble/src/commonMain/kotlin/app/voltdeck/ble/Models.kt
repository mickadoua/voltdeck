package app.voltdeck.ble

/** Identifiant plateforme (MAC Android, UUID de périphérique iOS). Jamais journalisé, jamais dans un rapport. */
class PlatformDeviceId(val value: String) {
    init {
        require(value.isNotBlank()) { "PlatformDeviceId ne peut pas être vide" }
    }

    override fun equals(other: Any?): Boolean = other is PlatformDeviceId && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "PlatformDeviceId(***)"
}

/** Annonce BLE reçue. Les champs sensibles sont masqués dans [toString]. */
class Adv(
    val id: PlatformDeviceId,
    val name: String?,
    val rssi: Int,
    /** Données constructeur, par Company ID (ex. 0x02E1 pour Victron). */
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    /** UUID de service annoncés, en minuscules (forme longue 128 bits). */
    val serviceUuids: List<String> = emptyList(),
) {
    override fun toString(): String =
        "Adv(name=$name, rssi=$rssi, companyIds=${manufacturerData.keys}, services=$serviceUuids)"
}

/** Critères de scan passif. Un champ à `null` ne filtre pas. */
data class ScanFilter(
    val companyId: Int? = null,
    val serviceUuid: String? = null,
    val namePrefix: String? = null,
) {
    fun matches(adv: Adv): Boolean =
        (companyId == null || companyId in adv.manufacturerData) &&
            (serviceUuid == null || adv.serviceUuids.any { it.equals(serviceUuid, ignoreCase = true) }) &&
            (namePrefix == null || adv.name?.startsWith(namePrefix) == true)

    companion object {
        val Any = ScanFilter()
    }
}

/**
 * Où lire et où demander, pour un pilote GATT. `requestCharacteristicUuid` ne reçoit que des trames de
 * [ReadAllowlist].
 */
data class GattProfile(
    val serviceUuid: String,
    val notifyCharacteristicUuid: String,
    val requestCharacteristicUuid: String,
    val requestWithResponse: Boolean = true,
)
