package app.familyhub.server.sync

import app.familyhub.contract.Ids
import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.FieldErrorCode
import app.familyhub.contract.entities.PaletteColor
import app.familyhub.contract.entities.TagData
import app.familyhub.contract.sync.EntityTypes
import app.familyhub.contract.sync.MutationStatus
import app.familyhub.contract.sync.PushResponse
import app.familyhub.server.testing.ADULTS
import app.familyhub.server.testing.assertApplied
import app.familyhub.server.testing.assertError
import app.familyhub.server.testing.assertRejected
import app.familyhub.server.testing.dataAs
import app.familyhub.server.testing.delete
import app.familyhub.server.testing.fields
import app.familyhub.server.testing.groupId
import app.familyhub.server.testing.householdUuid
import app.familyhub.server.testing.jsonClient
import app.familyhub.server.testing.lastSeq
import app.familyhub.server.testing.memberSession
import app.familyhub.server.testing.newTag
import app.familyhub.server.testing.pull
import app.familyhub.server.testing.pullAll
import app.familyhub.server.testing.push
import app.familyhub.server.testing.pushOne
import app.familyhub.server.testing.pushRaw
import app.familyhub.server.testing.setup
import app.familyhub.server.testing.testApp
import app.familyhub.server.testing.upsert
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** Push processing (architecture/sync.md § Push) and invariants 1, 2, 4, 5. */
class SyncPushTest {
    @Test
    fun tagCreateIsAppliedAndPulledAndRetriesAreIdempotent() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val create = deps.newTag("Car")

            val seq = assertNotNull(client.pushOne(token, create).assertApplied())
            assertEquals(deps.lastSeq(admin.householdUuid), seq)
            val tag = client.pull(token).changes.single { it.type == EntityTypes.TAG }
            assertEquals(create.id, tag.id)
            assertEquals(seq, tag.seq)
            assertEquals(TagData("Car", PaletteColor.BLUE), tag.dataAs<TagData>())

            // Invariant 4: the same mutation again changes nothing and returns the original result.
            val retry = client.pushOne(token, create)
            assertEquals(MutationStatus.APPLIED, retry.status)
            assertEquals(seq, retry.seq)
            assertTrue(retry.duplicate)
            assertEquals(seq, deps.lastSeq(admin.householdUuid))

            // A rejected mutation is replayed as rejected with the same code.
            val clash = deps.newTag("car")
            client.pushOne(token, clash).assertRejected(ErrorCode.TAG_NAME_TAKEN)
            val clashRetry = client.pushOne(token, clash)
            clashRetry.assertRejected(ErrorCode.TAG_NAME_TAKEN)
            assertTrue(clashRetry.duplicate)
        }

    @Test
    fun concurrentPushesOfTheSameBatchApplyOnce() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val batch = (1..10).map { deps.newTag("Tag $it") }
            val responses =
                coroutineScope {
                    (1..2).map { async { client.pushRaw(admin.accessToken, batch) } }.awaitAll()
                }
            responses.forEach { assertEquals(HttpStatusCode.OK, it.status) }
            val results = responses.map { it.body<PushResponse>().results }
            for (i in batch.indices) {
                val pair = results.map { it[i] }
                pair.forEach { it.assertApplied() }
                assertEquals(1, pair.count { !it.duplicate }, "exactly one original for mutation $i: $pair")
                assertEquals(pair[0].seq, pair[1].seq)
            }
            assertEquals(10, client.pull(admin.accessToken).changes.count { it.type == EntityTypes.TAG })
        }

    @Test
    fun fieldLevelLastWriterWins() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val adult = client.memberSession(admin.accessToken, "bob", setOf(deps.groupId(admin.householdUuid, ADULTS)))
            val create = deps.newTag("Car", color = "blue")
            client.pushOne(admin.accessToken, create).assertApplied()

            // Invariant 1: different fields of one entity both survive.
            client.pushOne(admin.accessToken, deps.upsert(EntityTypes.TAG, create.id, fields("color" to "red"))).assertApplied()
            client.pushOne(adult.accessToken, deps.upsert(EntityTypes.TAG, create.id, fields("name" to "Auto"))).assertApplied()
            assertEquals(TagData("Auto", PaletteColor.RED), tagData(client, admin.accessToken, create.id))

            // Invariant 2: the same field — the later arrival wins.
            client.pushOne(adult.accessToken, deps.upsert(EntityTypes.TAG, create.id, fields("name" to "Vehicle"))).assertApplied()
            client.pushOne(admin.accessToken, deps.upsert(EntityTypes.TAG, create.id, fields("name" to "Family car"))).assertApplied()
            assertEquals(TagData("Family car", PaletteColor.RED), tagData(client, adult.accessToken, create.id))

            // An unchanged value is a no-op: applied without a new seq.
            assertNull(client.pushOne(admin.accessToken, deps.upsert(EntityTypes.TAG, create.id, fields("color" to "red"))).assertApplied())
        }

    @Test
    fun deleteWinsOverLaterEdits() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val create = deps.newTag("Car")
            client.pushOne(token, create).assertApplied()
            val first = client.pull(token)

            val deleteSeq = assertNotNull(client.pushOne(token, deps.delete(EntityTypes.TAG, create.id)).assertApplied())
            client
                .pushOne(
                    token,
                    deps.upsert(EntityTypes.TAG, create.id, fields("name" to "Again")),
                ).assertRejected(ErrorCode.ENTITY_DELETED)
            // Deleting again, or deleting something unknown, is an applied no-op.
            assertNull(client.pushOne(token, deps.delete(EntityTypes.TAG, create.id)).assertApplied())
            assertNull(client.pushOne(token, deps.delete(EntityTypes.TAG, Ids.newId())).assertApplied())

            val tombstone = client.pull(token, first.cursor, accessVersion = first.accessVersion, epoch = first.epoch).changes.single()
            assertEquals(create.id, tombstone.id)
            assertTrue(tombstone.deleted)
            assertNull(tombstone.data)
            assertEquals(deleteSeq, tombstone.seq)
            // A full sync skips tombstones.
            assertTrue(client.pull(token).changes.none { it.type == EntityTypes.TAG })
        }

    @Test
    fun tagNamesAreUniqueCaseInsensitiveAmongNonDeletedTags() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val car = deps.newTag("Car")
            client.pushOne(token, car).assertApplied()
            val taken = client.pushOne(token, deps.newTag("  cAR "))
            taken.assertRejected(ErrorCode.TAG_NAME_TAKEN)
            assertEquals(mapOf("name" to FieldErrorCode.TAKEN), taken.error?.fields)

            // Archived tags still hold the name.
            client.pushOne(token, deps.upsert(EntityTypes.TAG, car.id, fields("archived" to true))).assertApplied()
            client.pushOne(token, deps.newTag("CAR")).assertRejected(ErrorCode.TAG_NAME_TAKEN)

            // Renaming to the same name in another case is fine for the tag itself.
            client.pushOne(token, deps.upsert(EntityTypes.TAG, car.id, fields("name" to "CAR"))).assertApplied()

            // Case folding works for Cyrillic too (needs a UTF-8 database locale, as in postgres:18-alpine).
            client.pushOne(token, deps.newTag("Дача")).assertApplied()
            client.pushOne(token, deps.newTag("ДАЧА")).assertRejected(ErrorCode.TAG_NAME_TAKEN)

            // Deleted tags free the name.
            client.pushOne(token, deps.delete(EntityTypes.TAG, car.id)).assertApplied()
            client.pushOne(token, deps.newTag("car")).assertApplied()
        }

    @Test
    fun invalidMutationsAreRejectedWithValidationFailed() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken

            // A new entity needs all required fields.
            val missing = client.pushOne(token, deps.upsert(EntityTypes.TAG, Ids.newId(), fields("name" to "Car")))
            missing.assertRejected(ErrorCode.VALIDATION_FAILED)
            assertEquals(mapOf("color" to FieldErrorCode.REQUIRED), missing.error?.fields)

            val unknownField =
                client.pushOne(
                    token,
                    deps.upsert(
                        EntityTypes.TAG,
                        Ids.newId(),
                        fields(
                            "name" to "A",
                            "color" to "red",
                            "size" to 3,
                        ),
                    ),
                )
            assertEquals(mapOf("size" to FieldErrorCode.INVALID_FORMAT), unknownField.error?.fields)

            // Unknown enum values would be mapped to a fallback by the lenient decoder; the server refuses them.
            val unknownColor =
                client.pushOne(
                    token,
                    deps.upsert(EntityTypes.TAG, Ids.newId(), fields("name" to "A", "color" to "turquoise")),
                )
            assertEquals(mapOf("color" to FieldErrorCode.INVALID_FORMAT), unknownColor.error?.fields)

            val wrongType =
                client.pushOne(
                    token,
                    deps.upsert(
                        EntityTypes.TAG,
                        Ids.newId(),
                        fields(
                            "name" to "A",
                            "color" to "red",
                            "archived" to "yes",
                        ),
                    ),
                )
            wrongType.assertRejected(ErrorCode.VALIDATION_FAILED)

            val blank = client.pushOne(token, deps.upsert(EntityTypes.TAG, Ids.newId(), fields("name" to "  ", "color" to "red")))
            assertEquals(mapOf("name" to FieldErrorCode.REQUIRED), blank.error?.fields)

            val badId = client.pushOne(token, deps.upsert(EntityTypes.TAG, "not-a-uuid", fields("name" to "A", "color" to "red")))
            badId.assertRejected(ErrorCode.VALIDATION_FAILED)

            client.pushOne(token, deps.upsert("task", Ids.newId(), fields("title" to "x"))).assertRejected(ErrorCode.VALIDATION_FAILED)

            // Required field set to null on an existing tag.
            val tag = deps.newTag("Car")
            client.pushOne(token, tag).assertApplied()
            client.pushOne(token, deps.upsert(EntityTypes.TAG, tag.id, fields("name" to null))).assertRejected(ErrorCode.VALIDATION_FAILED)
            // Optional field cleared with null.
            client.pushOne(token, deps.upsert(EntityTypes.TAG, tag.id, fields("icon" to "car"))).assertApplied()
            client.pushOne(token, deps.upsert(EntityTypes.TAG, tag.id, fields("icon" to null))).assertApplied()
            assertNull(tagData(client, token, tag.id).icon)

            // Too many mutations in one request.
            val tooMany = client.pushRaw(token, (1..101).map { deps.newTag("T$it") })
            assertEquals(HttpStatusCode.BadRequest, tooMany.status)
            tooMany.assertError(ErrorCode.VALIDATION_FAILED)
        }

    @Test
    fun mutationsOlderThan30DaysExpire() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val now = deps.clock.now()
            val old = deps.newTag("Old").copy(createdAt = now - 30.days - 1.hours)
            client.pushOne(admin.accessToken, old).assertRejected(ErrorCode.MUTATION_EXPIRED)
            val recent = deps.newTag("Recent").copy(createdAt = now - 29.days)
            client.pushOne(admin.accessToken, recent).assertApplied()
        }

    @Test
    fun mutationLogIsCleanedAfter30Days() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            client.pushOne(admin.accessToken, deps.newTag("Car")).assertApplied()
            assertEquals(0, deps.syncHousekeeping.cleanupMutationLog(deps.clock.now()))
            assertEquals(1, deps.syncHousekeeping.cleanupMutationLog(deps.clock.now() + 31.days))
        }

    @Test
    fun readOnlyTypesCannotBePushed() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val (_, changes) = client.pullAll(admin.accessToken)
            for (type in listOf(EntityTypes.HOUSEHOLD, EntityTypes.MEMBER, EntityTypes.GROUP)) {
                val existing = changes.first { it.type == type }
                client.pushOne(admin.accessToken, deps.upsert(type, existing.id, fields("name" to "x"))).assertRejected(ErrorCode.FORBIDDEN)
                client.pushOne(admin.accessToken, deps.delete(type, existing.id)).assertRejected(ErrorCode.FORBIDDEN)
            }
            assertFalse(client.pull(admin.accessToken).changes.any { it.deleted })
        }

    private suspend fun tagData(
        client: io.ktor.client.HttpClient,
        token: String,
        id: String,
    ): TagData =
        client
            .pull(token)
            .changes
            .single { it.id == id }
            .dataAs()
}
