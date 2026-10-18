package app.voltdeck

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

internal object BluetoothPermissions {
    val REQUIRED: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun granted(context: Context) = REQUIRED.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}

internal object BootPolicy {
    /**
     * La collecte ne repart seule que si l'utilisateur l'a demandé ([restartAfterBoot], désactivé par défaut), qu'il ne l'a
     * pas arrêtée ([collectionEnabled]), qu'il a des équipements à lire et que le système autorise encore le Bluetooth.
     */
    fun shouldStart(restartAfterBoot: Boolean, collectionEnabled: Boolean, hasDevices: Boolean, bluetoothGranted: Boolean) =
        restartAfterBoot && collectionEnabled && hasDevices && bluetoothGranted
}

/** Relance le service de collecte après le démarrage du téléphone, si [BootPolicy] l'autorise. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val devices = RegisteredDevice.decodeAll(context.getSharedPreferences(DevicePrefs.FILE, Context.MODE_PRIVATE).getString(DevicePrefs.KEY, null))
        val start = BootPolicy.shouldStart(
            restartAfterBoot = BackgroundPrefs.restartAfterBoot(context),
            collectionEnabled = BackgroundPrefs.enabled(context),
            hasDevices = devices.isNotEmpty(),
            bluetoothGranted = BluetoothPermissions.granted(context),
        )
        if (!start) return
        try {
            ContextCompat.startForegroundService(context, CollectionService.intent(context))
        } catch (_: Exception) {
            // Démarrage refusé par le système : la collecte reprendra à la prochaine ouverture de Voltdeck.
        }
    }
}
