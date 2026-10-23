package app.lares.integration

import app.lares.client.auth.SessionState
import app.lares.client.sync.LocalForbiddenException
import app.lares.client.sync.LocalWriteException
import app.lares.client.sync.SyncState
import app.lares.contract.api.admin.SetGroupsRequest
import app.lares.contract.api.admin.UpdateGroupRequest
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.PersonData
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.PermissionCatalogue
import app.lares.server.testing.ADMIN_LOGIN
import app.lares.server.testing.ADMIN_PASSWORD
import app.lares.server.testing.TestDatabase
import kotlinx.coroutines.flow.first
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/** Offline work, realtime, resets, revocation and permissions across several client cores (M0-06). */
class SyncScenariosTest {
    @Test
    fun `offline writes show at once and converge when the network returns`() =
        familyTest {
            boris.network.online = false
            boris.tags.create("Offline", PaletteColor.TEAL)
            boris.tagsRepository.observeAll().await { tags -> tags.any { it.data.name == "Offline" } }
            val offline = boris.engine.status.await { it.state == SyncState.OFFLINE }
            assertTrue(offline.pendingCount > 0)

            boris.network.online = true
            boris.engine.request()
            boris.engine.status.await { it.state == SyncState.SYNCED && it.pendingCount == 0L }
            assertConverged(all)
            assertEquals(1, anna.tagsNamed("Offline").size)
        }

    @Test
    fun `a connected client sees another client's change within 2 seconds`() =
        familyTest {
            boris.engine.setForeground(true)
            boris.engine.realtimeConnected.await { it }
            anna.tags.create("Live", PaletteColor.PURPLE)
            anna.sync()
            boris.tagsRepository.observeAll().await(timeout = 2.seconds) { tags -> tags.any { it.data.name == "Live" } }

            boris.engine.setForeground(false)
            boris.engine.realtimeConnected.await { !it }
        }

    @Test
    fun `6 - reset after a tombstone purge - full resync keeps the outbox`() =
        familyTest {
            val id = anna.tags.create("Old", PaletteColor.BROWN)
            anna.sync()
            anna.tags.delete(id)
            anna.sync()
            assertTrue(server.deps.syncHousekeeping.purgeTombstones(server.clock.now() + 91.days) > 0)

            assertFullResyncKeepsOutbox(boris)
            assertConverged(all)
        }

    @Test
    fun `6 - reset after an access change`() =
        familyTest {
            anna.api.setMemberGroups(borisMemberId, SetGroupsRequest(setOf(adultsId, kidsId)))
            assertFullResyncKeepsOutbox(boris)
            assertConverged(all)
        }

    @Test
    fun `6 - reset when the server is behind the client's cursor`() =
        familyTest {
            val lastSeq = sql("SELECT last_seq FROM household_sync_state")
            sql("UPDATE household_sync_state SET last_seq = 1")
            // Put the sequence back before the full sync, so no write gets a seq below the other clients' cursors
            // (a real restore also sets a new epoch, which resets every client; see invariant 10).
            assertFullResyncKeepsOutbox(boris, onFullSync = { sql("UPDATE household_sync_state SET last_seq = $lastSeq") })
            assertConverged(all)
        }

    @Test
    fun `10 - every client resets after a restore, and a full sync after a purge does not loop`() =
        familyTest {
            val id = anna.tags.create("Old", PaletteColor.BROWN)
            anna.sync()
            anna.tags.delete(id)
            anna.sync()
            server.deps.syncHousekeeping.purgeTombstones(server.clock.now() + 91.days)
            // A background cycle (e.g. the delayed trigger after Anna's local writes) may run the reset instead of the
            // explicit sync below. Wait for running cycles, then count every pull after the restore.
            all.forEach { it.sync() }
            val marks = all.associateWith { it.network.pullCursors.size }
            sql("UPDATE household_sync_state SET sync_epoch = gen_random_uuid()")

            for (client in all) {
                client.sync()
                val cursors = client.network.pullCursors.drop(marks.getValue(client))
                assertTrue(cursors.first() > 0 && 0L in cursors, "expected a reset and a full sync, got $cursors")
            }
            assertConverged(all)

            // A new device starts with a full sync right after the purge; it must not be sent into another reset.
            TestClient(server.url, "Anna's tablet").use { tablet ->
                tablet.login(ADMIN_LOGIN, ADMIN_PASSWORD)
                tablet.sync()
                tablet.sync()
                assertEquals(1, tablet.network.pullCursors.count { it == 0L }, "pulls: ${tablet.network.pullCursors}")
                assertEquals(anna.snapshot(), tablet.snapshot())
            }
        }

    @Test
    fun `a removed member is logged out and their local data is wiped`() =
        familyTest {
            carol.network.online = false
            val personId = (carol.session.state.value as SessionState.LoggedIn).me.personId
            carol.persons.update(personId, PersonData("Carol K."))
            carol.network.online = true
            anna.api.removeMember(carolMemberId)

            assertFalse(carol.engine.syncNow())
            carol.session.state.await { it !is SessionState.LoggedIn }
            assertTrue(carol.snapshot().isEmpty)
            assertEquals(0, carol.pendingCount())
            assertEquals(null, carol.localDb.read { local_sessionQueries.get().executeAsOneOrNull() })
            assertConverged(listOf(anna, boris))
        }

    @Test
    fun `permissions are evaluated from synced groups and work offline`() =
        familyTest {
            assertFalse(
                carol.permissionsRepository
                    .observeMine()
                    .first()
                    .has(Permission.TAGS_MANAGE),
            )
            assertFailsWith<LocalForbiddenException> { carol.tags.create("Toys", PaletteColor.PINK) }

            anna.api.updateGroup(kidsId, UpdateGroupRequest(permissions = PermissionCatalogue.kidsTemplate + Permission.TAGS_MANAGE))
            carol.sync()
            carol.permissionsRepository.observeMine().await { it.has(Permission.TAGS_MANAGE) }

            carol.network.online = false
            carol.tags.create("Toys", PaletteColor.PINK)
            assertTrue(carol.pendingCount() > 0)
            carol.network.online = true
            assertConverged(all)
            assertEquals(1, anna.tagsNamed("Toys").size)
        }

    @Test
    fun `8 - random offline work on three devices converges`() =
        familyTest {
            val random = Random(20261023)
            val colors = PaletteColor.entries
            var counter = 0
            var writes = 0
            repeat(6) { round ->
                for (client in all) {
                    client.network.online = random.nextBoolean()
                    repeat(random.nextInt(2, 6)) {
                        val tags = client.tagsRepository.observeAll(includeArchived = true).first()
                        val persons = client.personsRepository.observeAll().first()
                        try {
                            when (random.nextInt(9)) {
                                0, 1 -> {
                                    client.tags.create("Tag ${counter++ % 7}", colors.random(random))
                                }

                                2 -> {
                                    tags.randomOrNull(random)?.let { client.tags.rename(it.id, "Tag ${counter++ % 7}") }
                                }

                                3 -> {
                                    tags.randomOrNull(random)?.let { client.tags.recolor(it.id, colors.random(random)) }
                                }

                                4 -> {
                                    tags.randomOrNull(random)?.let { client.tags.archive(it.id, random.nextBoolean()) }
                                }

                                5 -> {
                                    tags.randomOrNull(random)?.let { client.tags.delete(it.id) }
                                }

                                6 -> {
                                    client.persons.create(PersonData("Relative ${counter++}"))
                                }

                                7 -> {
                                    val person = persons.randomOrNull(random)
                                    person?.let { client.persons.update(it.id, it.data.copy(notes = "round $round")) }
                                }

                                else -> {
                                    persons.randomOrNull(random)?.let { client.persons.delete(it.id) }
                                }
                            }
                            writes++
                        } catch (_: LocalWriteException) {
                            // Forbidden for this member, a locally taken name, or a member's own profile: skip.
                        }
                    }
                }
                for (client in all.shuffled(random)) client.engine.syncNow()
            }
            all.forEach { it.network.online = true }
            assertConverged(all)
            assertTrue(writes > 20, "only $writes local writes succeeded")
        }

    /**
     * Makes [client] write locally while its next incremental pull is on the way (so the entry is pending during the
     * reset), syncs, and checks that a full resync happened and the pending change survived it and reached the server.
     */
    private suspend fun Family.assertFullResyncKeepsOutbox(
        client: TestClient,
        onFullSync: () -> Unit = {},
    ) {
        val mark = client.network.pullCursors.size
        var written = false
        var fullSyncSeen = false
        client.network.beforePull = { after ->
            if (after > 0 && !written) {
                written = true
                client.tags.create("Pending", PaletteColor.ORANGE)
            }
            if (after == 0L && !fullSyncSeen) {
                fullSyncSeen = true
                onFullSync()
            }
        }
        client.sync()
        client.network.beforePull = null
        val cursors = client.network.pullCursors.drop(mark)
        assertTrue(written, "no incremental pull happened")
        assertTrue(cursors.first() > 0 && 0L in cursors, "expected a reset and a full sync, got $cursors")
        assertEquals(1, client.tagsNamed("Pending").size, "the pending tag is still shown after the full resync")
        client.sync()
        assertEquals(
            1,
            anna
                .serverSnapshot()
                .tags.values
                .count { it.name == "Pending" },
        )
    }

    private fun sql(statement: String): Long =
        TestDatabase.dataSource.connection.use { connection ->
            connection.createStatement().use { st ->
                if (st.execute(statement)) {
                    st.resultSet.use { rs -> if (rs.next()) rs.getLong(1) else 0 }
                } else {
                    st.updateCount.toLong()
                }
            }
        }
}
