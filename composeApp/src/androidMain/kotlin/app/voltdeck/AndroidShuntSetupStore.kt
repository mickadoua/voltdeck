package app.voltdeck

import android.content.Context
import app.voltdeck.core.DeviceId

class AndroidShuntSetupStore(context: Context) : ShuntSetupStore {
    private val prefs = context.getSharedPreferences("voltdeck-shunts", Context.MODE_PRIVATE)

    override fun get(device: DeviceId): ShuntSetup? = prefs.getString(device.value, null)?.let(ShuntSetup::decode)

    override fun put(device: DeviceId, setup: ShuntSetup) {
        prefs.edit().putString(device.value, setup.encode()).apply()
    }
}
