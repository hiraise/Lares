package app.lares.integration

import app.lares.client.sync.SyncState
import app.lares.contract.api.ErrorCode
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.TagData
import app.lares.contract.sync.EntityTypes
import app.lares.server.testing.ADMIN_PASSWORD
import app.lares.server.testing.createSecondHousehold
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Invariants 1–8 of architecture/sync.md, end to end with several client cores against the real server. */
class SyncInvariantsTest {
    /** A tag created by Anna and known to everybody. */
    private suspend fun Family.sharedTag(name: String): String {
        val id = anna.tags.create(name, PaletteColor.BLUE)
        anna.sync()
        all.forEach { it.sync() }
        return id
    }

    @Test
    fun `1 - offline edits of different fields both survive`() =
        familyTest {
            val id = sharedTag("Car")
            anna.network.online = false
            boris.network.online = false
            anna.tags.rename(id, "Family car")
            boris.tags.recolor(id, PaletteColor.RED)

            anna.network.online = true
            anna.sync()
            boris.network.online = true
            boris.sync()

            assertConverged(all)
            assertEquals(TagData("Family car", PaletteColor.RED), anna.snapshot().tags[id])
        }

    @Test
    fun `2 - the later arrival wins on the same field`() =
        familyTest {
            val id = sharedTag("Car")
            anna.network.online = false
            boris.network.online = false
            anna.tags.rename(id, "Anna's name")
            boris.tags.rename(id, "Boris's name")

            anna.network.online = true
            anna.sync()
            boris.network.online = true
            boris.sync()

            assertConverged(all)
            assertEquals(
                "Boris's name",
                carol
                    .snapshot()
                    .tags
                    .getValue(id)
                    .name,
            )
        }

    @Test
    fun `3 - a rejected mutation reverts to server state and is shown`() =
        familyTest {
            boris.network.online = false
            anna.tags.create("Car", PaletteColor.BLUE)
            anna.sync()
            // Boris has not seen Anna's tag, so his local check cannot catch the duplicate name (TAG-006).
            val borisTag = boris.tags.create("car", PaletteColor.GREEN)
            assertEquals(1, boris.tagsNamed("car").size)

            boris.network.online = true
            boris.sync()

            assertEquals(emptyList(), boris.tagsNamed("car"))
            assertEquals(1, boris.tagsNamed("Car").size)
            val rejection =
                boris.engine.rejections
                    .first()
                    .single()
            assertEquals(ErrorCode.TAG_NAME_TAKEN, rejection.code)
            assertEquals(borisTag, rejection.entityId)
            assertEquals(EntityTypes.TAG, rejection.type)
            boris.engine.markRejectionsSeen(listOf(rejection.id))
            assertEquals(emptyList(), boris.engine.rejections.first())
            assertConverged(all)
        }

    @Test
    fun `4 - a retried batch creates no duplicates`() =
        familyTest {
            boris.network.dropPushResponses = true
            boris.tags.create("Garden", PaletteColor.GREEN)
            assertFalse(boris.engine.syncNow())
            assertEquals(1, boris.pendingCount(), "the outbox is kept when the response is lost")
            // Background retries may be running; each one ends offline while responses are lost.
            boris.engine.status.await { it.state == SyncState.OFFLINE }

            boris.network.dropPushResponses = false
            boris.sync()

            assertEquals(0, boris.pendingCount())
            assertEquals(
                1,
                anna
                    .serverSnapshot()
                    .tags.values
                    .count { it.name == "Garden" },
            )
            assertConverged(all)
        }

    @Test
    fun `5 - delete wins over a concurrent edit in both orders`() =
        familyTest {
            // Edit arrives first, then the delete.
            val first = sharedTag("Car")
            anna.network.online = false
            boris.network.online = false
            anna.tags.delete(first)
            boris.tags.rename(first, "Auto")
            boris.network.online = true
            boris.sync()
            anna.network.online = true
            anna.sync()

            // Delete arrives first, then the edit (rejected with ENTITY_DELETED).
            val second = sharedTag("Bike")
            anna.network.online = false
            boris.network.online = false
            anna.tags.delete(second)
            boris.tags.rename(second, "Bicycle")
            anna.network.online = true
            anna.sync()
            boris.network.online = true
            boris.sync()
            assertEquals(
                ErrorCode.ENTITY_DELETED,
                boris.engine.rejections
                    .first()
                    .single()
                    .code,
            )

            assertConverged(all)
            for (client in all) {
                val tags = client.snapshot().tags
                assertNull(tags[first])
                assertNull(tags[second])
            }
        }

    @Test
    fun `7 - a mutation with another household's id is rejected without leaking data`() =
        familyTest {
            val id = sharedTag("Car")
            server.deps.createSecondHousehold("admin-b")
            TestClient(server.url, "Stranger's phone").use { stranger ->
                stranger.login("admin-b", ADMIN_PASSWORD)
                stranger.sync()
                // Locally this is a new tag (the stranger cannot know the id exists); the server must refuse it.
                stranger.writer.upsert(
                    EntityTypes.TAG,
                    id,
                    JsonObject(
                        mapOf(
                            "name" to JsonPrimitive("Mine"),
                            "color" to JsonPrimitive("red"),
                        ),
                    ),
                )
                assertEquals("Mine", stranger.snapshot().tags[id]?.name)
                stranger.sync()

                assertEquals(
                    ErrorCode.ENTITY_NOT_FOUND,
                    stranger.engine.rejections
                        .first()
                        .single()
                        .code,
                )
                val seen = stranger.snapshot()
                assertNull(seen.tags[id])
                assertEquals(listOf("The Joneses"), seen.households.values.map { it.name })
                val family = anna.serverSnapshot()
                assertTrue(seen.persons.keys.none { it in family.persons }, "persons of another household leaked")
                assertTrue(seen.members.keys.none { it in family.members }, "members of another household leaked")
                assertEquals(TagData("Car", PaletteColor.BLUE), family.tags[id])
            }
            assertConverged(all)
        }
}
