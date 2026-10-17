package app.voltdeck

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import app.voltdeck.ble.KableTransport

class MainActivity : ComponentActivity() {
    private var pending: ((Boolean) -> Unit)? = null
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        pending?.invoke(result.values.all { it })
        pending = null
    }

    private val bluetooth = object : BluetoothAccess {
        override fun granted() = BLUETOOTH_PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

        override fun request(onResult: (Boolean) -> Unit) {
            pending = onResult
            permissionRequest.launch(BLUETOOTH_PERMISSIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val keys = AndroidKeystoreVictronKeyStore(this)
        val prefs = getSharedPreferences("voltdeck-devices", Context.MODE_PRIVATE)
        val registry = TextDeviceRegistry({ prefs.getString(KEY_DEVICES, null) }, { prefs.edit().putString(KEY_DEVICES, it).apply() })
        setContent { App(keys, AndroidShuntSetupStore(this), registry, bluetooth) { KableTransport() } }
    }

    private companion object {
        const val KEY_DEVICES = "devices"
        val BLUETOOTH_PERMISSIONS =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }
}
