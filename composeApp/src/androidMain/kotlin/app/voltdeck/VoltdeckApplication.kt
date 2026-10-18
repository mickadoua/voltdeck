package app.voltdeck

import android.app.Application
import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.voltdeck.ble.KableTransport
import app.voltdeck.storage.DatabaseDriverFactory
import app.voltdeck.storage.StorageFiles
import app.voltdeck.storage.db.VoltdeckDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob

class VoltdeckApplication : Application() {
    val runtime: VoltdeckRuntime by lazy {
        val prefs = getSharedPreferences(DevicePrefs.FILE, Context.MODE_PRIVATE)
        VoltdeckRuntime(
            keys = AndroidKeystoreVictronKeyStore(this),
            shuntSetups = AndroidShuntSetupStore(this),
            registry = TextDeviceRegistry({ prefs.getString(DevicePrefs.KEY, null) }, { prefs.edit().putString(DevicePrefs.KEY, it).apply() }),
            transportFactory = { KableTransport() },
            databases = AndroidDatabases(this),
            scope = CoroutineScope(SupervisorJob() + collectorDispatcher()),
        ).also { it.start() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun collectorDispatcher() = Dispatchers.Default.limitedParallelism(1)

}

internal object DevicePrefs {
    const val FILE = "voltdeck-devices"
    const val KEY = "devices"
}

private class AndroidDatabases(private val context: Context) : Databases {
    private val factory = DatabaseDriverFactory(context)
    private var realDb: VoltdeckDatabase? = null
    private var demoDriver: SqlDriver? = null

    override fun real(): VoltdeckDatabase = realDb ?: VoltdeckDatabase(factory.create(StorageFiles.REAL)).also { realDb = it }

    override fun freshDemo(): VoltdeckDatabase {
        demoDriver?.close()
        context.deleteDatabase(StorageFiles.DEMO)
        val driver = factory.create(StorageFiles.DEMO)
        demoDriver = driver
        return VoltdeckDatabase(driver)
    }
}
