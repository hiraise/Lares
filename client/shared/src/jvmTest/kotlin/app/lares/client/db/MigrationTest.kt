package app.lares.client.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationTest {
    private fun JdbcSqliteDriver.schemaOf(vararg tables: String): Map<String, List<String>> =
        tables.associateWith { table ->
            executeQuery(
                null,
                "SELECT name || ' ' || type || ' ' || \"notnull\" || ' ' || pk FROM pragma_table_info('$table') ORDER BY cid",
                { cursor ->
                    val columns = mutableListOf<String>()
                    while (cursor.next().value) columns += cursor.getString(0)!!
                    app.cash.sqldelight.db.QueryResult
                        .Value(columns)
                },
                0,
            ).value
        }

    private fun JdbcSqliteDriver.indexes(): List<String> =
        executeQuery(
            null,
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE '%task%' AND name NOT LIKE 'sqlite_%' ORDER BY name",
            { cursor ->
                val names = mutableListOf<String>()
                while (cursor.next().value) names += cursor.getString(0)!!
                app.cash.sqldelight.db.QueryResult
                    .Value(names)
            },
            0,
        ).value

    @Test
    fun version2DatabaseGetsTheTaskTablesOfAFreshInstall() {
        val tables = arrayOf("task", "checklist_item", "task_completion")
        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LaresDb.Schema.create(it) }

        // An M0 database (schema version 2): everything except the task tables.
        val old = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LaresDb.Schema.create(old)
        tables.forEach { old.execute(null, "DROP TABLE $it", 0) }
        assertEquals(emptyList(), old.indexes())

        LaresDb.Schema.migrate(old, 2, 3)
        assertEquals(3L, LaresDb.Schema.version)
        assertEquals(fresh.schemaOf(*tables), old.schemaOf(*tables))
        assertEquals(fresh.indexes(), old.indexes())
        assertEquals(listOf("checklist_item_task", "task_completion_task"), old.indexes())
    }
}
