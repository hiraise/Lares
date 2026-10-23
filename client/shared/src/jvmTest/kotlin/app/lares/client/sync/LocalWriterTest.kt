package app.lares.client.sync

import app.lares.client.api.LaresApi
import app.lares.client.api.createHttpClient
import app.lares.client.auth.InMemoryTokenStore
import app.lares.client.auth.LocalSession
import app.lares.client.data.PersonsWriter
import app.lares.client.data.TagsWriter
import app.lares.client.platform.InMemoryServerStore
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.auth.Me
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.PersonData
import app.lares.contract.entities.TagData
import app.lares.contract.permissions.Permission
import app.lares.contract.sync.EntityTypes
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock

class LocalWriterTest {
    private val localDb = testLocalDb()
    private val adapters = defaultEntityAdapters(Clock.System)
    private val api =
        LaresApi(
            createHttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }),
            InMemoryServerStore("http://family.test"),
            InMemoryTokenStore(),
            Clock.System,
        )

    // Never started: local writes only, no network.
    private val engine =
        SyncEngine(
            api,
            localDb,
            adapters,
            Materializer(adapters),
            Clock.System,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job()),
        )
    private val writer = LocalWriter(localDb, adapters, Clock.System, engine)
    private val tags = TagsWriter(writer)
    private val persons = PersonsWriter(writer, localDb)

    private suspend fun loginAs(vararg permissions: Permission) =
        localDb.transaction {
            LocalSession.save(
                this,
                Me(
                    "u",
                    "member-1",
                    "h",
                    "The Smiths",
                    "person-1",
                    "Anna",
                    "anna",
                    true,
                    isAdmin = false,
                    permissions = permissions.toSet(),
                ),
            )
        }

    private suspend fun outbox() = localDb.read { sync_outboxQueries.peek(100).executeAsList() }

    private suspend fun tag(id: String) = localDb.read { adapters[EntityTypes.TAG]!!.selectTyped(this, id) }

    @Test
    fun createAndPatchWriteTheTypedRowAndTheOutbox() =
        runTest {
            loginAs(Permission.TAGS_MANAGE)
            val id = tags.create("  Car ", PaletteColor.BLUE)
            tags.recolor(id, PaletteColor.RED)
            assertEquals(TagData("Car", PaletteColor.RED), tag(id))
            val entries = outbox()
            assertEquals(listOf("upsert", "upsert"), entries.map { it.op })
            assertEquals("""{"name":"Car","color":"blue","archived":false}""", entries[0].fields)
            assertEquals("""{"color":"red"}""", entries[1].fields)
        }

    @Test
    fun unchangedValuesAreNotQueued() =
        runTest {
            loginAs(Permission.TAGS_MANAGE)
            val id = tags.create("Car", PaletteColor.BLUE)
            tags.rename(id, "Car ")
            assertEquals(1, outbox().size)
        }

    @Test
    fun forbiddenWritesStoreNothing() =
        runTest {
            loginAs(Permission.TASKS_VIEW)
            assertFailsWith<LocalForbiddenException> { tags.create("Car", PaletteColor.BLUE) }
            assertEquals(emptyList(), outbox())
        }

    @Test
    fun invalidWritesStoreNothing() =
        runTest {
            loginAs(Permission.TAGS_MANAGE)
            val e = assertFailsWith<LocalValidationException> { tags.create("   ", PaletteColor.BLUE) }
            assertEquals(mapOf("name" to FieldErrorCode.REQUIRED), e.fields)
            val missing =
                assertFailsWith<LocalValidationException> {
                    writer.upsert(EntityTypes.TAG, "01928c4e-0000-7000-8000-000000000009", JsonObject(mapOf("name" to JsonPrimitive("X"))))
                }
            assertEquals(mapOf("color" to FieldErrorCode.REQUIRED), missing.fields)
            assertEquals(emptyList(), outbox())
        }

    @Test
    fun tagNamesAreUniqueIgnoringCase() =
        runTest {
            loginAs(Permission.TAGS_MANAGE)
            tags.create("Дача", PaletteColor.GREEN)
            val e = assertFailsWith<LocalValidationException> { tags.create("ДАЧА", PaletteColor.BLUE) }
            assertEquals(mapOf("name" to FieldErrorCode.TAKEN), e.fields)
        }

    @Test
    fun readOnlyTypesCannotBeWritten() =
        runTest {
            loginAs(Permission.HOUSEHOLD_MANAGE)
            assertFailsWith<IllegalArgumentException> { writer.delete(EntityTypes.GROUP, "g") }
        }

    @Test
    fun deletedEntitiesCannotBeEdited() =
        runTest {
            loginAs(Permission.TAGS_MANAGE)
            val id = tags.create("Car", PaletteColor.BLUE)
            tags.delete(id)
            assertEquals(ErrorCode.ENTITY_DELETED, assertFailsWith<LocalWriteException> { tags.rename(id, "Auto") }.code)
        }

    @Test
    fun membersEditTheirOwnProfileButCannotDeleteAMembersPerson() =
        runTest {
            loginAs()
            localDb.transaction {
                sync_shadowQueries.upsert(EntityTypes.PERSON, "person-1", 1, 0, """{"name":"Anna","contacts":[]}""")
                Materializer(adapters).rematerialize(this, EntityTypes.PERSON, "person-1")
                memberQueries.upsert("member-1", "person-1", "u", "anna", 1)
            }
            persons.update("person-1", PersonData(name = "Anna K.", birthYear = 1990))
            assertEquals("""{"name":"Anna K.","birthYear":1990}""", outbox().single().fields)
            assertFailsWith<LocalForbiddenException> { persons.delete("person-1") }

            loginAs(Permission.PERSONS_MANAGE)
            assertEquals(ErrorCode.PERSON_IS_MEMBER, assertFailsWith<LocalWriteException> { persons.delete("person-1") }.code)
        }

    @Test
    fun newPersonsGetTheLeastUsedColor() =
        runTest {
            loginAs(Permission.PERSONS_MANAGE)
            persons.create(PersonData(name = "A", color = PaletteColor.RED))
            val id = persons.create(PersonData(name = "B"))
            assertEquals(
                PaletteColor.ORANGE,
                localDb.read { (adapters[EntityTypes.PERSON]!!.selectTyped(this, id) as PersonData).color },
            )
        }
}
