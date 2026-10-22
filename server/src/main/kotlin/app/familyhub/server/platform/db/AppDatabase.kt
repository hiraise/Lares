package app.familyhub.server.platform.db

import app.familyhub.server.platform.DbConfig
import app.familyhub.server.platform.sync.HouseholdChangeBus
import app.familyhub.server.platform.sync.PendingChanges
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import javax.sql.DataSource

/**
 * Entry point for all database access: `dbQuery { … }` runs a block in one transaction.
 * Household changes recorded inside the block (`recordChange`) are published to [changeBus] only after a successful commit.
 */
class AppDatabase(
    private val database: Database,
    private val changeBus: HouseholdChangeBus,
) {
    suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T {
        var pending = PendingChanges()
        val result =
            withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    // A fresh holder per attempt, so nothing from a rolled-back attempt leaks out.
                    pending = PendingChanges().also { putUserData(PendingChanges.Key, it) }
                    block()
                }
            }
        pending.events.forEach(changeBus::publish)
        return result
    }

    companion object {
        /** Connection pool sized for the 1 GB VPS budget (ADR-0013). */
        fun createDataSource(config: DbConfig): HikariDataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = config.url
                    username = config.user
                    password = config.password
                    maximumPoolSize = 5
                    minimumIdle = 1
                    connectionTimeout = 5_000
                    poolName = "familyhub"
                },
            )

        fun migrate(dataSource: DataSource) {
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }

        fun connect(dataSource: DataSource): Database =
            Database.connect(
                datasource = dataSource,
                databaseConfig =
                    DatabaseConfig {
                        // No automatic retries: a failed write is reported to the caller, not silently repeated.
                        defaultMaxAttempts = 1
                    },
            )
    }
}
