package app.voltdeck.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.voltdeck.storage.db.VoltdeckDatabase

actual fun createTestDatabase(): VoltdeckDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY + "?foreign_keys=true")
    VoltdeckDatabase.Schema.create(driver)
    return VoltdeckDatabase(driver)
}
