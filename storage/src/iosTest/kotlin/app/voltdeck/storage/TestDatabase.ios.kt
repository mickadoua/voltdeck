package app.voltdeck.storage

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.voltdeck.storage.db.VoltdeckDatabase
import co.touchlab.sqliter.DatabaseConfiguration

actual fun createTestDatabase(): VoltdeckDatabase = VoltdeckDatabase(
    NativeSqliteDriver(
        schema = VoltdeckDatabase.Schema,
        name = "voltdeck-test.db",
        onConfiguration = { it.copy(inMemory = true, extendedConfig = DatabaseConfiguration.Extended(foreignKeyConstraints = true)) },
    ),
)
