package app.lares.client.ui.location

import app.lares.client.data.location.ANYA
import app.lares.client.data.location.DIMA
import app.lares.client.data.location.HOME
import app.lares.client.data.location.HOME_ID
import app.lares.client.data.location.LOC_ADULT
import app.lares.client.data.location.LocationFixture
import app.lares.client.data.location.MemberOnMap
import app.lares.client.data.location.OLGA
import app.lares.client.data.location.Place
import app.lares.client.data.location.PlaceAlert
import app.lares.client.data.location.SCHOOL
import app.lares.client.data.location.SCHOOL_ID
import app.lares.contract.Ids
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.PlaceAlertData
import app.lares.contract.sync.EntityTypes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** «Сообщать: прибытие и уход» (plan M4-10, LOC-021): rows and the derived rows a save writes. */
class AlertsSheetTest {
    private val f = LocationFixture()
    private val places = listOf(Place(SCHOOL_ID, SCHOOL), Place(HOME_ID, HOME))

    @AfterTest
    fun close() = f.close()

    private fun member(
        id: String,
        name: String,
        place: Place? = null,
    ) = MemberOnMap(
        id,
        null,
        name,
        PaletteColor.GREEN,
        VisibilityLevel.HISTORY,
        SharingStatus.SHARING,
        null,
        null,
        null,
        place,
        null,
        false,
        false,
    )

    private fun alert(
        memberId: String,
        vararg placeIds: String,
    ) = PlaceAlert(Ids.placeAlertRowId(memberId, DIMA), PlaceAlertData(memberId, DIMA, placeIds.toSet()))

    @Test
    fun `LOC-021 member rows - places by name, checked from own alerts, here now marked`() {
        val rows = AlertsLogic.memberRows(member(ANYA, "Аня", place = places[0]), places, listOf(alert(ANYA, HOME_ID)))
        assertEquals(listOf("Дом", "Школа №12"), rows.map { it.title })
        assertEquals(listOf(true, false), rows.map { it.checked })
        assertEquals(listOf(false, true), rows.map { it.hereNow })
    }

    @Test
    fun `LOC-021 place rows - the others the viewer sees, checked when the place is in their set`() {
        val members = listOf(member(OLGA, "Ольга"), member(DIMA, "Дмитрий"), member(ANYA, "Аня", place = places[0]))
        val rows = AlertsLogic.placeRows(SCHOOL_ID, members, DIMA, listOf(alert(ANYA, SCHOOL_ID), alert(OLGA, HOME_ID)))
        assertEquals(listOf("Аня", "Ольга"), rows.map { it.title }, "not the viewer, by name")
        assertEquals(listOf(true, false), rows.map { it.checked })
        assertEquals(listOf(true, false), rows.map { it.hereNow })
    }

    @Test
    fun `LOC-021 changes - one full set per member, a place target keeps the other places`() {
        val alerts = listOf(alert(ANYA, HOME_ID), alert(OLGA, SCHOOL_ID))
        val target = AlertsTarget.Member(ANYA)
        val rows = AlertsLogic.memberRows(member(ANYA, "Аня"), places, alerts)
        assertEquals(mapOf(ANYA to setOf(HOME_ID, SCHOOL_ID)), AlertsLogic.changes(target, rows, setOf(HOME_ID, SCHOOL_ID), alerts))
        assertEquals(emptyMap(), AlertsLogic.changes(target, rows, setOf(HOME_ID), alerts), "no change, nothing written")
        assertEquals(mapOf(ANYA to emptySet()), AlertsLogic.changes(target, rows, emptySet(), alerts), "all off = empty set")

        val place = AlertsTarget.Place(SCHOOL_ID)
        val placeRows = AlertsLogic.placeRows(SCHOOL_ID, listOf(member(ANYA, "Аня"), member(OLGA, "Ольга")), DIMA, alerts)
        assertEquals(
            mapOf(ANYA to setOf(HOME_ID, SCHOOL_ID), OLGA to emptySet()),
            AlertsLogic.changes(place, placeRows, setOf(ANYA), alerts),
        )
    }

    @Test
    fun `LOC-021 save writes the derived row, then an empty set turns it off`() =
        runTest {
            f.family()
            f.putPlaces()
            f.loginAs(DIMA, *LOC_ADULT)
            AlertsLogic.save(f.placesWriter, mapOf(ANYA to setOf(SCHOOL_ID)))
            val row =
                f.placesRepository
                    .observeMyAlerts()
                    .first()
                    .single()
            assertEquals(Ids.placeAlertRowId(ANYA, DIMA), row.id)
            assertEquals(setOf(SCHOOL_ID), row.data.placeIds)
            assertEquals(EntityTypes.PLACE_ALERT, f.outbox().last().type)

            AlertsLogic.save(f.placesWriter, mapOf(ANYA to emptySet()))
            assertTrue(
                f.placesRepository
                    .observeMyAlerts()
                    .first()
                    .single()
                    .data.placeIds
                    .isEmpty(),
            )
            assertEquals(Ids.placeAlertRowId(ANYA, DIMA), f.outbox().last().entity_id)
        }
}
