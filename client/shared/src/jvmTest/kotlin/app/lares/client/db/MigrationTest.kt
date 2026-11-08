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

    private fun JdbcSqliteDriver.indexes(like: String = "%task%"): List<String> =
        executeQuery(
            null,
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE '$like' AND name NOT LIKE 'sqlite_%' ORDER BY name",
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

        // An M0 database (schema version 2): everything except the task and shopping tables.
        val old = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LaresDb.Schema.create(old)
        (tables + SHOPPING_TABLES + CALENDAR_TABLES).forEach { old.execute(null, "DROP TABLE $it", 0) }
        assertEquals(emptyList(), old.indexes())

        LaresDb.Schema.migrate(old, 2, 3)
        assertEquals(fresh.schemaOf(*tables), old.schemaOf(*tables))
        assertEquals(fresh.indexes(), old.indexes())
        assertEquals(listOf("checklist_item_task", "task_completion_task"), old.indexes())
    }

    @Test
    fun version3DatabaseGetsTheShoppingTablesOfAFreshInstall() {
        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LaresDb.Schema.create(it) }

        // An M1 database (schema version 3): everything except the shopping tables.
        val old = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LaresDb.Schema.create(old)
        (SHOPPING_TABLES + CALENDAR_TABLES).forEach { old.execute(null, "DROP TABLE $it", 0) }
        assertEquals(emptyList(), old.indexes("shopping%"))

        LaresDb.Schema.migrate(old, 3, 4)
        assertEquals(fresh.schemaOf(*SHOPPING_TABLES), old.schemaOf(*SHOPPING_TABLES))
        assertEquals(listOf("shopping_item_key", "shopping_item_list"), old.indexes("shopping%"))
    }

    @Test
    fun version4DatabaseGetsTheCalendarTablesOfAFreshInstall() {
        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LaresDb.Schema.create(it) }

        // An M2 database (schema version 4): everything except the calendar tables.
        val old = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LaresDb.Schema.create(old)
        CALENDAR_TABLES.forEach { old.execute(null, "DROP TABLE $it", 0) }
        assertEquals(emptyList(), old.indexes("event%"))

        LaresDb.Schema.migrate(old, 4, 5)
        assertEquals(fresh.schemaOf(*CALENDAR_TABLES), old.schemaOf(*CALENDAR_TABLES))
        assertEquals(listOf("event_occurrence_event", "event_reminder_event"), old.indexes("event%"))
    }

    @Test
    fun version5DatabaseGetsTheLocationTablesOfAFreshInstall() {
        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LaresDb.Schema.create(it) }

        // An M3 database (schema version 5): everything except the location tables.
        val old = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LaresDb.Schema.create(old)
        LOCATION_TABLES.forEach { old.execute(null, "DROP TABLE $it", 0) }
        assertEquals(emptyList(), old.indexes("position%"))

        LaresDb.Schema.migrate(old, 5, 6)
        assertEquals(fresh.schemaOf(*LOCATION_TABLES), old.schemaOf(*LOCATION_TABLES))
        assertEquals(listOf("position_outbox_recorded"), old.indexes("position%"))
    }

    @Test
    fun version6DatabaseGetsTheBudgetTablesOfAFreshInstall() {
        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LaresDb.Schema.create(it) }

        // An M4 database (schema version 6): everything except the budget tables.
        val old = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LaresDb.Schema.create(old)
        BUDGET_TABLES.forEach { old.execute(null, "DROP TABLE $it", 0) }
        assertEquals(emptyList(), old.indexes("budget%"))

        LaresDb.Schema.migrate(old, 6, 7)
        assertEquals(7L, LaresDb.Schema.version)
        assertEquals(fresh.schemaOf(*BUDGET_TABLES), old.schemaOf(*BUDGET_TABLES))
        assertEquals(listOf("budget_funding_month", "budget_month_month", "budget_receipt_month"), old.indexes("budget%"))
    }

    private companion object {
        val SHOPPING_TABLES = arrayOf("shopping_list", "shopping_item")
        val CALENDAR_TABLES = arrayOf("event", "event_occurrence", "event_reminder", "notification_pref")
        val LOCATION_TABLES = arrayOf("place", "place_alert", "position_outbox", "location_pref", "location_snapshot")
        val BUDGET_TABLES = arrayOf("budget_plan", "budget_month", "budget_receipt", "budget_funding")
    }
}
