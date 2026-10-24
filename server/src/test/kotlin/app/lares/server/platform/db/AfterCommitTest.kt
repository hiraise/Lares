package app.lares.server.platform.db

import app.lares.server.platform.sync.HouseholdChangeBus
import app.lares.server.testing.TestDatabase
import app.lares.server.testing.testDeps
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `JdbcTransaction.afterCommit` (plan M1-03): runs after commit only, once per successful attempt. */
class AfterCommitTest {
    @Test
    fun runsAfterCommitInOrder() =
        runBlocking {
            val deps = testDeps()
            val calls = mutableListOf<String>()
            val result =
                deps.db.dbQuery {
                    afterCommit { calls += "first" }
                    afterCommit { calls += "second" }
                    // Nothing runs while the transaction is open.
                    assertTrue(calls.isEmpty())
                    "done"
                }
            assertEquals("done", result)
            assertEquals(listOf("first", "second"), calls)
        }

    @Test
    fun notOnRollback() =
        runBlocking {
            val deps = testDeps()
            val calls = mutableListOf<String>()
            assertFailsWith<IllegalStateException> {
                deps.db.dbQuery {
                    afterCommit { calls += "rolled back" }
                    error("rollback")
                }
            }
            assertEquals(emptyList(), calls)
        }

    @Test
    fun failingActionDoesNotStopOthers() =
        runBlocking {
            val deps = testDeps()
            val calls = mutableListOf<String>()
            deps.db.dbQuery {
                afterCommit { error("boom") }
                afterCommit { calls += "after" }
            }
            assertEquals(listOf("after"), calls)
        }

    @Test
    fun retriedAttemptKeepsOnlyTheSuccessfulAttemptsActions() =
        runBlocking {
            testDeps()
            val retrying = Database.connect(TestDatabase.dataSource, databaseConfig = DatabaseConfig { defaultMaxAttempts = 2 })
            val db = AppDatabase(retrying, HouseholdChangeBus())
            var attempt = 0
            val calls = mutableListOf<String>()
            db.dbQuery {
                attempt++
                afterCommit { calls += "attempt $attempt" }
                if (attempt == 1) throw SQLException("retry me")
            }
            assertEquals(2, attempt)
            assertEquals(listOf("attempt 2"), calls)
        }
}
