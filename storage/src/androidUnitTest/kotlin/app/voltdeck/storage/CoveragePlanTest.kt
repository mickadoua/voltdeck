package app.voltdeck.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.voltdeck.core.DeviceRole
import app.voltdeck.storage.db.VoltdeckDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** Relève le plan SQLite de la requête de couverture, tel que SQLite l'exécute réellement. */
private class PlanSpy(private val inner: SqlDriver) : SqlDriver by inner {
    val plans = mutableListOf<String>()

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        if ("COUNT(DISTINCT bucket_start)" in sql) {
            inner.executeQuery(null, "EXPLAIN QUERY PLAN $sql", { cursor ->
                while (cursor.next().value) plans += cursor.getString(3).orEmpty()
                QueryResult.Value(Unit)
            }, parameters, binders)
        }
        return inner.executeQuery(identifier, sql, mapper, parameters, binders)
    }
}

class CoveragePlanTest {
    @Test
    fun coverageSeeksTheWindowByPrimaryKeyInsteadOfScanningTheDevicesWholeHistory() {
        val spy = PlanSpy(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY + "?foreign_keys=true"))
        VoltdeckDatabase.Schema.create(spy)
        val db = VoltdeckDatabase(spy)
        db.addDevice(BMS, DeviceRole.BATTERY, createdAt = EPOCH_2026)
        CoverageReader(db).minutes(BMS, EPOCH_2026 + 3.hours)
        val plan = spy.plans.single { "agg_minute" in it }
        assertTrue(
            "device_id=? AND metric=? AND channel=? AND bucket_start>? AND bucket_start<?" in plan,
            "la couverture doit chercher par clé primaire complète, plan SQLite : $plan",
        )
        assertEquals(1, spy.plans.count { "agg_minute" in it })
    }
}
