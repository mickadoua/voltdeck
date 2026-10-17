package app.voltdeck

import androidx.compose.ui.window.ComposeUIViewController
import app.voltdeck.ble.KableTransport
import platform.Foundation.NSUserDefaults

private const val KEY_DEVICES = "voltdeck.devices"

fun MainViewController() = ComposeUIViewController {
    val defaults = NSUserDefaults.standardUserDefaults
    App(
        KeychainVictronKeyStore(),
        UserDefaultsShuntSetupStore(),
        TextDeviceRegistry({ defaults.stringForKey(KEY_DEVICES) }, { defaults.setObject(it, forKey = KEY_DEVICES) }),
        AlwaysGrantedBluetooth,
    ) { KableTransport() }
}
