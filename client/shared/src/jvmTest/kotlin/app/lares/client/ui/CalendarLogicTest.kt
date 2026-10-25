package app.lares.client.ui

import app.lares.client.data.Person
import app.lares.client.data.calendar.CalendarItem
import app.lares.client.ui.calendar.CalendarLogic
import app.lares.client.ui.common.UiText
import app.lares.client.ui.common.load
import app.lares.contract.calendar.EventOccurrence
import app.lares.contract.entities.EventKind
import app.lares.contract.entities.PersonData
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Texts of the Calendar tab and the event page (plan M3-05), in English and Russian. */
class CalendarLogicTest {
    private val today = LocalDate(2026, 10, 23) // Friday

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

    private fun assertTexts(
        en: String,
        ru: String,
        text: UiText?,
    ) = assertEquals(en to ru, both(text!!))

    private fun occ(
        start: LocalDate,
        end: LocalDate = start,
        startTime: LocalTime? = null,
        endTime: LocalTime? = null,
    ) = EventOccurrence("e", start, start, end, startTime, endTime, "Event", null, null, emptySet(), changed = false)

    private fun item(
        occ: EventOccurrence,
        before: Boolean = false,
        after: Boolean = false,
    ) = CalendarItem.Event(occ, EventKind.EVENT, emptyList(), emptyList(), false, null, before, after)

    private fun person(name: String) = Person("p-$name", PersonData(name))

    @Test
    fun `month title and day headers`() {
        assertTexts("October 2026", "Октябрь 2026", CalendarLogic.monthTitle(2026, 10))
        assertTexts("Today, October 23", "Сегодня, 23 октября", CalendarLogic.dayHeader(today, today))
        assertTexts("Saturday, October 24", "Суббота, 24 октября", CalendarLogic.dayHeader(LocalDate(2026, 10, 24), today))
        assertTexts("Today", "Сегодня", CalendarLogic.listHeader(today, today))
        assertTexts("Tomorrow", "Завтра", CalendarLogic.listHeader(LocalDate(2026, 10, 24), today))
        assertTexts("Sunday, October 25", "Воскресенье, 25 октября", CalendarLogic.listHeader(LocalDate(2026, 10, 25), today))
        assertTexts(
            "Friday, January 1, 2027",
            "Пятница, 1 января 2027",
            CalendarLogic.listHeader(LocalDate(2027, 1, 1), today),
        )
    }

    @Test
    fun `time column for timed, all-day and continuing occurrences`() {
        val timed = item(occ(today, startTime = LocalTime(17, 30), endTime = LocalTime(18, 45)))
        val column = CalendarLogic.timeColumn(timed, today)
        assertTexts("17:30", "17:30", column.top)
        assertTexts("18:45", "18:45", column.bottom)

        val allDay = CalendarLogic.timeColumn(item(occ(today)), today)
        assertTexts("All day", "Весь день", allDay.top)
        assertEquals(null, allDay.bottom)

        val holiday = occ(LocalDate(2026, 7, 10), LocalDate(2026, 7, 20))
        assertTexts("until July 20", "до 20 июля", CalendarLogic.timeColumn(item(holiday, after = true), today).bottom)
        assertTexts("until July 20", "до 20 июля", CalendarLogic.timeColumn(item(holiday, before = true, after = true), today).bottom)
        assertTexts("from July 10", "с 10 июля", CalendarLogic.timeColumn(item(holiday, before = true), today).bottom)

        val train = occ(LocalDate(2026, 7, 10), LocalDate(2026, 7, 11), LocalTime(22, 0), LocalTime(7, 30))
        val first = CalendarLogic.timeColumn(item(train, after = true), today)
        assertTexts("22:00", "22:00", first.top)
        assertTexts("until July 11", "до 11 июля", first.bottom)
        val last = CalendarLogic.timeColumn(item(train, before = true), today)
        assertTexts("until 7:30", "до 7:30", last.top)
        assertTexts("from July 10", "с 10 июля", last.bottom)
    }

    @Test
    fun `meta, birthdays, memorable dates and ages`() {
        assertTexts("Whole family", "Вся семья", CalendarLogic.eventMeta(emptyList(), null))
        assertTexts(
            "Аня, Ольга · ДК «Юность»",
            "Аня, Ольга · ДК «Юность»",
            CalendarLogic.eventMeta(listOf(person("Аня"), person("Ольга")), " ДК «Юность» "),
        )
        assertTexts("Бабушка Валя, 78 years", "Бабушка Валя, 78 лет", CalendarLogic.birthdayTitle("Бабушка Валя", 78))
        assertTexts("Birthday: Дядя Коля", "День рождения: Дядя Коля", CalendarLogic.birthdayTitle("Дядя Коля", null))
        assertTexts(
            "Годовщина свадьбы · 16 years",
            "Годовщина свадьбы · 16 лет",
            CalendarLogic.memorableTitle("Годовщина свадьбы", 16),
        )
        assertTexts("Годовщина", "Годовщина", CalendarLogic.memorableTitle("Годовщина", null))
        assertTexts("1 year", "1 год", CalendarLogic.years(1))
        assertTexts("2 years", "2 года", CalendarLogic.years(2))
        assertTexts("5 years", "5 лет", CalendarLogic.years(5))
        assertTexts("21 years", "21 год", CalendarLogic.years(21))
    }

    @Test
    fun `when texts of the event page`() {
        val tuesday = LocalDate(2026, 10, 27)
        assertTexts(
            "Tuesday, October 27 · 16:00–17:00",
            "Вторник, 27 октября · 16:00–17:00",
            CalendarLogic.detailWhen(occ(tuesday, startTime = LocalTime(16, 0), endTime = LocalTime(17, 0)), today),
        )
        assertTexts("Tuesday, October 27 · all day", "Вторник, 27 октября · весь день", CalendarLogic.detailWhen(occ(tuesday), today))
        assertTexts(
            "July 10 – July 20",
            "10 июля – 20 июля",
            CalendarLogic.detailWhen(occ(LocalDate(2026, 7, 10), LocalDate(2026, 7, 20)), today),
        )
        assertTexts(
            "July 10, 22:00 – July 11, 7:30",
            "10 июля, 22:00 – 11 июля, 7:30",
            CalendarLogic.detailWhen(occ(LocalDate(2026, 7, 10), LocalDate(2026, 7, 11), LocalTime(22, 0), LocalTime(7, 30)), today),
        )
        assertTexts("June 12 · every year", "12 июня · каждый год", CalendarLogic.memorableWhen(LocalDate(2010, 6, 12)))
        val dances = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.WEEK, 1, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
        assertTexts(
            "Every Mon and Wed · until May 31, 2027",
            "По пн и ср · до 31 мая 2027",
            CalendarLogic.rule(dances, LocalDate(2026, 9, 7), LocalDate(2027, 5, 31), today),
        )
        assertTexts("Every Mon and Wed", "По пн и ср", CalendarLogic.rule(dances, LocalDate(2026, 9, 7), null, today))
    }

    @Test
    fun `reminder options and texts`() {
        assertEquals(listOf(0, 5, 10, 15, 30, 60, 120, 1440, 2880, 10080), CalendarLogic.reminderOptions(allDay = false))
        assertEquals(listOf(0, 1440, 2880, 10080), CalendarLogic.reminderOptions(allDay = true))
        assertTexts("None", "Нет", CalendarLogic.reminder(null, allDay = false))
        assertTexts("1 hour before", "За 1 час", CalendarLogic.reminder(60, allDay = false))
        assertTexts("At the start", "В начале", CalendarLogic.reminder(0, allDay = false))
        assertTexts("On the day at 9:00", "В день события в 9:00", CalendarLogic.reminder(0, allDay = true))
        assertTexts("1 day before at 9:00", "За 1 день в 9:00", CalendarLogic.reminder(1440, allDay = true))
        assertTexts(
            "1 hour before (as in the event)",
            "За 1 час (как в событии)",
            CalendarLogic.myReminder(60, allDay = false, own = false),
        )
        assertTexts("2 hours before", "За 2 часа", CalendarLogic.myReminder(120, allDay = false, own = true))
        assertTexts("None (as in the event)", "Нет (как в событии)", CalendarLogic.myReminder(null, allDay = false, own = false))
    }
}
