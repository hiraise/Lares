package app.lares.server.location

import app.lares.contract.api.auth.AuthResult
import app.lares.server.AppDeps
import app.lares.server.platform.db.HouseholdTable
import app.lares.server.platform.toContract
import app.lares.server.platform.toDb
import app.lares.server.testing.ADMIN_PASSWORD
import app.lares.server.testing.createSecondHousehold
import app.lares.server.testing.fix
import app.lares.server.testing.jsonClient
import app.lares.server.testing.login
import app.lares.server.testing.testApp
import app.lares.server.testing.testClock
import io.ktor.client.call.body
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** History retention per household (LOC-005). */
class LocationRetentionTest {
    private suspend fun AppDeps.store(
        householdId: UUID,
        memberId: UUID,
        vararg times: Instant,
    ) = db.dbQuery {
        val now = clock.now().toDb()
        LocationStateRepository.lockOrCreate(householdId, memberId, now)
        times.forEach { at ->
            val fix = fix(LAT, LON, at)
            PositionRepository.insert(householdId, memberId, UUID.randomUUID(), fix, now)
            LocationStateRepository.writeLast(memberId, fix, now)
        }
    }

    private suspend fun AppDeps.storedTimes(memberId: UUID): List<Instant> =
        db.dbQuery {
            PositionTable
                .select(PositionTable.recordedAt)
                .where { PositionTable.memberId eq memberId }
                .orderBy(PositionTable.recordedAt to SortOrder.ASC)
                .map { it[PositionTable.recordedAt].toContract() }
        }

    @Test
    fun loc005OldPositionsAreDeletedPerHousehold() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            val otherHousehold = deps.createSecondHousehold()
            val other = client.login("admin-b", ADMIN_PASSWORD).body<AuthResult>()
            deps.db.dbQuery { HouseholdTable.update({ HouseholdTable.id eq otherHousehold }) { it[locationHistoryDays] = 90 } }
            val now = deps.testClock.now()

            // Stored oldest first, so `last` of the kid is the 40-day-old fix.
            deps.store(family.householdId, family.adult.memberUuid, now - 40.days, now - 10.days)
            deps.store(family.householdId, family.kid.memberUuid, now - 40.days)
            deps.store(otherHousehold, other.memberUuid, now - 100.days, now - 60.days)

            assertEquals(3, deps.locationRetentionJob.runOnce(now))
            assertEquals(listOf(now - 10.days), deps.storedTimes(family.adult.memberUuid))
            assertEquals(emptyList(), deps.storedTimes(family.kid.memberUuid))
            assertEquals(listOf(now - 60.days), deps.storedTimes(other.memberUuid))

            val states = deps.db.dbQuery { LocationStateRepository.load(family.householdId) }
            assertNotNull(states.getValue(family.adult.memberUuid).last)
            assertNull(states.getValue(family.kid.memberUuid).last)
            assertNotNull(
                deps.db
                    .dbQuery { LocationStateRepository.load(otherHousehold) }
                    .getValue(other.memberUuid)
                    .last,
            )

            assertEquals(0, deps.locationRetentionJob.runOnce(now))
        }
}
