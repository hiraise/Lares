package app.lares.client.ui.location

import app.lares.client.data.location.HOME
import app.lares.client.data.location.HOME_ID
import app.lares.client.data.location.Place
import app.lares.client.data.location.SCHOOL
import app.lares.client.data.location.SCHOOL_ID
import app.lares.client.ui.common.UiText
import app.lares.client.ui.common.load
import app.lares.contract.api.location.TrackPoint
import app.lares.contract.location.Stop
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** Pure parts of the history screen (plan M4-09, LOC-004). */
class HistoryLogicTest {
    private val zone = TimeZone.of("Europe/Moscow")
    private val places = listOf(Place(HOME_ID, HOME), Place(SCHOOL_ID, SCHOOL))

    @AfterTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    private fun both(text: UiText): Pair<String, String> =
        runBlocking {
            Locale.setDefault(Locale.ENGLISH)
            val en = text.load()
            Locale.setDefault(Locale.forLanguageTag("ru"))
            val ru = text.load()
            Locale.setDefault(Locale.ENGLISH)
            en to ru
        }

    /** UTC times; Moscow is UTC+3. */
    private fun stop(
        start: String,
        end: String,
        placeId: String?,
        ongoing: Boolean = false,
        lat: Double = 55.81,
        lon: Double = 37.52,
    ) = Stop(Instant.parse("2026-10-21T$start:00Z"), Instant.parse("2026-10-21T$end:00Z"), lat, lon, placeId, ongoing)

    @Test
    fun `LOC-004 stop rows - place, home, address, no address, ongoing`() {
        val stops =
            listOf(
                stop("04:00", "05:10", HOME_ID),
                stop("05:25", "11:40", SCHOOL_ID),
                stop("12:00", "13:30", null, lat = 55.751234, lon = 37.618765),
                stop("13:40", "13:55", null, lat = 55.70, lon = 37.50),
                stop("13:50", "14:20", HOME_ID, ongoing = true),
            )
        val addresses = mapOf((55.7512 to 37.6188) to "ул. Ленина, 12", (55.70 to 37.50) to null)
        val rows = HistoryLogic.rows(stops, places, addresses, zone)
        assertEquals(("At home" to "Дома") to ("7:00–8:10" to "7:00–8:10"), both(rows[0].title) to both(rows[0].time))
        assertEquals(true, rows[0].home)
        assertEquals("Школа №12" to "8:25–14:40", both(rows[1].title).second to both(rows[1].time).second)
        assertEquals("ул. Ленина, 12", both(rows[2].title).first)
        assertEquals("No address" to "Без адреса", both(rows[3].title))
        assertEquals("since 16:50" to "с 16:50", both(rows[4].time))
        assertEquals(listOf("stop_0", "stop_1", "stop_2", "stop_3", "stop_4"), rows.map { it.id })
        assertEquals(listOf(false, false, true, true, false), stops.map(HistoryLogic::needsAddress))
    }

    @Test
    fun `LOC-004 day strip has historyDays days from today back`() {
        val today = LocalDate(2026, 10, 21)
        val days = HistoryLogic.days(today, 30)
        assertEquals(30, days.size)
        assertEquals(today, days.first())
        assertEquals(LocalDate(2026, 9, 22), days.last())
        assertEquals(7, HistoryLogic.days(today, 7).size)
        assertEquals("Today" to "Сегодня", both(HistoryLogic.dayLabel(today, today)))
        assertEquals("Yesterday" to "Вчера", both(HistoryLogic.dayLabel(LocalDate(2026, 10, 20), today)))
        assertEquals("Mon, Oct 19" to "Пн, 19 окт.", both(HistoryLogic.dayLabel(LocalDate(2026, 10, 19), today)))
    }

    @Test
    fun `camera fits the track, one spot is zoom 16, nothing without points`() {
        fun point(
            lat: Double,
            lon: Double,
        ) = TrackPoint(Instant.parse("2026-10-21T10:00:00Z"), lat, lon, 10f)
        assertNull(HistoryLogic.camera(emptyList()))
        assertEquals(CameraKind.Point(55.8, 37.5, 16.0), HistoryLogic.camera(listOf(point(55.8, 37.5), point(55.8, 37.5))))
        assertEquals(
            CameraKind.Bounds(south = 55.79, west = 37.50, north = 55.81, east = 37.55),
            HistoryLogic.camera(listOf(point(55.80, 37.55), point(55.79, 37.52), point(55.81, 37.50))),
        )
        val s = stop("05:25", "11:40", SCHOOL_ID, lat = 55.8001, lon = 37.5329)
        assertEquals(CameraKind.Point(55.8001, 37.5329, 16.0), HistoryLogic.stopCamera(s))
    }
}
