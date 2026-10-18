package app.voltdeck.storage

import app.cash.sqldelight.driver.native.inMemoryDriver
import app.voltdeck.storage.db.VoltdeckDatabase
import co.touchlab.sqliter.DatabaseConfiguration

// Sans nom : sqliter ouvre ":memory:", privé à ce driver. Avec un nom il ouvre une base partagée du processus
// ("file:<nom>?mode=memory&cache=shared") et chaque test hériterait des lignes du précédent.
actual fun createTestDatabase(): VoltdeckDatabase = VoltdeckDatabase(
    inMemoryDriver(VoltdeckDatabase.Schema, DatabaseConfiguration.Extended(foreignKeyConstraints = true)),
)
