package app.voltdeck.storage

import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.storage.db.VoltdeckDatabase
import kotlin.time.Instant

/** Un équipement enregistré. [platformAddress] n'apparaît jamais dans `toString` : il ne doit pas finir dans un journal. */
data class DeviceInfo(
    val id: DeviceId,
    val driverId: String,
    val role: DeviceRole,
    val displayName: String,
    val platformAddress: String,
    val model: String?,
    val createdAt: Instant,
    val archivedAt: Instant?,
) {
    /** Vrai si l'équipement existait et n'était pas archivé à un moment de [start, end). */
    fun isActiveDuring(start: Instant, end: Instant): Boolean =
        createdAt < end && (archivedAt == null || archivedAt > start)

    override fun toString(): String = "DeviceInfo(id=$id, driverId=$driverId, role=$role, archived=${archivedAt != null})"
}

class DeviceStore(private val database: VoltdeckDatabase) {
    fun register(
        id: DeviceId,
        driverId: String,
        role: DeviceRole,
        displayName: String,
        platformAddress: String,
        model: String?,
        createdAt: Instant,
    ) {
        database.deviceQueries.insertDevice(
            id.value, driverId, role.name, displayName, platformAddress, model, createdAt.epochSeconds,
        )
    }

    /** Retrait = archivage : l'historique reste (VOL-1 §5.3). */
    fun archive(id: DeviceId, at: Instant) {
        database.deviceQueries.archiveDevice(at.epochSeconds, id.value)
    }

    fun all(): List<DeviceInfo> = database.deviceQueries.selectDevices().executeAsList().map {
        DeviceInfo(
            id = DeviceId(it.id),
            driverId = it.driver_id,
            role = DeviceRole.valueOf(it.role),
            displayName = it.display_name,
            platformAddress = it.platform_address,
            model = it.model,
            createdAt = instantOf(it.created_at),
            archivedAt = it.archived_at?.let(::instantOf),
        )
    }
}
