package app.voltdeck

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlin.time.Clock
import kotlin.time.Instant

internal object BluetoothPermissions {
    val REQUIRED: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun granted(context: Context) = REQUIRED.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}

/** Ce que le téléphone dit au moment du démarrage : lu une fois, puis jugé par [BootPolicy]. */
internal data class BootConditions(
    val restartAfterBoot: Boolean,
    val collectionEnabled: Boolean,
    val hasDevices: Boolean,
    val bluetoothGranted: Boolean,
)

internal object BootPolicy {
    /**
     * La collecte ne repart seule que si l'utilisateur l'a demandé ([BootConditions.restartAfterBoot], désactivé par
     * défaut), qu'il ne l'a pas arrêtée, qu'il a des équipements à lire et que le système autorise encore le Bluetooth.
     * Sinon l'issue dit laquelle de ces conditions manque.
     */
    fun decide(c: BootConditions): BootOutcome = when {
        !c.restartAfterBoot -> BootOutcome.SETTING_OFF
        !c.collectionEnabled -> BootOutcome.STOPPED_BY_USER
        !c.hasDevices -> BootOutcome.NO_DEVICE
        !c.bluetoothGranted -> BootOutcome.NO_PERMISSION
        else -> BootOutcome.STARTED
    }
}

internal object BootRestart {
    /** Ignore toute diffusion autre que le démarrage du téléphone, et note l'issue de chaque tentative pour le Diagnostic. */
    fun run(
        action: String?,
        conditions: () -> BootConditions,
        startService: () -> Unit,
        now: () -> Instant,
        bootCount: () -> Int?,
        record: (BootAttempt) -> Unit,
    ) {
        if (action != Intent.ACTION_BOOT_COMPLETED) return
        val outcome = BootPolicy.decide(conditions())
        if (outcome != BootOutcome.STARTED) return record(BootAttempt(now(), outcome, bootCount = bootCount()))
        try {
            startService()
            record(BootAttempt(now(), BootOutcome.STARTED, bootCount = bootCount()))
        } catch (e: Exception) {
            record(BootAttempt(now(), BootOutcome.REFUSED, e.javaClass.simpleName, bootCount()))
        }
    }
}

internal object ServiceStart {
    /**
     * Passe le service au premier plan. Android peut le refuser par une [SecurityException] (droit manquant) ou, dès
     * l'API 31, par `ForegroundServiceStartNotAllowedException` (une [IllegalStateException]) : les deux sont notées au
     * lieu de faire tomber le processus, et un démarrage réussi efface le refus précédent.
     */
    fun run(startForeground: () -> Unit, now: () -> Instant, recordFailure: (BootAttempt) -> Unit, clearFailure: () -> Unit): Boolean =
        try {
            startForeground()
            clearFailure()
            true
        } catch (e: Exception) {
            if (e !is SecurityException && e !is IllegalStateException) throw e
            recordFailure(BootAttempt(now(), BootOutcome.REFUSED, e.javaClass.simpleName))
            false
        }
}

/** Relance le service de collecte après le démarrage du téléphone, si [BootPolicy] l'autorise. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BootRestart.run(
            action = intent.action,
            conditions = {
                BootConditions(
                    restartAfterBoot = BackgroundPrefs.restartAfterBoot(context),
                    collectionEnabled = BackgroundPrefs.enabled(context),
                    hasDevices = RegisteredDevice
                        .decodeAll(context.getSharedPreferences(DevicePrefs.FILE, Context.MODE_PRIVATE).getString(DevicePrefs.KEY, null))
                        .isNotEmpty(),
                    bluetoothGranted = BluetoothPermissions.granted(context),
                )
            },
            startService = { ContextCompat.startForegroundService(context, CollectionService.intent(context)) },
            now = { Clock.System.now() },
            bootCount = { BackgroundPrefs.bootCount(context) },
            record = { BackgroundPrefs.recordBootAttempt(context, it) },
        )
    }
}
