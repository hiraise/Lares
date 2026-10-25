package app.lares.client.ui

import app.lares.client.data.Person
import app.lares.client.data.calendar.CalendarItem
import app.lares.client.data.calendar.TimedBlock
import app.lares.client.data.calendar.WeekDay
import app.lares.client.ui.calendar.WeekGridLogic
import app.lares.client.ui.common.UiText
import app.lares.client.ui.common.load
import app.lares.contract.calendar.EventOccurrence
import app.lares.contract.entities.EventKind
import app.lares.contract.entities.PersonData
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure layout and texts of the week grid (plan M3-07, CAL-008, CAL-013). */
class WeekGridLogicTest {
    private val monday = LocalDate(2026, 10, 19)
    private val today = LocalDate(2026, 10, 23)

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

    private fun day(n: Int) = monday.plus(n, DateTimeUnit.DAY)

    private fun event(
        id: String,
        start: LocalDate,
        end: LocalDate = start,
        startTime: LocalTime? = null,
        endTime: LocalTime? = null,
        title: String = id,
        participants: List<Person> = emptyList(),
    ) = CalendarItem.Event(
        EventOccurrence(id, start, start, end, startTime, endTime, title, null, null, emptySet(), changed = false),
        EventKind.EVENT,
        participants,
        emptyList(),
        false,
        null,
        false,
        false,
    )

    private fun block(
        start: Int,
        end: Int,
        column: Int = 0,
        columns: Int = 1,
    ) = TimedBlock(event("e", monday, startTime = LocalTime(0, 0), endTime = LocalTime(1, 0)), start, end, column, columns)

    private fun week(vararg allDay: Pair<Int, CalendarItem>): List<WeekDay> =
        (0 until 7).map { n -> WeekDay(day(n), allDay.filter { it.first == n }.map { it.second }, emptyList()) }

    @Test
    fun `block geometry from columns and minutes`() {
        val single = WeekGridLogic.blockBox(block(17 * 60 + 30, 18 * 60 + 45), 70f, 48f, 1f, 24f)
        assertEquals(0f, single.x)
        assertEquals(69f, single.width)
        assertEquals(17.5f * 48, single.y)
        assertEquals(1.25f * 48 - 1, single.height)

        val second = WeekGridLogic.blockBox(block(600, 660, column = 1, columns = 2), 70f, 48f, 1f, 24f)
        assertEquals(35f, second.x)
        assertEquals(34f, second.width)
        val third = WeekGridLogic.blockBox(block(600, 660, column = 2, columns = 3), 60f, 48f, 1f, 24f)
        assertEquals(40f, third.x)
        assertEquals(19f, third.width)

        val short = WeekGridLogic.blockBox(block(600, 610), 70f, 48f, 1f, 24f)
        assertEquals(24f, short.height, "minimum height")
        val late = WeekGridLogic.blockBox(block(23 * 60 + 50, 24 * 60), 70f, 48f, 1f, 24f)
        assertEquals(24 * 48f - 24f, late.y, "kept inside the day")
    }

    @Test
    fun `strip spans multi-day items and stacks rows`() {
        val holiday = event("holiday", day(3), day(9))
        val trip = event("trip", day(0), day(1))
        val grandma = CalendarItem.Birthday(Person("p4", PersonData("Grandma")), day(3), 78)
        val days =
            week(
                0 to trip,
                1 to trip,
                3 to grandma,
                3 to holiday,
                4 to holiday,
                5 to holiday,
                6 to holiday,
            )
        val strip = WeekGridLogic.strip(days)
        val byId = strip.chips.associateBy { it.key }
        val holidayChip = byId.getValue(WeekGridLogic.key(holiday))
        assertEquals(3 to 6, holidayChip.first to holidayChip.last, "one chip across the days it covers in the week")
        assertEquals(0 to 1, byId.getValue(WeekGridLogic.key(trip)).let { it.first to it.last })
        assertEquals(0, byId.getValue(WeekGridLogic.key(trip)).row)
        // The longer holiday takes the first row on Thursday, the birthday the next one.
        assertEquals(0, holidayChip.row)
        assertEquals(1, byId.getValue(WeekGridLogic.key(grandma)).row)
        assertEquals(2, strip.rows)
        assertFalse(strip.hasMore)
    }

    @Test
    fun `more than three rows show as plus N`() {
        val items = (1..5).map { event("e$it", day(2)) }
        val wide = event("wide", day(1), day(3))
        val strip = WeekGridLogic.strip(week(*(items.map { 2 to it } + listOf(1 to wide, 2 to wide, 3 to wide)).toTypedArray()))
        assertEquals(3, strip.rows)
        assertEquals(0, strip.chips.single { it.key == WeekGridLogic.key(wide) }.row, "starts earliest")
        assertEquals(listOf(0, 0, 3, 0, 0, 0, 0), strip.more)
        assertTrue(strip.hasMore)
    }

    @Test
    fun `initial scroll is 7 o'clock or earlier for an early block`() {
        val none = (0 until 7).map { WeekDay(day(it), emptyList(), emptyList()) }
        assertEquals(7 * 60, WeekGridLogic.initialScrollMinute(none))
        val late = none.mapIndexed { i, d -> if (i == 2) d.copy(blocks = listOf(block(9 * 60, 10 * 60))) else d }
        assertEquals(7 * 60, WeekGridLogic.initialScrollMinute(late))
        val early = none.mapIndexed { i, d -> if (i == 4) d.copy(blocks = listOf(block(6 * 60, 7 * 60))) else d }
        assertEquals(5 * 60 + 30, WeekGridLogic.initialScrollMinute(early))
        val night = none.mapIndexed { i, d -> if (i == 1) d.copy(blocks = listOf(block(10, 70))) else d }
        assertEquals(0, WeekGridLogic.initialScrollMinute(night))
    }

    @Test
    fun `week titles within a month, across months and years`() {
        assertEquals("October 19–25" to "19–25 октября", both(WeekGridLogic.weekTitle(monday, today)))
        assertEquals("Sep 28 – Oct 4" to "28 сент. – 4 окт.", both(WeekGridLogic.weekTitle(LocalDate(2026, 9, 28), today)))
        assertEquals(
            "Dec 28, 2026 – Jan 3, 2027" to "28 дек. 2026 – 3 янв. 2027",
            both(WeekGridLogic.weekTitle(LocalDate(2026, 12, 28), today)),
        )
        assertEquals("January 4–10, 2027" to "4–10 января 2027", both(WeekGridLogic.weekTitle(LocalDate(2027, 1, 4), today)))
        assertEquals("Mar 29 – Apr 4, 2027" to "29 мар. – 4 апр. 2027", both(WeekGridLogic.weekTitle(LocalDate(2027, 3, 29), today)))
    }

    @Test
    fun `block description names the day, times and participants`() {
        val anna = Person("p3", PersonData("Аня"))
        val dance =
            event("dance", monday, startTime = LocalTime(17, 30), endTime = LocalTime(18, 45), title = "Танцы", participants = listOf(anna))
        val block = TimedBlock(dance, 17 * 60 + 30, 18 * 60 + 45, 0, 1)
        assertEquals(
            "Танцы, Monday 17:30–18:45, Аня" to "Танцы, понедельник 17:30–18:45, Аня",
            both(WeekGridLogic.blockDescription(block, monday)),
        )
        assertEquals("17:30", WeekGridLogic.blockStart(block))

        val train = event("train", monday, day(1), LocalTime(22, 0), LocalTime(7, 30), title = "Train")
        val night = TimedBlock(train, 0, 7 * 60 + 30, 0, 1)
        assertEquals(
            "Train, Tuesday 0:00–7:30, Whole family" to "Train, вторник 0:00–7:30, Вся семья",
            both(WeekGridLogic.blockDescription(night, day(1))),
        )
        assertEquals(
            "Train, Monday 22:00–24:00, Whole family",
            both(WeekGridLogic.blockDescription(TimedBlock(train, 22 * 60, 24 * 60, 0, 1), monday)).first,
        )
    }
}
