package app.voltdeck

import app.voltdeck.core.DeviceId
import platform.Foundation.NSUserDefaults

class UserDefaultsShuntSetupStore : ShuntSetupStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun get(device: DeviceId): ShuntSetup? = defaults.stringForKey(key(device))?.let(ShuntSetup::decode)

    override fun put(device: DeviceId, setup: ShuntSetup) {
        defaults.setObject(setup.encode(), forKey = key(device))
    }

    private fun key(device: DeviceId) = "voltdeck.shunt.${device.value}"
}
