package app.familyhub.client.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.familyhub.client.db.FamilyHubDb
import app.familyhub.client.db.LocalDb
import kotlinx.coroutines.Dispatchers

/** A fresh in-memory database, as the JVM platform module creates it. */
fun testLocalDb(): LocalDb {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { FamilyHubDb.Schema.create(it) }
    return LocalDb(driver, Dispatchers.IO.limitedParallelism(1))
}
