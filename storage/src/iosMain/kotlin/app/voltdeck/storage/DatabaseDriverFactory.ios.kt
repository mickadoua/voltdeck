package app.voltdeck.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.wrapConnection
import app.voltdeck.storage.db.VoltdeckDatabase
import co.touchlab.sqliter.DatabaseConfiguration

actual class DatabaseDriverFactory {
    actual fun create(fileName: String): SqlDriver = NativeSqliteDriver(
        schema = VoltdeckDatabase.Schema,
        name = fileName,
        onConfiguration = { it.copy(extendedConfig = DatabaseConfiguration.Extended(foreignKeyConstraints = true)) },
    )
}
