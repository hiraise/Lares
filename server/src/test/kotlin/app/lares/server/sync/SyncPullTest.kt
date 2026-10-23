package app.lares.server.sync

import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.admin.CreateInviteRequest
import app.lares.contract.api.admin.CreateMemberRequest
import app.lares.contract.api.admin.ResetPasswordRequest
import app.lares.contract.api.admin.SetGroupsRequest
import app.lares.contract.api.admin.UpdateHouseholdRequest
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.entities.GroupData
import app.lares.contract.entities.HouseholdData
import app.lares.contract.entities.MemberData
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.PersonData
import app.lares.contract.permissions.BuiltInGroup
import app.lares.contract.sync.EntityTypes
import app.lares.server.AppDeps
import app.lares.server.household.MemberRepository
import app.lares.server.platform.db.HouseholdSyncStateTable
import app.lares.server.testing.ADMIN_LOGIN
import app.lares.server.testing.ADMIN_PASSWORD
import app.lares.server.testing.KIDS
import app.lares.server.testing.TestDatabase
import app.lares.server.testing.assertApplied
import app.lares.server.testing.assertError
import app.lares.server.testing.createMember
import app.lares.server.testing.dataAs
import app.lares.server.testing.delete
import app.lares.server.testing.fields
import app.lares.server.testing.groupId
import app.lares.server.testing.householdUuid
import app.lares.server.testing.jsonClient
import app.lares.server.testing.lastSeq
import app.lares.server.testing.login
import app.lares.server.testing.memberSession
import app.lares.server.testing.newTag
import app.lares.server.testing.pull
import app.lares.server.testing.pullAll
import app.lares.server.testing.pullRaw
import app.lares.server.testing.pushOne
import app.lares.server.testing.send
import app.lares.server.testing.setup
import app.lares.server.testing.testApp
import app.lares.server.testing.testClock
import app.lares.server.testing.upsert
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/** Pull (architecture/sync.md § Pull, § Reset) and invariants 6, 9, 10. */
class SyncPullTest {
    @Test
    fun fullPullAfterSetupReturnsTheHouseholdInSeqOrder() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val response = client.pull(admin.accessToken)
            assertFalse(response.reset)
            assertFalse(response.hasMore)
            assertEquals(deps.lastSeq(admin.householdUuid), response.cursor)
            val seqs = response.changes.map { it.seq }
            assertEquals(seqs.sorted(), seqs)
            assertEquals(seqs.size, seqs.toSet().size)

            val byType = response.changes.groupBy { it.type }
            assertEquals(
                "The Smiths",
                byType
                    .getValue(EntityTypes.HOUSEHOLD)
                    .single()
                    .dataAs<HouseholdData>()
                    .name,
            )
            assertEquals(3, byType.getValue(EntityTypes.GROUP).size)
            val admins =
                byType.getValue(EntityTypes.GROUP).map { it.dataAs<GroupData>() }.single {
                    it.builtIn ==
                        BuiltInGroup.ADMINISTRATORS
                }
            assertEquals(setOf(admin.me.memberId), admins.memberIds)
            val member = byType.getValue(EntityTypes.MEMBER).single()
            assertEquals(MemberData(admin.me.personId, admin.me.userId, ADMIN_LOGIN, hasPassword = true), member.dataAs<MemberData>())
            // FND-034: the first person gets the first palette color.
            val person = byType.getValue(EntityTypes.PERSON).single().dataAs<PersonData>()
            assertEquals(PersonData("Anna", color = PaletteColor.RED), person)
        }

    @Test
    fun pagingWithSmallLimits() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            repeat(3) { client.pushOne(admin.accessToken, deps.newTag("Tag $it")).assertApplied() }
            val all = client.pull(admin.accessToken).changes

            val first = client.pull(admin.accessToken, limit = 2)
            assertTrue(first.hasMore)
            assertEquals(all.take(2), first.changes)
            assertEquals(first.changes.last().seq, first.cursor)

            val (last, paged) = client.pullAll(admin.accessToken, limit = 2)
            assertEquals(all, paged)
            assertFalse(last.hasMore)
            assertEquals(deps.lastSeq(admin.householdUuid), last.cursor)

            // Up to date: nothing new, cursor stays.
            val again = client.pull(admin.accessToken, last.cursor, 2, last.accessVersion, last.epoch)
            assertTrue(again.changes.isEmpty())
            assertEquals(last.cursor, again.cursor)
        }

    @Test
    fun invalidParametersAreRejected() =
        testApp { _ ->
            val client = jsonClient()
            val admin = client.setup()
            for (response in listOf(
                client.pullRaw(admin.accessToken, after = -1),
                client.pullRaw(admin.accessToken, limit = 0),
                client.pullRaw(admin.accessToken, limit = 1001),
                client.send(HttpMethod.Get, "/api/v1/sync/changes?after=abc", admin.accessToken),
            )) {
                assertEquals(HttpStatusCode.BadRequest, response.status)
                response.assertError(ErrorCode.VALIDATION_FAILED)
            }
            assertEquals(HttpStatusCode.Unauthorized, client.pullRaw("nope").status)
        }

    @Test
    fun adminRestWritesAppearInPull() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val (start, _) = client.pullAll(admin.accessToken)

            client.send(HttpMethod.Patch, "/api/v1/household", admin.accessToken, UpdateHouseholdRequest(name = "Smith family"))
            val created = client.createMember(admin.accessToken, CreateMemberRequest("Kid"))
            val changes = client.pull(admin.accessToken, start.cursor, accessVersion = start.accessVersion, epoch = start.epoch).changes
            assertEquals("Smith family", changes.single { it.type == EntityTypes.HOUSEHOLD }.dataAs<HouseholdData>().name)
            assertEquals(created.memberId, changes.single { it.type == EntityTypes.MEMBER }.id)
            // FND-034: the second person gets the next color.
            assertEquals(PaletteColor.ORANGE, changes.single { it.type == EntityTypes.PERSON }.dataAs<PersonData>().color)

            // A password reset changes the member's `hasPassword`, so the member row moves too.
            val (afterCreate, _) = client.pullAll(admin.accessToken, start)
            assertFalse(changes.single { it.type == EntityTypes.MEMBER }.dataAs<MemberData>().hasPassword)
            val reset =
                client.send(
                    HttpMethod.Post,
                    "/api/v1/members/${created.memberId}/password",
                    admin.accessToken,
                    ResetPasswordRequest("new password"),
                )
            assertEquals(HttpStatusCode.NoContent, reset.status)
            val member =
                client
                    .pull(
                        admin.accessToken,
                        afterCreate.cursor,
                        accessVersion = afterCreate.accessVersion,
                        epoch = afterCreate.epoch,
                    ).changes
                    .single()
            assertEquals(created.memberId, member.id)
            assertTrue(member.dataAs<MemberData>().hasPassword)
        }

    @Test
    fun resetRules() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val householdId = admin.householdUuid
            val (synced, _) = client.pullAll(token)
            val cursor = synced.cursor

            fun assertReset(response: app.lares.contract.sync.PullResponse) {
                assertTrue(response.reset, "expected reset: $response")
                assertTrue(response.changes.isEmpty())
                assertEquals(0, response.cursor)
                assertFalse(response.hasMore)
            }

            // In sync: no reset.
            assertFalse(client.pull(token, cursor, accessVersion = synced.accessVersion, epoch = synced.epoch).reset)
            // (b) access version changed.
            assertReset(client.pull(token, cursor, accessVersion = synced.accessVersion + 1, epoch = synced.epoch))
            // (c) cursor ahead of the server (restored from an older backup).
            assertReset(client.pull(token, cursor + 5, accessVersion = synced.accessVersion, epoch = synced.epoch))
            // (d) epoch changed (restore procedure).
            assertReset(client.pull(token, cursor, accessVersion = synced.accessVersion, epoch = UUID.randomUUID().toString()))
            // Missing accessVersion or epoch on an incremental pull.
            assertReset(client.pull(token, cursor, epoch = synced.epoch))
            assertReset(client.pull(token, cursor, accessVersion = synced.accessVersion))
            // after = 0 never resets.
            assertFalse(client.pull(token, 0, accessVersion = 999, epoch = "other").reset)

            // A group change bumps the member's access version: the next incremental pull resets.
            val kids = deps.groupId(householdId, KIDS)
            val adminsId =
                client
                    .pull(token)
                    .changes
                    .single { it.type == EntityTypes.GROUP && it.dataAs<GroupData>().builtIn != null }
                    .id
            client.send(
                HttpMethod.Put,
                "/api/v1/members/${admin.me.memberId}/groups",
                token,
                SetGroupsRequest(setOf(adminsId, kids.toString())),
            )
            val afterAccessChange = client.pull(token, cursor, accessVersion = synced.accessVersion, epoch = synced.epoch)
            assertReset(afterAccessChange)
            assertEquals(synced.accessVersion + 1, afterAccessChange.accessVersion)

            // Restore: a new epoch makes every client reset even if their cursor is valid.
            val (current, _) = client.pullAll(token)
            val newEpoch = UUID.randomUUID()
            deps.db.dbQuery {
                HouseholdSyncStateTable.update({ HouseholdSyncStateTable.householdId eq householdId }) { it[syncEpoch] = newEpoch }
            }
            val restored = client.pull(token, current.cursor, accessVersion = current.accessVersion, epoch = current.epoch)
            assertReset(restored)
            assertEquals(newEpoch.toString(), restored.epoch)
        }

    @Test
    fun purgedTombstonesForceOneResetButNoLoop() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            var token = admin.accessToken
            val tag = deps.newTag("Old")
            client.pushOne(token, tag).assertApplied()
            val (beforeDelete, _) = client.pullAll(token)
            val deleteSeq = assertNotNull(client.pushOne(token, deps.delete(EntityTypes.TAG, tag.id)).assertApplied())
            client.pushOne(token, deps.newTag("Fresh")).assertApplied()

            // Not old enough yet.
            assertEquals(0, deps.syncHousekeeping.purgeTombstones(deps.clock.now()))
            deps.testClock.advance(91.days)
            token = client.login(ADMIN_LOGIN, ADMIN_PASSWORD).body<AuthResult>().accessToken
            assertEquals(1, deps.syncHousekeeping.purgeTombstones(deps.clock.now()))
            assertEquals(deleteSeq, purgeHorizon(deps, admin.householdUuid))

            // (a) The client's cursor is older than the purged tombstone: reset.
            val stale = client.pull(token, beforeDelete.cursor, accessVersion = beforeDelete.accessVersion, epoch = beforeDelete.epoch)
            assertTrue(stale.reset)
            // The full resync after it, then an incremental pull: no reset again (invariant 10).
            val (full, changes) = client.pullAll(token)
            assertTrue(changes.none { it.id == tag.id })
            val next = client.pull(token, full.cursor, accessVersion = full.accessVersion, epoch = full.epoch)
            assertFalse(next.reset)
            // Purging again changes nothing.
            assertEquals(0, deps.syncHousekeeping.purgeTombstones(deps.clock.now()))
        }

    @Test
    fun purgeKeepsMemberTombstonesAndReferencedPersons() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            var token = admin.accessToken
            // A removed member whose sessions still reference the member row.
            val leaving = client.memberSession(token, "leaving", emptySet())
            assertEquals(HttpStatusCode.NoContent, client.send(HttpMethod.Delete, "/api/v1/members/${leaving.me.memberId}", token).status)
            // A deleted relative that an invite still points to.
            val relative = deps.upsert(EntityTypes.PERSON, Ids.newId(), fields("name" to "Uncle"))
            client.pushOne(token, relative).assertApplied()
            val invite = client.send(HttpMethod.Post, "/api/v1/invites", token, CreateInviteRequest(emptySet(), personId = relative.id))
            assertEquals(HttpStatusCode.Created, invite.status)
            client.pushOne(token, deps.delete(EntityTypes.PERSON, relative.id)).assertApplied()
            // A deleted tag that nothing references.
            val tag = deps.newTag("Old")
            client.pushOne(token, tag).assertApplied()
            client.pushOne(token, deps.delete(EntityTypes.TAG, tag.id)).assertApplied()

            deps.testClock.advance(100.days)
            token = client.login(ADMIN_LOGIN, ADMIN_PASSWORD).body<AuthResult>().accessToken
            assertEquals(1, deps.syncHousekeeping.purgeTombstones(deps.clock.now()), "only the tag is purged")
            val member = deps.db.dbQuery { MemberRepository.get(admin.householdUuid, UUID.fromString(leaving.me.memberId)) }
            assertTrue(assertNotNull(member).deleted)
            val (_, changes) = client.pullAll(token)
            assertTrue(changes.any { it.id == leaving.me.personId }, "the removed member's person stays")
        }

    @Test
    fun pullNeverSkipsAChangeCommittedDuringIt() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val (synced, _) = client.pullAll(token)
            client.pushOne(token, deps.newTag("Car")).assertApplied()
            val personId = UUID.fromString(admin.me.personId)

            // Between the tag and the person queries another transaction commits a person change.
            var committedSeq = -1L
            deps.syncPullService.afterHandlerQuery = { type ->
                if (type == EntityTypes.TAG && committedSeq < 0) committedSeq = commitPersonRename(admin.householdUuid, personId, "Anya")
            }
            val page = client.pull(token, synced.cursor, accessVersion = synced.accessVersion, epoch = synced.epoch)
            deps.syncPullService.afterHandlerQuery = null
            assertTrue(committedSeq > 0)
            // The snapshot did not see it, so the cursor stays below its seq …
            assertTrue(page.changes.none { it.type == EntityTypes.PERSON })
            assertTrue(page.cursor < committedSeq, "cursor ${page.cursor} must stay below $committedSeq")
            // … and the next pull returns it.
            val next = client.pull(token, page.cursor, accessVersion = page.accessVersion, epoch = page.epoch)
            assertEquals(
                "Anya",
                next.changes
                    .single { it.type == EntityTypes.PERSON }
                    .dataAs<PersonData>()
                    .name,
            )
        }

    /** Commits a person rename through plain JDBC in its own transaction, the way any writer allocates a seq. */
    private fun commitPersonRename(
        householdId: UUID,
        personId: UUID,
        name: String,
    ): Long =
        TestDatabase.dataSource.connection.use { connection ->
            connection.autoCommit = false
            val seq =
                connection
                    .prepareStatement(
                        "UPDATE household_sync_state SET last_seq = last_seq + 1 WHERE household_id = ? RETURNING last_seq",
                    ).use {
                        it.setObject(1, householdId)
                        it.executeQuery().use { rs ->
                            rs.next()
                            rs.getLong(1)
                        }
                    }
            connection.prepareStatement("UPDATE person SET name = ?, seq = ? WHERE id = ?").use {
                it.setString(1, name)
                it.setLong(2, seq)
                it.setObject(3, personId)
                it.executeUpdate()
            }
            connection.commit()
            seq
        }

    private suspend fun purgeHorizon(
        deps: AppDeps,
        householdId: UUID,
    ): Long =
        deps.db.dbQuery {
            HouseholdSyncStateTable
                .selectAll()
                .where { HouseholdSyncStateTable.householdId eq householdId }
                .single()[HouseholdSyncStateTable.purgeHorizonSeq]
        }
}
