package app.voltdeck.storage

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.voltdeck.storage.db.VoltdeckDatabase

actual class DatabaseDriverFactory(private val context: Context) {
    actual fun create(fileName: String): SqlDriver = AndroidSqliteDriver(
        schema = VoltdeckDatabase.Schema,
        context = context,
        name = fileName,
        callback = object : AndroidSqliteDriver.Callback(VoltdeckDatabase.Schema) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
            }
        },
    )
}
