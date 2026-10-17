package app.voltdeck.drivers.jbd

import app.voltdeck.ble.Adv
import app.voltdeck.ble.AllowedCommand
import app.voltdeck.ble.BleTransport
import app.voltdeck.ble.GattProfile
import app.voltdeck.ble.ForbiddenCommandException
import app.voltdeck.ble.PlatformDeviceId
import app.voltdeck.ble.ReadOnlyGattLink
import app.voltdeck.core.DeviceId
import app.voltdeck.core.DeviceRole
import app.voltdeck.core.Reading
import app.voltdeck.drivers.Candidate
import app.voltdeck.drivers.DeviceDriver
import app.voltdeck.drivers.KnownDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Compteurs de santé d'un BMS, pour l'écran Diagnostic. Aucune donnée d'équipement n'y figure. */
data class JbdCounters(
    val readings: Int = 0,
    /** Trames écartées : somme de contrôle fausse, fin de trame fausse, octets parasites, statut non nul, décodage impossible. */
    val rejectedFrames: Int = 0,
    val timeouts: Int = 0,
    val writeRetries: Int = 0,
    val reconnections: Int = 0,
)

/** Levée par [JbdDriver.confirmIdentity] quand l'équipement ne répond pas comme un BMS JBD. */
class JbdIdentityException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Pilote du BMS JBD / Xiaoxiang (batteries Ultimatron et JBD), en lecture seule.
 *
 * Protocole : service GATT 0xFF00, notifications 0xFF01, requêtes 0xFF02. Les seules requêtes émises sont les
 * [AllowedCommand] 0x03, 0x04 et 0x05 : le pilote n'a aucun moyen d'émettre un octet libre.
 * Sources : voir [JbdFrames][JbdFrameAssembler] et [JbdDecoder][JbdBasicInfo]. Retentative de la première écriture :
 * https://github.com/patman15/BMS_BLE-HA/issues/279.
 *
 * Après chaque coupure, le pilote se reconnecte (2 s, puis le double, jusqu'à 60 s). Pendant un trou il n'émet rien :
 * aucune mesure n'est inventée ni répétée.
 */
class JbdDriver(
    private val clock: Clock = Clock.System,
    private val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    private val responseTimeout: Duration = DEFAULT_RESPONSE_TIMEOUT,
) : DeviceDriver {
    override val driverId: String = DRIVER_ID

    private val countersState = MutableStateFlow<Map<DeviceId, JbdCounters>>(emptyMap())

    /** Compteurs par équipement, depuis la création du pilote. */
    val counters: StateFlow<Map<DeviceId, JbdCounters>> get() = countersState

    override fun identify(adv: Adv): Candidate? {
        if (adv.serviceUuids.none { it.equals(GattProfile.Jbd.serviceUuid, ignoreCase = true) }) return null
        return Candidate(
            driverId = driverId,
            role = DeviceRole.BATTERY,
            platformId = adv.id,
            name = adv.name,
            addressHint = adv.id.value.startsWith(JBD_ADDRESS_PREFIX, ignoreCase = true),
        )
    }

    /**
     * Confirme qu'un candidat est un BMS JBD par la lecture 0x05 (version matérielle), avant de l'enregistrer.
     * Renvoie la version ASCII ; lève [JbdIdentityException] si l'équipement ne répond pas comme un JBD.
     */
    suspend fun confirmIdentity(platformId: PlatformDeviceId, transport: BleTransport): String {
        val link = try {
            transport.connect(platformId, GattProfile.Jbd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw JbdIdentityException("Connexion impossible pour confirmer l'équipement", e)
        }
        return try {
            link.use {
                coroutineScope {
                    val session = JbdSession(link, this, responseTimeout)
                    try {
                        JbdHardwareVersion.decode(session.exchange(AllowedCommand.JbdHardwareVersion, JbdProtocol.REG_HARDWARE_VERSION).data)
                    } finally {
                        session.stop()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: JbdIdentityException) {
            throw e
        } catch (e: Exception) {
            throw JbdIdentityException("L'équipement ne répond pas comme un BMS JBD", e)
        }
    }

    override fun readings(device: KnownDevice, transport: BleTransport): Flow<Reading> = channelFlow {
        var backoff = BACKOFF_INITIAL
        while (true) {
            var produced = false
            try {
                transport.connect(device.platformId, GattProfile.Jbd).use { link ->
                    coroutineScope {
                        val session = JbdSession(link, this, responseTimeout)
                        try {
                            pollForever(device, session) { reading ->
                                produced = true
                                send(reading)
                            }
                        } finally {
                            session.stop()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ForbiddenCommandException) {
                throw e
            } catch (e: Exception) {
                // Coupure, connexion refusée ou trop de cycles ratés : on repart de zéro après le délai.
            }
            bump(device.deviceId) { it.copy(reconnections = it.reconnections + 1) }
            if (produced) backoff = BACKOFF_INITIAL
            delay(backoff)
            backoff = minOf(backoff * 2, BACKOFF_MAX)
        }
    }

    private suspend fun pollForever(device: KnownDevice, session: JbdSession, emit: suspend (Reading) -> Unit) {
        var failedCycles = 0
        while (true) {
            val reading = try {
                pollOnce(device, session)
            } catch (e: JbdCycleException) {
                if (++failedCycles >= MAX_FAILED_CYCLES) {
                    syncCounters(device, session)
                    throw e
                }
                null
            }
            syncCounters(device, session)
            if (reading != null) {
                failedCycles = 0
                bump(device.deviceId) { it.copy(readings = it.readings + 1) }
                emit(reading)
            }
            delay(pollInterval)
        }
    }

    private suspend fun pollOnce(device: KnownDevice, session: JbdSession): Reading {
        val response = session.exchange(AllowedCommand.JbdBasicInfo, JbdProtocol.REG_BASIC_INFO)
        val basic = try {
            JbdBasicInfo.decode(response.data)
        } catch (e: JbdDecodeException) {
            session.rejected++
            throw JbdProtocolException("registre 0x03 illisible", e)
        }
        val at = clock.now()
        val cells = try {
            val cellResponse = session.exchange(AllowedCommand.JbdCellVoltages, JbdProtocol.REG_CELL_VOLTAGES)
            JbdCellVoltages.toSamples(JbdCellVoltages.decode(cellResponse.data, basic.cellCount))
        } catch (e: JbdCycleException) {
            emptyList()
        } catch (e: JbdDecodeException) {
            session.rejected++
            emptyList()
        }
        return Reading(device.deviceId, at, basic.toSamples() + cells)
    }

    /** Reporte dans [counters] ce que la session a compté depuis le dernier report. */
    private fun syncCounters(device: KnownDevice, session: JbdSession) {
        val delta = session.drainCounters()
        if (delta == JbdCounters()) return
        bump(device.deviceId) {
            it.copy(
                rejectedFrames = it.rejectedFrames + delta.rejectedFrames,
                timeouts = it.timeouts + delta.timeouts,
                writeRetries = it.writeRetries + delta.writeRetries,
            )
        }
    }

    private fun bump(id: DeviceId, change: (JbdCounters) -> JbdCounters) {
        countersState.update { it + (id to change(it[id] ?: JbdCounters())) }
    }

    companion object {
        const val DRIVER_ID = "jbd.gatt"
        val DEFAULT_POLL_INTERVAL: Duration = 5.seconds
        val DEFAULT_RESPONSE_TIMEOUT: Duration = 2.seconds

        /** OUI Jiabaida vu sur Android (indice seulement : iOS ne donne pas l'adresse). Source : aiobmsble, jbd_bms.py. */
        const val JBD_ADDRESS_PREFIX = "A4:C1:37"

        internal val BACKOFF_INITIAL: Duration = 2.seconds
        internal val BACKOFF_MAX: Duration = 60.seconds
        internal const val FIRST_WRITE_RETRIES = 3
        internal val FIRST_WRITE_RETRY_DELAY: Duration = 500.milliseconds
        private const val MAX_FAILED_CYCLES = 3
    }
}

internal open class JbdCycleException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal class JbdTimeoutException : JbdCycleException("pas de réponse du BMS")

internal class JbdProtocolException(message: String, cause: Throwable? = null) : JbdCycleException(message, cause)

/**
 * Une connexion : écoute les notifications dans [scope] et pose une question à la fois. La première question
 * après connexion est réessayée jusqu'à [JbdDriver.FIRST_WRITE_RETRIES] fois, 500 ms d'écart (BMS_BLE-HA#279) ; une
 * notification perdue pendant l'abonnement tombe dans le même réessai.
 */
internal class JbdSession(
    private val link: ReadOnlyGattLink,
    scope: CoroutineScope,
    private val responseTimeout: Duration,
) {
    private val assembler = JbdFrameAssembler()
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private var confirmed = false
    private var timeouts = 0
    private var writeRetries = 0

    /** Trames écartées : octets parasites, somme fausse, statut non nul, registre illisible. */
    var rejected = 0

    private val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            link.notifications.collect { inbox.send(it) }
        } finally {
            inbox.close()
        }
    }

    fun stop() = collector.cancel()

    /** Compteurs accumulés depuis le dernier appel. */
    fun drainCounters(): JbdCounters {
        val out = JbdCounters(rejectedFrames = rejected, timeouts = timeouts, writeRetries = writeRetries)
        rejected = 0
        timeouts = 0
        writeRetries = 0
        return out
    }

    suspend fun exchange(cmd: AllowedCommand, register: Int): JbdResponse {
        if (confirmed) return singleExchange(cmd, register)
        var attempt = 0
        while (true) {
            try {
                return singleExchange(cmd, register).also { confirmed = true }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClosedReceiveChannelException) {
                throw e
            } catch (e: ForbiddenCommandException) {
                throw e
            } catch (e: Exception) {
                if (attempt++ >= JbdDriver.FIRST_WRITE_RETRIES) throw e
                writeRetries++
                delay(JbdDriver.FIRST_WRITE_RETRY_DELAY)
            }
        }
    }

    private suspend fun singleExchange(cmd: AllowedCommand, register: Int): JbdResponse {
        assembler.reset()
        purgeInbox()
        val rejectedBefore = assembler.rejectedFrames
        try {
            link.request(cmd)
            val response = withTimeoutOrNull(responseTimeout) { receiveFrame(register) }
            if (response == null) {
                timeouts++
                throw JbdTimeoutException()
            }
            if (!response.isOk) {
                rejected++
                throw JbdProtocolException("le BMS a refusé la requête (statut ${response.status})")
            }
            return response
        } finally {
            rejected += assembler.rejectedFrames - rejectedBefore
        }
    }

    private fun purgeInbox() {
        while (inbox.tryReceive().isSuccess) continue
    }

    /** Ignore les réponses d'un autre registre (réponse tardive à la question précédente). */
    private suspend fun receiveFrame(register: Int): JbdResponse {
        while (true) {
            for (candidate in assembler.feed(inbox.receive())) {
                if (candidate.register == register) return candidate
            }
        }
    }
}
