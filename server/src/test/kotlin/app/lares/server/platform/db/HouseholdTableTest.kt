package app.lares.server.platform.db

import app.lares.server.testing.insertHousehold
import app.lares.server.testing.testDeps
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class HouseholdTableTest {
    @Test
    fun timestampsRoundTripAsInstants() =
        runBlocking {
            val deps = testDeps()
            val id = deps.db.insertHousehold()
            val instant = Instant.parse("2026-10-22T10:15:30.123456Z")
            val stored =
                deps.db.dbQuery {
                    HouseholdTable.update({ HouseholdTable.id eq id }) { it[updatedAt] = instant }
                    HouseholdTable.selectAll().where { HouseholdTable.id eq id }.single()[HouseholdTable.updatedAt]
                }
            assertEquals(instant, stored)
            // The column is timestamptz: PostgreSQL itself must see the same UTC moment.
            val asText =
                deps.db.dbQuery {
                    exec("SELECT to_char(updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US') FROM household") { rs ->
                        rs.next()
                        rs.getString(1)
                    }
                }
            assertEquals("2026-10-22T10:15:30.123456", asText)
        }
}
