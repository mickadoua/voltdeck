package app.voltdeck

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private var pending: ((Boolean) -> Unit)? = null
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        pending?.invoke(result.values.all { it })
        pending = null
    }

    private val notificationRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val bluetooth = object : BluetoothAccess {
        override fun granted() = BluetoothPermissions.granted(this@MainActivity)

        override fun request(onResult: (Boolean) -> Unit) {
            pending = onResult
            permissionRequest.launch(BluetoothPermissions.REQUIRED)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val background = AndroidBackgroundCollection(applicationContext) { bluetooth.granted() }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { App((application as VoltdeckApplication).runtime, bluetooth, background) }
    }
}
