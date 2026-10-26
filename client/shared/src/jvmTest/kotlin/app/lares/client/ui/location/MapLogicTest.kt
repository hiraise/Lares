package app.lares.client.ui.location

import app.lares.client.data.location.ANYA
import app.lares.client.data.location.DIMA
import app.lares.client.data.location.EnglishLocationTexts
import app.lares.client.data.location.HOME
import app.lares.client.data.location.HOME_ID
import app.lares.client.data.location.LocationTexts
import app.lares.client.data.location.MemberOnMap
import app.lares.client.data.location.OLGA
import app.lares.client.data.location.Place
import app.lares.client.data.location.SCHOOL
import app.lares.client.data.location.SCHOOL_ID
import app.lares.client.data.location.fixAt
import app.lares.contract.api.location.PositionFix
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.entities.PaletteColor
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The Map's pure parts (plan M4-08, LOC-003, LOC-013, LOC-014). */
class MapLogicTest {
    private val now = Instant.parse("2026-10-21T10:00:00Z")
    private val places = listOf(Place(HOME_ID, HOME), Place(SCHOOL_ID, SCHOOL))

    private fun member(
        id: String,
        name: String,
        status: SharingStatus = SharingStatus.SHARING,
        last: PositionFix? = fixAt(now - 1.minutes),
        stale: Boolean = false,
        moving: Boolean = false,
        place: Place? = null,
        color: PaletteColor = PaletteColor.BLUE,
    ) = MemberOnMap(id, null, name, color, VisibilityLevel.HISTORY, status, null, null, last, place, null, stale, moving)

    @Test
    fun `LOC-003 pins in the personal color, grey when paused, off, never sharing or stale, none without a fix`() {
        val members =
            listOf(
                member(OLGA, "Ольга", color = PaletteColor.RED),
                member(DIMA, "Дмитрий", status = SharingStatus.PAUSED),
                member(ANYA, "Аня", stale = true),
                member("off", "Тимур", status = SharingStatus.OFF_ON_PHONE),
                member("never", "Бабушка", status = SharingStatus.NEVER),
                member("nofix", "Дед", last = null),
            )
        val pins = MapLogic.pins(members, dark = false)
        assertEquals(listOf(OLGA, DIMA, ANYA, "off", "never"), pins.map { it.id })
        assertEquals(listOf(false, true, true, true, true), pins.map { it.grey })
        assertEquals(
            androidx.compose.ui.graphics
                .Color(0xFFC14B41),
            pins.first().color,
        )
        // Two names start with «Д»: two letters.
        assertEquals("ДМ", pins[1].initials)
        assertEquals("О", pins[0].initials)
    }

    @Test
    fun `LOC-003 accuracy circles only from 30 m`() {
        val pins =
            MapLogic.pins(
                listOf(
                    member(OLGA, "Ольга", last = fixAt(now, accuracy = 12f)),
                    member(DIMA, "Дмитрий", last = fixAt(now, accuracy = 30f)),
                ),
                dark = true,
            )
        assertNull(pins[0].accuracyM)
        assertEquals(30.0, pins[1].accuracyM)
    }

    @Test
    fun `LOC-003 rows - everybody but me, in the given (name) order`() {
        val members = listOf(member(ANYA, "Аня"), member(DIMA, "Дмитрий"), member(OLGA, "Ольга"))
        assertEquals(listOf(ANYA, OLGA), MapLogic.rows(members, DIMA).map { it.memberId })
        assertEquals(3, MapLogic.rows(members, null).size)
    }

    @Test
    fun `battery classes - red at 15 and below, charging icon, no battery without a level`() {
        fun fix(
            level: Int?,
            charging: Boolean? = null,
        ) = fixAt(now).copy(battery = level, charging = charging)
        assertEquals(BatteryInfo(15, BatteryIcon.BAR_1, low = true), MapLogic.battery(fix(15)))
        assertEquals(BatteryIcon.BAR_3, MapLogic.battery(fix(38))?.icon)
        assertEquals(BatteryIcon.BAR_5, MapLogic.battery(fix(64))?.icon)
        assertEquals(BatteryIcon.BAR_6, MapLogic.battery(fix(81))?.icon)
        assertEquals(BatteryIcon.FULL, MapLogic.battery(fix(100))?.icon)
        assertEquals(BatteryInfo(3, BatteryIcon.CHARGING, low = false), MapLogic.battery(fix(3, charging = true)))
        assertNull(MapLogic.battery(fix(null)))
        assertNull(MapLogic.battery(null))
    }

    @Test
    fun `camera - the asked member or place, else everybody, then my location`() {
        val members = listOf(member(ANYA, "Аня"), member(DIMA, "Дмитрий"), member("nofix", "Дед", last = null))
        assertEquals(CameraKind.Member(ANYA), MapLogic.initialCamera(ANYA, null, members, places))
        assertEquals(CameraKind.All, MapLogic.initialCamera("nofix", null, members, places))
        assertEquals(CameraKind.Place(SCHOOL_ID), MapLogic.initialCamera(null, SCHOOL_ID, members, places))
        assertEquals(CameraKind.All, MapLogic.initialCamera(null, "gone", members, places))

        assertEquals(CameraKind.All, MapLogic.nextLocate(null, DIMA, members))
        assertEquals(CameraKind.Member(DIMA), MapLogic.nextLocate(CameraKind.All, DIMA, members))
        assertEquals(CameraKind.All, MapLogic.nextLocate(CameraKind.Member(DIMA), DIMA, members))
        // Without an own position the button always shows everybody.
        assertEquals(CameraKind.All, MapLogic.nextLocate(CameraKind.All, "nofix", members))
    }

    @Test
    fun `LOC-014 addresses only for sharing members outside places, not moving, not stale`() {
        assertTrue(MapLogic.needsAddress(member(ANYA, "Аня")))
        assertFalse(MapLogic.needsAddress(member(ANYA, "Аня", place = places[0])))
        assertFalse(MapLogic.needsAddress(member(ANYA, "Аня", moving = true)))
        assertFalse(MapLogic.needsAddress(member(ANYA, "Аня", stale = true)))
        assertFalse(MapLogic.needsAddress(member(ANYA, "Аня", status = SharingStatus.PAUSED)))
        assertFalse(MapLogic.needsAddress(member(ANYA, "Аня", last = null)))
        assertEquals(
            MapLogic.addressKey(fixAt(now, 55.796301, 37.537801)),
            MapLogic.addressKey(fixAt(now, 55.796304, 37.537799)),
        )
    }

    @Test
    fun `LOC-014 without an address the status shows the distance to the nearest place`() {
        val texts = LocationTexts(EnglishLocationTexts())
        val near = member(ANYA, "Аня", last = fixAt(now - 5.minutes, HOME.latitude - 0.0045, HOME.longitude))
        val status = runBlocking { texts.status(near, null, now, TimeZone.of("Europe/Moscow"), places) }
        assertEquals("500 m from home · 5 min ago", status)
        val withAddress = runBlocking { texts.status(near, "ул. Ленина, 12", now, TimeZone.of("Europe/Moscow"), places) }
        assertEquals("ул. Ленина, 12 · 5 min ago", withAddress)
    }

    @Test
    fun `LOC-011 refresh for others who share now`() {
        assertTrue(MapLogic.canRefresh(member(ANYA, "Аня"), DIMA))
        assertFalse(MapLogic.canRefresh(member(DIMA, "Дмитрий"), DIMA))
        assertFalse(MapLogic.canRefresh(member(ANYA, "Аня", status = SharingStatus.PAUSED), DIMA))
        assertFalse(MapLogic.canRefresh(member(ANYA, "Аня", status = SharingStatus.OFF_ON_PHONE), DIMA))
    }
}
