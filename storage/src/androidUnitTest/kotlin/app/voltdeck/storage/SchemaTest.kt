package app.voltdeck.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.voltdeck.storage.db.VoltdeckDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Garde-fou du schéma de la migration 1 (VOL-1 §5.3). Les colonnes ci-dessous sont celles de la note technique validée :
 * toute différence (table, colonne, rétention) se discute avec l'architecte avant le code, puis par une nouvelle migration,
 * jamais en modifiant 1.sqm. (SQLDelight, en mode « schéma dérivé des migrations », ne crée pas de tâche de vérification
 * sur instantané .db : ce test en tient lieu.)
 */
class SchemaTest {
    private val aggregateColumns = listOf("device_id", "metric", "channel", "bucket_start", "count", "min", "max", "sum", "last")
    private val statColumns = listOf("count", "min", "max", "sum", "last")

    private val expected = mapOf(
        "device" to listOf("id", "driver_id", "role", "display_name", "platform_address", "model", "created_at", "archived_at"),
        "agg_minute" to aggregateColumns,
        "agg_hour" to aggregateColumns,
        "agg_day" to listOf("device_id", "metric", "channel", "day", "tz_id") + statColumns,
        "agg_month" to listOf("device_id", "metric", "channel", "month", "tz_id") + statColumns,
    )

    private fun query(driver: SqlDriver, sql: String): List<List<String?>> =
        driver.executeQuery(null, sql, { cursor ->
            val rows = ArrayList<List<String?>>()
            while (cursor.next().value) rows += listOf(cursor.getString(0), cursor.getString(1))
            QueryResult.Value(rows)
        }, 0).value

    private fun columns(driver: SqlDriver): Map<String, List<String?>> =
        query(driver, "SELECT name, name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")
            .associate { (table, _) -> table!! to query(driver, "SELECT name, type FROM pragma_table_info('$table')").map { it[0] } }

    @Test
    fun migrationOneCreatesTheValidatedTablesAndColumns() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VoltdeckDatabase.Schema.create(driver)
        assertEquals(expected, columns(driver))
    }

    @Test
    fun upgradingFromAnEmptyDatabaseGivesTheSameSchemaAsAFreshInstall() {
        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VoltdeckDatabase.Schema.create(fresh)
        val upgraded = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VoltdeckDatabase.Schema.migrate(upgraded, 1, VoltdeckDatabase.Schema.version)
        assertEquals(columns(fresh), columns(upgraded))
        assertEquals(2L, VoltdeckDatabase.Schema.version, "une seule migration (1.sqm) : schéma en version 2")
    }
}
