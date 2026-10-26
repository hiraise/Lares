package app.lares.client.data.location

import app.lares.client.sync.LocalForbiddenException
import app.lares.client.sync.LocalValidationException
import app.lares.contract.Ids
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.entities.PlaceAlertData
import app.lares.contract.entities.PlaceData
import app.lares.contract.sync.EntityTypes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PlaceAdaptersTest {
    private val f = LocationFixture()

    @AfterTest
    fun close() = f.close()

    private fun json(data: PlaceData) = LaresJson.encodeToJsonElement(PlaceData.serializer(), data).jsonObject

    private fun json(data: PlaceAlertData) = LaresJson.encodeToJsonElement(PlaceAlertData.serializer(), data).jsonObject

    @Test
    fun `LOC-020 typed rows round trip and unknown icons fall back`() =
        runTest {
            f.family()
            f.putPlaces()
            val adapter = f.adapters[EntityTypes.PLACE] as PlaceAdapter
            assertEquals(SCHOOL, f.localDb.read { adapter.selectTyped(this, SCHOOL_ID) })
            f.localDb.transaction { adapter.upsertFromJson(this, HOME_ID, JsonObject(json(HOME) + ("icon" to JsonPrimitive("castle")))) }
            assertEquals(
                HOME.copy(icon = app.lares.contract.entities.PlaceIcon.PLACE),
                f.localDb.read { adapter.selectTyped(this, HOME_ID) },
            )
        }

    @Test
    fun `LOC-020 createdBy never changes`() =
        runTest {
            f.family()
            f.putPlaces()
            f.loginAs(DIMA, *LOC_ADULT)
            val e =
                assertFailsWith<LocalValidationException> {
                    f.writer.upsert(
                        EntityTypes.PLACE,
                        HOME_ID,
                        JsonObject(
                            mapOf("createdBy" to JsonPrimitive(DIMA)),
                        ),
                    )
                }
            assertEquals(mapOf("createdBy" to FieldErrorCode.INVALID_FORMAT), e.fields)
        }

    @Test
    fun `LOC-020 a place is created only with me as creator`() =
        runTest {
            f.family()
            f.loginAs(DIMA, *LOC_ADULT)
            assertFailsWith<LocalForbiddenException> { f.writer.upsert(EntityTypes.PLACE, Ids.newId(), json(HOME)) }
        }

    @Test
    fun `LOC-021 alert rows need the derived id and stay the subscriber's own`() =
        runTest {
            f.family()
            f.putPlaces()
            f.loginAs(DIMA, *LOC_ADULT)
            val mine = PlaceAlertData(ANYA, DIMA, setOf(SCHOOL_ID))
            val wrongId = assertFailsWith<LocalValidationException> { f.writer.upsert(EntityTypes.PLACE_ALERT, Ids.newId(), json(mine)) }
            assertEquals(mapOf("id" to FieldErrorCode.INVALID_FORMAT), wrongId.fields)

            val othersRow = PlaceAlertData(ANYA, OLGA, setOf(SCHOOL_ID))
            assertFailsWith<LocalForbiddenException> {
                f.writer.upsert(EntityTypes.PLACE_ALERT, Ids.placeAlertRowId(ANYA, OLGA), json(othersRow))
            }

            f.writer.upsert(EntityTypes.PLACE_ALERT, Ids.placeAlertRowId(ANYA, DIMA), json(mine))
            assertFailsWith<LocalForbiddenException> { f.writer.delete(EntityTypes.PLACE_ALERT, Ids.placeAlertRowId(ANYA, DIMA)) }
            assertEquals(1, f.outbox().size)
        }

    @Test
    fun `LOC-021 only my own alert rows are shown`() =
        runTest {
            f.family()
            f.putPlaces()
            val adapter = f.adapters[EntityTypes.PLACE_ALERT] as PlaceAlertAdapter
            f.localDb.transaction {
                adapter.upsertTyped(this, Ids.placeAlertRowId(ANYA, OLGA), PlaceAlertData(ANYA, OLGA, setOf(HOME_ID)))
                adapter.upsertTyped(this, Ids.placeAlertRowId(ANYA, DIMA), PlaceAlertData(ANYA, DIMA, setOf(SCHOOL_ID)))
            }
            f.loginAs(DIMA, *LOC_ADULT)
            assertEquals(
                listOf(ANYA to DIMA),
                f.placesRepository
                    .observeMyAlerts()
                    .first()
                    .map { it.data.memberId to it.data.subscriberId },
            )
            f.loginAs(DIMA)
            assertEquals(emptyList(), f.placesRepository.observeMyAlerts().first())
            assertNull(f.localDb.read { adapter.selectTyped(this, Ids.newId()) })
        }
}
