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

/** Annonce BLE reçue. Les champs sensibles sont masqués dans [toString]. Les octets sont copiés à la construction. */
class Adv(
    val id: PlatformDeviceId,
    val name: String?,
    val rssi: Int,
    manufacturerData: Map<Int, ByteArray> = emptyMap(),
    /** UUID de service annoncés, en minuscules (forme longue 128 bits). */
    val serviceUuids: List<String> = emptyList(),
) {
    /** Données constructeur, par Company ID (ex. 0x02E1 pour Victron). Copie propre à cette annonce. */
    val manufacturerData: Map<Int, ByteArray> = manufacturerData.mapValues { it.value.copyOf() }

    override fun toString(): String =
        "Adv(rssi=$rssi, companyIds=${this.manufacturerData.keys}, services=$serviceUuids)"
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
 * Où lire et où demander, pour un pilote GATT. Fermé : un pilote ne choisit pas ses UUID, il prend un profil
 * connu. La liste blanche borne les octets émis ([ReadAllowlist]), ce profil borne le destinataire.
 */
sealed class GattProfile private constructor(
    val serviceUuid: String,
    val notifyCharacteristicUuid: String,
    val requestCharacteristicUuid: String,
    val requestWithResponse: Boolean,
) {
    /**
     * BMS JBD / Xiaoxiang (Ultimatron) : service 0xFF00, notifications 0xFF01, requêtes 0xFF02.
     * Sources : syssi/esphome-jbd-bms (Apache-2.0), components/jbd_bms_ble/jbd_bms_ble.h ;
     * patman15/aiobmsble (Apache-2.0), aiobmsble/bms/jbd_bms.py.
     */
    data object Jbd : GattProfile(
        serviceUuid = "0000ff00-0000-1000-8000-00805f9b34fb",
        notifyCharacteristicUuid = "0000ff01-0000-1000-8000-00805f9b34fb",
        requestCharacteristicUuid = "0000ff02-0000-1000-8000-00805f9b34fb",
        requestWithResponse = true,
    )
}

/** Levée quand on allait se connecter à un équipement que Voltdeck n'a pas le droit de contacter. Rien n'a été émis. */
class ForbiddenConnectionException(message: String) : RuntimeException(message)

/**
 * Règle de connexion commune au transport réel et au simulateur (VOL-1 §2.1, §4.3 verrou 2) : Voltdeck écoute
 * seulement les annonces Victron, il ne s'y connecte jamais.
 */
object ConnectionPolicy {
    const val VICTRON_COMPANY_ID = 0x02E1

    fun requireConnectable(adv: Adv) {
        if (VICTRON_COMPANY_ID in adv.manufacturerData) {
            throw ForbiddenConnectionException(
                "Victron : Voltdeck ne se connecte jamais, il écoute seulement les annonces",
            )
        }
    }
}
