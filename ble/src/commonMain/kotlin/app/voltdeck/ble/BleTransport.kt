package app.voltdeck.ble

import kotlinx.coroutines.flow.Flow

/** L'unique porte vers le matériel Bluetooth. Seul ce module connaît Kable. */
interface BleTransport {
    fun advertisements(filter: ScanFilter): Flow<Adv>

    suspend fun connect(id: PlatformDeviceId, profile: GattProfile): ReadOnlyGattLink
}

/**
 * Lien GATT en lecture seule : on reçoit des notifications, et la seule émission possible est une
 * [AllowedCommand], c'est-à-dire une trame constante de [ReadAllowlist]. Aucun `ByteArray` libre.
 */
interface ReadOnlyGattLink : AutoCloseable {
    val notifications: Flow<ByteArray>

    suspend fun request(cmd: AllowedCommand)
}
