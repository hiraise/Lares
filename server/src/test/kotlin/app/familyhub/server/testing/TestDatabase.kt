package app.familyhub.server.testing

import app.familyhub.server.platform.DbConfig
import app.familyhub.server.platform.db.AppDatabase
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource

/** One PostgreSQL container per test JVM, migrated once. Tests call [reset] to start from empty tables. */
object TestDatabase {
    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:18-alpine").apply { start() }
    }

    val dbConfig: DbConfig by lazy { DbConfig(container.jdbcUrl, container.username, container.password) }

    val dataSource: DataSource by lazy {
        AppDatabase.createDataSource(dbConfig).also(AppDatabase::migrate)
    }

    val database: Database by lazy { AppDatabase.connect(dataSource) }

    /** Empties every table except Flyway's history. */
    fun reset() {
        dataSource.connection.use { connection ->
            val tables =
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
                        ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
                }
            if (tables.isNotEmpty()) {
                connection.createStatement().use { statement ->
                    statement.execute("TRUNCATE ${tables.joinToString { "\"$it\"" }} RESTART IDENTITY CASCADE")
                }
            }
        }
    }
}
