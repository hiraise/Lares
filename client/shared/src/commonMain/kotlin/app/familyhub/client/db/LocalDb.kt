package app.familyhub.client.db

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The local SQLite database and the dispatcher all database work runs on. Platform modules choose the dispatcher:
 * `Dispatchers.IO` on Android, a single-threaded view of it on the JVM (the in-memory JDBC driver has one connection).
 */
class LocalDb(
    driver: SqlDriver,
    val dispatcher: CoroutineDispatcher,
) {
    val db: FamilyHubDb = FamilyHubDb(driver)

    /** Runs [block] in one SQLite transaction. */
    suspend fun <T> transaction(block: FamilyHubDb.() -> T): T = withContext(dispatcher) { db.transactionWithResult { db.block() } }

    suspend fun <T> read(block: FamilyHubDb.() -> T): T = withContext(dispatcher) { db.block() }

    /** Deletes all local data (logout, revoked session). */
    suspend fun clearAll() =
        transaction {
            householdQueries.deleteAll()
            memberQueries.deleteAll()
            group_entityQueries.deleteAll()
            personQueries.deleteAll()
            tagQueries.deleteAll()
            sync_shadowQueries.deleteAll()
            sync_outboxQueries.deleteAll()
            sync_stateQueries.deleteAll()
            sync_rejectionQueries.deleteAll()
            local_sessionQueries.deleteAll()
            reminderQueries.deleteAll()
            notification_muteQueries.deleteAll()
            push_endpointQueries.deleteAll()
        }
}
