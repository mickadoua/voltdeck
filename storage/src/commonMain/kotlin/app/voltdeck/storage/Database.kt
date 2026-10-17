package app.voltdeck.storage

import app.cash.sqldelight.db.SqlDriver
import app.voltdeck.storage.db.VoltdeckDatabase

/** Noms des fichiers de base : le mode démo a son propre fichier et ne touche jamais aux données réelles (VOL-1 §5.4). */
object StorageFiles {
    const val REAL = "voltdeck.db"
    const val DEMO = "voltdeck-demo.db"

    fun nameFor(demo: Boolean) = if (demo) DEMO else REAL
}

/** Crée le driver SQLite de la plateforme, clés étrangères activées, pour le fichier [fileName]. */
expect class DatabaseDriverFactory {
    fun create(fileName: String): SqlDriver
}

fun openDatabase(factory: DatabaseDriverFactory, demo: Boolean): VoltdeckDatabase =
    VoltdeckDatabase(factory.create(StorageFiles.nameFor(demo)))
