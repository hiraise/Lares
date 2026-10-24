package app.lares.server.platform.db

import app.lares.server.platform.DbConfig
import app.lares.server.platform.sync.HouseholdChangeBus
import app.lares.server.platform.sync.PendingChanges
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import javax.sql.DataSource

private val log = KotlinLogging.logger {}

/**
 * Runs [action] after this transaction commits, after its change events are published; never on rollback. Must run
 * inside `AppDatabase.dbQuery`. The action must be quick and must not block (e.g. put an event into a channel);
 * exceptions are logged and swallowed.
 */
fun JdbcTransaction.afterCommit(action: () -> Unit) {
    val pending = getUserData(PendingChanges.Key) ?: error("afterCommit must run inside AppDatabase.dbQuery")
    pending.addAfterCommit(action)
}

/**
 * Entry point for all database access: `dbQuery { … }` runs a block in one transaction.
 * Household changes recorded inside the block (`recordChange`) are published to [changeBus] only after a successful commit,
 * followed by the block's [afterCommit] actions.
 */
class AppDatabase(
    private val database: Database,
    private val changeBus: HouseholdChangeBus,
) {
    /**
     * [isolation] is a `java.sql.Connection.TRANSACTION_*` level (default: the database default, READ COMMITTED);
     * sync pulls use REPEATABLE READ to read all tables from one snapshot.
     */
    suspend fun <T> dbQuery(
        isolation: Int? = null,
        readOnly: Boolean = false,
        block: suspend JdbcTransaction.() -> T,
    ): T {
        var pending = PendingChanges()
        val result =
            withContext(Dispatchers.IO) {
                suspendTransaction(db = database, transactionIsolation = isolation, readOnly = readOnly) {
                    // A fresh holder per attempt, so nothing from a rolled-back attempt leaks out.
                    pending = PendingChanges().also { putUserData(PendingChanges.Key, it) }
                    block()
                }
            }
        pending.events.forEach(changeBus::publish)
        for (action in pending.afterCommit) {
            try {
                action()
            } catch (e: Exception) {
                log.error(e) { "After-commit action failed" }
            }
        }
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
                    poolName = "lares"
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
