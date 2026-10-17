package app.voltdeck.ble

import com.juul.kable.Advertisement
import com.juul.kable.Characteristic
import com.juul.kable.Filter
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Transport Bluetooth réel, adossé à Kable. C'est le SEUL fichier du dépôt autorisé à écrire sur une
 * caractéristique (contrôle CI `scripts/ci/check-readonly.sh`). Toute écriture passe par [GuardedGattLink],
 * qui compare la trame à [ReadAllowlist] avant d'émettre.
 */
@OptIn(ExperimentalUuidApi::class)
class KableTransport : BleTransport {
    // Plus ancien en premier : les adresses Android tournent, les tables ne doivent pas grossir sans limite.
    private val seen = LinkedHashMap<PlatformDeviceId, Advertisement>()
    private val ledger = ConnectionLedger()
    private val seenLock = Mutex()

    override fun advertisements(filter: ScanFilter): Flow<Adv> {
        val scanner = Scanner {
            filters {
                match {
                    filter.serviceUuid?.let { services = listOf(Uuid.parse(it)) }
                    filter.companyId?.let { manufacturerData = listOf(Filter.ManufacturerData(it)) }
                    filter.namePrefix?.let { name = Filter.Name.Prefix(it) }
                }
            }
        }
        return scanner.advertisements.map { advertisement ->
            val adv = advertisement.toAdv()
            seenLock.withLock {
                seen.remove(adv.id)
                seen[adv.id] = advertisement
                ledger.record(adv)
                while (seen.size > MAX_SEEN) seen.remove(seen.keys.first())
                ledger.trim(MAX_SEEN)
            }
            adv
        }
    }

    override suspend fun connect(id: PlatformDeviceId, profile: GattProfile): ReadOnlyGattLink {
        val advertisement = seenLock.withLock {
            ledger.requireConnectable(id, profile)
            seen[id]
        } ?: throw IllegalStateException("Équipement inconnu : il faut l'avoir vu en scan avant de se connecter")
        val service = Uuid.parse(profile.serviceUuid)
        val notify = characteristicOf(service, Uuid.parse(profile.notifyCharacteristicUuid))
        val request = characteristicOf(service, Uuid.parse(profile.requestCharacteristicUuid))
        val writeType = if (profile.requestWithResponse) WriteType.WithResponse else WriteType.WithoutResponse
        val peripheral = Peripheral(advertisement)
        try {
            peripheral.connect()
            return GuardedGattLink(
                notifications = peripheral.observe(notify),
                sink = FrameSink { frame -> peripheral.write(request, frame, writeType) },
                onClose = { peripheral.close() },
            )
        } catch (e: Throwable) {
            peripheral.close()
            throw e
        }
    }

    private fun Advertisement.toAdv(): Adv {
        val manufacturer = manufacturerData
        return Adv(
            id = PlatformDeviceId(identifier.toString()),
            name = peripheralName ?: name,
            rssi = rssi,
            manufacturerData = if (manufacturer == null) emptyMap() else mapOf(manufacturer.code to manufacturer.data),
            serviceUuids = uuids.map { it.toString().lowercase() },
        )
    }

    private companion object {
        const val MAX_SEEN = 256
    }
}

internal fun interface FrameSink {
    suspend fun write(frame: ByteArray)
}

/**
 * Seul chemin d'écriture : la trame est comparée à [ReadAllowlist] (égalité d'octets) avant d'atteindre
 * [sink]. En cas d'écart, [ForbiddenCommandException] et rien n'est envoyé.
 */
internal class GuardedGattLink(
    override val notifications: Flow<ByteArray>,
    private val sink: FrameSink,
    private val onClose: () -> Unit = {},
) : ReadOnlyGattLink {
    override suspend fun request(cmd: AllowedCommand) = requestFrame(cmd.frame())

    internal suspend fun requestFrame(frame: ByteArray) {
        ReadAllowlist.requireAllowed(frame)
        sink.write(frame)
    }

    override fun close() = onClose()
}
