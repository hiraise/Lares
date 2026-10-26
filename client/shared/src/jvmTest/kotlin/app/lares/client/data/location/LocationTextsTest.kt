package app.lares.client.data.location

import app.lares.client.ui.common.LocationResourceTexts
import app.lares.contract.api.location.OffReason
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.entities.PaletteColor
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class LocationTextsTest {
    // Wednesday 2026-10-21 13:00 in Moscow.
    private val now = Instant.parse("2026-10-21T10:00:00Z")
    private val zone = TimeZone.of("Europe/Moscow")
    private val texts = LocationTexts(LocationResourceTexts())
    private val home = Place(HOME_ID, HOME)
    private val school = Place(SCHOOL_ID, SCHOOL)

    @AfterTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    private fun member(
        status: SharingStatus = SharingStatus.SHARING,
        ago: kotlin.time.Duration? = 1.minutes,
        pausedUntil: Instant? = null,
        offReason: OffReason? = null,
        place: Place? = null,
        placeSince: Instant? = null,
        stale: Boolean = false,
        moving: Boolean = false,
        lat: Double = HOME.latitude,
    ) = MemberOnMap(
        memberId = ANYA,
        personId = P_ANYA,
        name = "Аня",
        color = PaletteColor.GREEN,
        level = VisibilityLevel.HISTORY,
        status = status,
        pausedUntil = pausedUntil,
        offReason = offReason,
        last = ago?.let { fixAt(now - it, lat = lat) },
        place = place,
        placeSince = placeSince,
        stale = stale,
        moving = moving,
    )

    /** The status line in ru and en. */
    private fun status(
        m: MemberOnMap,
        address: String? = null,
        places: List<Place> = emptyList(),
    ): Pair<String, String> =
        runBlocking {
            Locale.setDefault(Locale.forLanguageTag("ru"))
            val ru = texts.status(m, address, now, zone, places)
            Locale.setDefault(Locale.ENGLISH)
            ru to texts.status(m, address, now, zone, places)
        }

    private fun age(at: Instant): Pair<String, String> =
        runBlocking {
            Locale.setDefault(Locale.forLanguageTag("ru"))
            val ru = texts.age(at, now, zone)
            Locale.setDefault(Locale.ENGLISH)
            ru to texts.age(at, now, zone)
        }

    @Test
    fun `LOC-013 never shared`() =
        assertEquals("Не делится геопозицией" to "Not sharing location", status(member(SharingStatus.NEVER, ago = null)))

    @Test
    fun `LOC-010 paused with and without an end`() {
        assertEquals(
            "На паузе до 15:30" to "Paused until 15:30",
            status(
                member(
                    SharingStatus.PAUSED,
                    pausedUntil =
                        now + 2.hours + 30.minutes,
                ),
            ),
        )
        assertEquals(
            "На паузе до 8:00" to "Paused until 8:00",
            status(member(SharingStatus.PAUSED, pausedUntil = Instant.parse("2026-10-22T05:00:00Z"))),
        )
        assertEquals(
            "На паузе до 24 окт. 9:00" to "Paused until Oct 24 9:00",
            status(member(SharingStatus.PAUSED, pausedUntil = Instant.parse("2026-10-24T06:00:00Z"))),
        )
        assertEquals("На паузе" to "Paused", status(member(SharingStatus.PAUSED)))
    }

    @Test
    fun `LOC-012 off on the phone with the reason and the last place time`() {
        // The last fix at 14:20 Moscow time would be in the future; 11:20 today is 08:20 UTC.
        assertEquals(
            "Геопозиция выключена на телефоне · последнее место в 11:20" to "Location is off on the phone · last seen at 11:20",
            status(
                member(
                    SharingStatus.OFF_ON_PHONE,
                    ago = now - Instant.parse("2026-10-21T08:20:00Z"),
                    offReason = OffReason.LOCATION_DISABLED,
                ),
            ),
        )
        assertEquals(
            "Нет доступа к геопозиции · последнее место вчера в 22:05" to "No location access · last seen yesterday at 22:05",
            status(
                member(
                    SharingStatus.OFF_ON_PHONE,
                    ago = now - Instant.parse("2026-10-20T19:05:00Z"),
                    offReason = OffReason.PERMISSION_REVOKED,
                ),
            ),
        )
        assertEquals(
            "Передача остановлена" to "Sharing stopped",
            status(member(SharingStatus.OFF_ON_PHONE, ago = null, offReason = OffReason.STOPPED)),
        )
        assertEquals(
            "Выход из приложения · последнее место 12 окт. в 9:30" to "Signed out of the app · last seen Oct 12 at 9:30",
            status(member(SharingStatus.OFF_ON_PHONE, ago = now - Instant.parse("2026-10-12T06:30:00Z"), offReason = OffReason.SIGNED_OUT)),
        )
        assertEquals(
            "Передача остановлена" to "Sharing stopped",
            status(member(SharingStatus.OFF_ON_PHONE, ago = null)),
            "unknown reason = stopped",
        )
    }

    @Test
    fun `LOC-013 stale positions and no data`() {
        assertEquals("Нет данных с 12:05" to "No data since 12:05", status(member(ago = 55.minutes, stale = true)))
        assertEquals(
            "Нет данных со вчера, 18:05" to "No data since yesterday 18:05",
            status(
                member(
                    ago =
                        now - Instant.parse("2026-10-20T15:05:00Z"),
                    stale = true,
                ),
            ),
        )
        assertEquals("Нет данных с 12 окт." to "No data since Oct 12", status(member(ago = 9.days, stale = true)))
        assertEquals("Нет данных" to "No data", status(member(ago = null)))
    }

    @Test
    fun `LOC-003 in a place since`() {
        assertEquals("Дома · с 12:10" to "At home · since 12:10", status(member(place = home, placeSince = now - 50.minutes)))
        assertEquals(
            "Школа №12 · со вчера, 8:25" to "Школа №12 · since yesterday 8:25",
            status(member(place = school, placeSince = Instant.parse("2026-10-20T05:25:00Z"))),
        )
        assertEquals("Школа №12" to "Школа №12", status(member(place = school)))
    }

    @Test
    fun `LOC-014 moving, address, distance to the nearest place, no address`() {
        assertEquals("В пути · сейчас" to "On the way · now", status(member(moving = true, ago = 30.seconds())))
        assertEquals(
            "ул. Ленина, 12 · 3 мин назад" to "ул. Ленина, 12 · 3 min ago",
            status(member(ago = 3.minutes), address = "ул. Ленина, 12"),
        )
        // 0.018° of latitude ≈ 2 km south of home (the school is north of it).
        val away = member(ago = 3.minutes, lat = HOME.latitude - 0.018)
        assertEquals("2 км от дома · 3 мин назад" to "2 km from home · 3 min ago", status(away, places = listOf(home, school)))
        assertEquals(
            "350 м от места «Школа №12» · 3 мин назад" to "350 m from Школа №12 · 3 min ago",
            status(
                member(ago = null).copy(last = fixAt(now - 3.minutes, SCHOOL.latitude + 0.00315, SCHOOL.longitude)),
                places = listOf(school),
            ),
        )
        assertEquals("Без адреса · 3 мин назад" to "No address · 3 min ago", status(member(ago = 3.minutes)))
    }

    @Test
    fun `LOC-003 age texts`() {
        assertEquals("сейчас" to "now", age(now - 90.seconds()))
        assertEquals("2 мин назад" to "2 min ago", age(now - 2.minutes - 1.seconds()))
        assertEquals("59 мин назад" to "59 min ago", age(now - 59.minutes))
        assertEquals("в 11:30" to "at 11:30", age(now - 90.minutes))
        assertEquals("вчера в 23:00" to "yesterday at 23:00", age(Instant.parse("2026-10-20T20:00:00Z")))
        assertEquals("12 окт. в 14:20" to "Oct 12 at 14:20", age(Instant.parse("2026-10-12T11:20:00Z")))
    }

    @Test
    fun `LOC-003 battery classes and distances`() =
        runBlocking {
            assertEquals(
                listOf(BatteryLevel.LOW, BatteryLevel.LOW, BatteryLevel.MEDIUM, BatteryLevel.HIGH),
                listOf(5, 15, 50, 51).map(texts::battery),
            )
            assertEquals(
                listOf("10 m", "350 m", "1 km", "2.5 km", "12 km"),
                listOf(3.0, 347.0, 1020.0, 2460.0, 12_300.0).map {
                    formatDistance(it, ".", "m", "km")
                },
            )
            val en = EnglishLocationTexts()
            assertEquals("Paused until 15:30", en.paused("15:30"))
            assertEquals("2.5 km", en.distance(2460.0))
        }

    private fun Int.seconds() = kotlin.time.Duration.parse("${this}s")
}
