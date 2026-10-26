package app.lares.client.data.location

import app.lares.client.sync.LocalForbiddenException
import app.lares.client.sync.LocalValidationException
import app.lares.client.sync.LocalWriteException
import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.entities.PlaceAlertData
import app.lares.contract.sync.EntityTypes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlacesWriterTest {
    private val f = LocationFixture()

    @AfterTest
    fun close() = f.close()

    private suspend fun fields(index: Int) = LaresJson.parseToJsonElement(f.outbox()[index].fields!!).jsonObject

    @Test
    fun `LOC-020 create, edit and delete a place offline`() =
        runTest {
            f.family()
            f.loginAs(DIMA, *LOC_ADULT)
            val id = f.placesWriter.create(SCHOOL.copy(name = " Школа №12 ", createdBy = "ignored"))
            assertEquals(
                listOf("Школа №12"),
                f.placesRepository
                    .observePlaces()
                    .first()
                    .map { it.data.name },
            )
            assertEquals(DIMA, fields(0)["createdBy"]?.jsonPrimitive?.content)

            val stored =
                f.placesRepository
                    .observePlaces()
                    .first()
                    .single()
                    .data
            f.placesWriter.update(id, stored.copy(radiusM = 200))
            assertEquals(setOf("radiusM"), fields(1).keys)
            f.placesWriter.update(id, stored.copy(radiusM = 200))
            assertEquals(2, f.outbox().size, "no change, no mutation")

            f.placesWriter.delete(id)
            assertEquals(emptyList(), f.placesRepository.observePlaces().first())
            assertEquals(listOf(EntityTypes.PLACE, EntityTypes.PLACE, EntityTypes.PLACE), f.outbox().map { it.type })
        }

    @Test
    fun `LOC-020 invalid places are refused locally`() =
        runTest {
            f.family()
            f.loginAs(OLGA, *LOC_ADULT)
            val e = assertFailsWith<LocalValidationException> { f.placesWriter.create(HOME.copy(radiusM = 20)) }
            assertEquals(mapOf("radiusM" to FieldErrorCode.OUT_OF_RANGE), e.fields)
            assertEquals(emptyList(), f.outbox())
        }

    @Test
    fun `LOC-020 a kid cannot create, edit or delete places`() =
        runTest {
            f.family()
            f.putPlaces()
            f.loginAs(ANYA, *LOC_KID)
            assertFailsWith<LocalForbiddenException> { f.placesWriter.create(HOME) }
            assertFailsWith<LocalForbiddenException> { f.placesWriter.update(HOME_ID, HOME.copy(name = "Квартира")) }
            assertFailsWith<LocalForbiddenException> { f.placesWriter.delete(HOME_ID) }
            assertEquals(emptyList(), f.outbox())
            assertEquals(
                2,
                f.placesRepository
                    .observePlaces()
                    .first()
                    .size,
                "a kid still sees the places",
            )
        }

    @Test
    fun `LOC-020 places are hidden without location view`() =
        runTest {
            f.family()
            f.putPlaces()
            f.loginAs(ANYA)
            assertEquals(emptyList(), f.placesRepository.observePlaces().first())
        }

    @Test
    fun `LOC-021 alert writes the derived row with the full set only when it changes`() =
        runTest {
            f.family()
            f.putPlaces()
            f.loginAs(ANYA, *LOC_KID)
            f.placesWriter.setAlert(OLGA, emptySet())
            assertEquals(emptyList(), f.outbox(), "nothing to turn off")

            f.placesWriter.setAlert(OLGA, setOf(SCHOOL_ID))
            val rowId = Ids.placeAlertRowId(OLGA, ANYA)
            assertEquals(rowId, f.outbox().single().entity_id)
            assertEquals(setOf("memberId", "subscriberId", "placeIds"), fields(0).keys)

            f.placesWriter.setAlert(OLGA, setOf(SCHOOL_ID))
            assertEquals(1, f.outbox().size)

            f.placesWriter.setAlert(OLGA, setOf(SCHOOL_ID, HOME_ID))
            assertEquals(setOf("placeIds"), fields(1).keys)
            assertEquals(2, fields(1)["placeIds"]!!.jsonArray.size)

            f.placesWriter.setAlert(OLGA, emptySet())
            assertEquals(0, fields(2)["placeIds"]!!.jsonArray.size, "empty set = off, the row stays")
            assertEquals(
                listOf(PlaceAlert(rowId, PlaceAlertData(OLGA, ANYA, emptySet()))),
                f.placesRepository.observeMyAlerts().first(),
            )
        }

    @Test
    fun `LOC-021 alerts about myself and on unknown places are refused`() =
        runTest {
            f.family()
            f.putPlaces()
            f.loginAs(DIMA, *LOC_ADULT)
            assertFailsWith<LocalForbiddenException> { f.placesWriter.setAlert(DIMA, setOf(HOME_ID)) }
            val e = assertFailsWith<LocalWriteException> { f.placesWriter.setAlert(OLGA, setOf(Ids.newId())) }
            assertEquals(ErrorCode.ENTITY_NOT_FOUND, e.code)
            val m = assertFailsWith<LocalWriteException> { f.placesWriter.setAlert(Ids.newId(), setOf(HOME_ID)) }
            assertEquals(ErrorCode.ENTITY_NOT_FOUND, m.code)
            assertTrue(f.outbox().isEmpty())
        }
}
