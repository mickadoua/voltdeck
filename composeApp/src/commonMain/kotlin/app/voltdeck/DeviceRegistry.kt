package app.voltdeck

import app.voltdeck.ble.PlatformDeviceId
import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.drivers.KnownDevice

/** Équipement ajouté par l'utilisateur. [platformId] n'apparaît jamais dans `toString`. */
class RegisteredDevice(
    val id: DeviceId,
    val driverId: String,
    val role: DeviceRole,
    val platformId: PlatformDeviceId,
    val name: String,
    val model: String?,
) {
    val known: KnownDevice get() = KnownDevice(id, platformId, role)

    fun encode(): String = listOf(id.value, driverId, role.name, platformId.value, name, model.orEmpty()).joinToString(FIELD)

    override fun toString(): String = "RegisteredDevice(id=$id, driverId=$driverId, role=$role)"

    companion object {
        private const val FIELD = "\u001F"
        private const val RECORD = "\u001E"

        fun decode(text: String): RegisteredDevice? {
            val f = text.split(FIELD)
            if (f.size != 6) return null
            val role = DeviceRole.entries.firstOrNull { it.name == f[2] } ?: return null
            return runCatching { RegisteredDevice(DeviceId(f[0]), f[1], role, PlatformDeviceId(f[3]), f[4], f[5].ifEmpty { null }) }.getOrNull()
        }

        fun encodeAll(devices: List<RegisteredDevice>): String = devices.joinToString(RECORD) { it.encode() }

        fun decodeAll(text: String?): List<RegisteredDevice> =
            text?.takeIf { it.isNotEmpty() }?.split(RECORD)?.mapNotNull(::decode).orEmpty()
    }
}

/** Liste des équipements ajoutés, gardée sur le téléphone. */
interface DeviceRegistry {
    fun all(): List<RegisteredDevice>

    fun add(device: RegisteredDevice)

    fun remove(id: DeviceId)
}

/** Registre adossé à un texte persistant ([load], [save]) : SharedPreferences, NSUserDefaults, ou mémoire. */
open class TextDeviceRegistry(private val load: () -> String?, private val save: (String) -> Unit) : DeviceRegistry {
    override fun all(): List<RegisteredDevice> = RegisteredDevice.decodeAll(load())

    override fun add(device: RegisteredDevice) {
        save(RegisteredDevice.encodeAll(all().filter { it.id != device.id && it.platformId != device.platformId } + device))
    }

    override fun remove(id: DeviceId) {
        save(RegisteredDevice.encodeAll(all().filter { it.id != id }))
    }
}

class InMemoryDeviceRegistry : DeviceRegistry by run({
    var text: String? = null
    TextDeviceRegistry({ text }, { text = it })
})
