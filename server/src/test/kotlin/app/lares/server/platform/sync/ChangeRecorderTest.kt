package app.lares.server.platform.sync

import app.lares.server.platform.db.HouseholdSyncStateTable
import app.lares.server.testing.insertHousehold
import app.lares.server.testing.testDeps
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChangeRecorderTest {
    /** Marks the end of the events a test waits for. */
    private val endMarker = HouseholdChanged(UUID(0, 0), -1)

    /** Collects bus events published while [action] runs. */
    private suspend fun collectEvents(
        bus: HouseholdChangeBus,
        action: suspend () -> Unit,
    ): List<HouseholdChanged> =
        kotlinx.coroutines.coroutineScope {
            val collected = async(start = CoroutineStart.UNDISPATCHED) { bus.events.takeWhile { it != endMarker }.toList() }
            action()
            bus.publish(endMarker)
            collected.await()
        }

    @Test
    fun concurrentNextSeqHasNoGapsOrDuplicates() =
        runBlocking {
            val deps = testDeps()
            val householdId = deps.db.insertHousehold()
            val seqs =
                List(20) {
                    async(kotlinx.coroutines.Dispatchers.IO) { deps.db.dbQuery { nextSeq(householdId) } }
                }.awaitAll()
            assertEquals((1L..20L).toList(), seqs.sorted())
        }

    @Test
    fun commitEmitsOnce() =
        runBlocking {
            val deps = testDeps()
            val householdId = deps.db.insertHousehold()
            val events = collectEvents(deps.changeBus) { deps.db.dbQuery { recordChange(householdId) } }
            assertEquals(listOf(HouseholdChanged(householdId, 1)), events)
        }

    @Test
    fun severalChangesInOneTransactionEmitTheLastSeq() =
        runBlocking {
            val deps = testDeps()
            val householdId = deps.db.insertHousehold()
            val events =
                collectEvents(deps.changeBus) {
                    deps.db.dbQuery {
                        recordChange(householdId)
                        recordChange(householdId)
                    }
                }
            assertEquals(listOf(HouseholdChanged(householdId, 2)), events)
        }

    @Test
    fun rollbackEmitsNothingAndKeepsSeq() =
        runBlocking {
            val deps = testDeps()
            val householdId = deps.db.insertHousehold()
            val events =
                collectEvents(deps.changeBus) {
                    assertFailsWith<IllegalStateException> {
                        deps.db.dbQuery {
                            recordChange(householdId)
                            error("rollback")
                        }
                    }
                }
            assertEquals(emptyList(), events)
            val lastSeq =
                deps.db.dbQuery {
                    HouseholdSyncStateTable
                        .selectAll()
                        .where { HouseholdSyncStateTable.householdId eq householdId }
                        .single()[HouseholdSyncStateTable.lastSeq]
                }
            assertEquals(0, lastSeq)
        }

    @Test
    fun unknownHouseholdFails(): Unit =
        runBlocking {
            val deps = testDeps()
            assertFailsWith<IllegalStateException> { deps.db.dbQuery { nextSeq(UUID.randomUUID()) } }
            assertFailsWith<IllegalStateException> { deps.db.dbQuery { lockHousehold(UUID.randomUUID()) } }
        }

    @Test
    fun lockHouseholdSucceedsForExistingHousehold() =
        runBlocking {
            val deps = testDeps()
            val householdId = deps.db.insertHousehold()
            deps.db.dbQuery { lockHousehold(householdId) }
        }
}
