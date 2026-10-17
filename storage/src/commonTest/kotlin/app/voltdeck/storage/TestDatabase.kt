package app.voltdeck.storage

import app.voltdeck.storage.db.VoltdeckDatabase

/** Base SQLite en mémoire, schéma créé, clés étrangères actives. */
expect fun createTestDatabase(): VoltdeckDatabase
