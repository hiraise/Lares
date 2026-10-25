package app.lares.client.data.calendar

import app.lares.contract.Ids
import app.lares.contract.entities.EventData
import app.lares.contract.entities.EventKind
import app.lares.contract.entities.EventOccurrenceData
import app.lares.contract.entities.EventReminderData
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import app.lares.contract.entities.TaskData
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure view logic; the fixture only provides persons, members, the viewer and task views. Today: Wed 2026-10-21 13:00. */
class CalendarViewsTest {
    private val f = CalendarFixture()

    private fun d(
        day: Int,
        month: Int = 10,
    ) = LocalDate(2026, month, day)

    private fun t(
        hour: Int,
        minute: Int = 0,
    ) = LocalTime(hour, minute)

    private val weekly =
        Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.WEEK, 1, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))

    private suspend fun inputs(
        events: List<Pair<String, EventData>> = emptyList(),
        rows: List<EventOccurrenceData> = emptyList(),
        reminders: List<EventReminderData> = emptyList(),
        tasks: List<Pair<String, TaskData>> = emptyList(),
        login: String = OLGA,
        kid: Boolean = false,
    ): CalendarInputs {
        f.family()
        f.loginAs(login, *(if (kid) CAL_KID else CAL_ADULT))
        tasks.forEach { (id, task) -> f.putTask(id, task) }
        return f.inputs().copy(events = events, occurrenceRows = rows, myReminders = reminders)
    }

    private fun titles(day: DayView) =
        day.items.map {
            when (it) {
                is CalendarItem.Event -> it.occ.title
                is CalendarItem.Birthday -> "🎂 ${it.person.data.name}"
                is CalendarItem.Task -> "☐ ${it.view.data.title}"
            }
        }

    private fun id() = Ids.newId()

    @Test
    fun `CAL-004 CAL-009 a day lists birthdays, memorable dates, all-day, timed with tasks, then untimed tasks`() =
        runTest {
            val memorable =
                EventData(
                    "Годовщина",
                    OLGA,
                    LocalDate(2010, 10, 24),
                    LocalDate(2010, 10, 24),
                    allDay = true,
                    recurrence = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.YEAR, 1),
                    kind = EventKind.MEMORABLE_DATE,
                    countYears = true,
                )
            val i =
                inputs(
                    events =
                        listOf(
                            id() to timedEvent("Вечер", d(24), t(18), t(19)),
                            id() to allDayEvent("Ярмарка", d(24)),
                            id() to timedEvent("Бассейн", d(24), t(10), t(11)),
                            id() to allDayEvent("Поход", d(24)),
                            id() to memorable,
                        ),
                    tasks =
                        listOf(
                            id() to TaskData("Без времени", OLGA, dueDate = d(24)),
                            id() to TaskData("Утром", OLGA, dueDate = d(24), dueTime = t(9)),
                            id() to TaskData("Просрочено", OLGA, dueDate = d(20)),
                            id() to TaskData("Без даты", OLGA),
                        ),
                )
            val days = CalendarViews.days(i, d(21), d(24))
            assertEquals(listOf(d(21), d(22), d(23), d(24)), days.map { it.date })
            assertEquals(
                listOf("🎂 Бабушка Валя", "Годовщина", "Поход", "Ярмарка", "☐ Утром", "Бассейн", "Вечер", "☐ Без времени"),
                titles(days[3]),
            )
            assertEquals(16, (days[3].items[1] as CalendarItem.Event).years)
            assertEquals(78, (days[3].items[0] as CalendarItem.Birthday).age)
            // Overdue on today, flagged.
            val overdue = days[0].items.single() as CalendarItem.Task
            assertEquals("Просрочено", overdue.view.data.title)
            assertTrue(overdue.overdue)
            assertEquals(listOf("🎂 Аня"), titles(days[2]))
        }

    @Test
    fun `CAL-013 a multi-day event is on each day it covers`() =
        runTest {
            val i = inputs(events = listOf(id() to allDayEvent("Отпуск", d(22), d(24))))
            val items = CalendarViews.days(i, d(21), d(25)).map { it.items.filterIsInstance<CalendarItem.Event>() }
            assertEquals(listOf(0, 1, 1, 1, 0), items.map { it.size })
            assertEquals(
                listOf(false to true, true to true, true to false),
                items.drop(1).take(3).map {
                    it[0].continuesBefore to
                        it[0].continuesAfter
                },
            )
        }

    @Test
    fun `CAL-015 moved and cancelled occurrences`() =
        runTest {
            val e = id()
            val event = timedEvent("Танцы", d(19), t(17, 30), t(18, 45)).copy(recurrence = weekly)
            val i =
                inputs(
                    events = listOf(e to event),
                    rows =
                        listOf(
                            EventOccurrenceData(e, d(21), cancelled = true),
                            EventOccurrenceData(e, d(26), startDate = d(27), endDate = d(27), startTime = t(18), endTime = t(19)),
                            // A reset row changes nothing.
                            EventOccurrenceData(e, d(28)),
                        ),
                )
            val byDay = CalendarViews.days(i, d(19), d(28)).associate { it.date to it.items.filterIsInstance<CalendarItem.Event>() }
            assertEquals(listOf(d(19), d(27), d(28)), byDay.filterValues { it.isNotEmpty() }.keys.toList())
            val moved = byDay.getValue(d(27)).single()
            assertTrue(moved.occ.changed)
            assertEquals(d(26), moved.occ.occurrence)
            assertEquals(t(18), moved.occ.startTime)
            assertTrue(moved.repeating)
        }

    @Test
    fun `CAL-008 month has 42 cells from Monday with distinct dots`() =
        runTest {
            val i =
                inputs(
                    events =
                        listOf(
                            id() to timedEvent("Родители", d(22), t(18), t(19), setOf(P_OLGA, P_DIMA)),
                            id() to timedEvent("Ещё раз", d(22), t(20), t(21), setOf(P_OLGA)),
                            id() to timedEvent("Семья", d(23), t(10), t(11)),
                            id() to timedEvent("Все", d(26), t(10), t(11), setOf(P_OLGA, P_DIMA, P_ANYA, P_VALYA)),
                        ),
                    tasks = listOf(id() to TaskData("Задача", OLGA, dueDate = d(27))),
                )
            val cells = CalendarViews.month(i, 2026, 10)
            assertEquals(42, cells.size)
            assertEquals(LocalDate(2026, 9, 28), cells.first().date)
            assertFalse(cells.first().inMonth)
            assertTrue(cells.single { it.date == d(21) }.today)
            val cell = cells.associateBy { it.date }
            // Participants in the persons' name order: Дмитрий, Ольга.
            assertEquals(listOf(PaletteColor.BLUE, PaletteColor.RED), cell.getValue(d(22)).dots)
            // Аня's birthday first, then the whole family (null).
            assertEquals(listOf(PaletteColor.GREEN, null), cell.getValue(d(23)).dots)
            assertEquals(listOf(PaletteColor.PURPLE), cell.getValue(d(24)).dots)
            // Persons in name order: Аня, Бабушка Валя, Дмитрий, Ольга → 3 dots and more.
            assertEquals(listOf(PaletteColor.GREEN, PaletteColor.PURPLE, PaletteColor.BLUE), cell.getValue(d(26)).dots)
            assertTrue(cell.getValue(d(26)).more)
            assertEquals(emptyList(), cell.getValue(d(27)).dots)
        }

    @Test
    fun `CAL-008 week clips across midnight and lays out overlapping blocks`() =
        runTest {
            val i =
                inputs(
                    events =
                        listOf(
                            id() to timedEvent("A", d(19), t(10), t(12)),
                            id() to timedEvent("B", d(19), t(11), t(13)),
                            id() to timedEvent("C", d(19), t(11, 30), t(12, 30)),
                            id() to timedEvent("D", d(20), t(10), t(11)),
                            id() to timedEvent("E", d(20), t(10, 30), t(11, 30)),
                            id() to timedEvent("F", d(20), t(12), t(12, 10)),
                            id() to timedEvent("Поезд", d(22), t(22), t(7), endDate = d(23)),
                            id() to allDayEvent("Отпуск", d(24)),
                        ),
                    tasks = listOf(id() to TaskData("Задача", OLGA, dueDate = d(19), dueTime = t(9))),
                )
            val week = CalendarViews.week(i, d(19))
            assertEquals(7, week.size)

            fun layout(day: Int) = week.single { it.date == d(day) }.blocks.map { Triple(it.item.occ.title, it.column, it.columns) }
            assertEquals(listOf(Triple("A", 0, 3), Triple("B", 1, 3), Triple("C", 2, 3)), layout(19))
            assertEquals(listOf(Triple("D", 0, 2), Triple("E", 1, 2), Triple("F", 0, 1)), layout(20))
            val short = week.single { it.date == d(20) }.blocks.last()
            assertEquals(12 * 60 to 12 * 60 + 30, short.startMinute to short.endMinute)
            val thu = week.single { it.date == d(22) }.blocks.single()
            val fri = week.single { it.date == d(23) }.blocks.single()
            assertEquals(22 * 60 to 24 * 60, thu.startMinute to thu.endMinute)
            assertEquals(0 to 7 * 60, fri.startMinute to fri.endMinute)
            // Birthdays in the strip; tasks are left out.
            assertEquals(listOf("🎂 Бабушка Валя", "Отпуск"), titles(DayView(d(24), week.single { it.date == d(24) }.allDay)))
            assertEquals(3, week.single { it.date == d(19) }.let { it.allDay.size + it.blocks.size })
        }

    @Test
    fun `CAL-008 CAL-009 TAG-003 filters by person, tag and tasks`() =
        runTest {
            val tag = id()
            val i =
                inputs(
                    events =
                        listOf(
                            id() to timedEvent("С Аней", d(23), t(10), t(11), setOf(P_ANYA)),
                            id() to timedEvent("Для всех", d(23), t(12), t(13)),
                            id() to timedEvent("Только Ольга", d(23), t(14), t(15), setOf(P_OLGA)).copy(tagIds = setOf(tag)),
                        ),
                    tasks =
                        listOf(
                            id() to TaskData("Ане", OLGA, dueDate = d(23), assigneeIds = setOf(ANYA)),
                            id() to TaskData("Любому", OLGA, dueDate = d(23)),
                            id() to TaskData("Диме", OLGA, dueDate = d(23), assigneeIds = setOf(DIMA), tagIds = setOf(tag)),
                        ),
                )
            val anya = CalendarViews.days(i, d(23), d(24), CalendarFilter(personId = P_ANYA))
            assertEquals(listOf("🎂 Аня", "С Аней", "Для всех", "☐ Ане", "☐ Любому"), titles(anya[0]))
            assertEquals(emptyList(), titles(anya[1]))
            // A relative has no tasks.
            val valya = CalendarViews.days(i, d(23), d(24), CalendarFilter(personId = P_VALYA))
            assertEquals(listOf("Для всех"), titles(valya[0]))
            assertEquals(listOf("🎂 Бабушка Валя"), titles(valya[1]))
            val tagged = CalendarViews.days(i, d(23), d(24), CalendarFilter(tagId = tag))
            assertEquals(listOf("Только Ольга", "☐ Диме"), titles(tagged[0]))
            assertEquals(emptyList(), titles(tagged[1]))
            val noTasks = CalendarViews.days(i, d(23), d(23), CalendarFilter(showTasks = false))
            assertEquals(listOf("🎂 Аня", "С Аней", "Для всех", "Только Ольга"), titles(noTasks[0]))
        }

    @Test
    fun `CAL-019 upcoming leaves out ended events and shows at most five`() =
        runTest {
            val i =
                inputs(
                    events =
                        listOf(
                            id() to timedEvent("Утром", d(21), t(9), t(10)),
                            id() to timedEvent("Днём", d(21), t(15), t(16)),
                            id() to allDayEvent("Отпуск", d(20), d(23)),
                            id() to timedEvent("Через неделю", d(28), t(10), t(11)),
                            id() to timedEvent("Позже", d(29), t(10), t(11)),
                        ),
                    tasks = listOf(id() to TaskData("Задача", OLGA, dueDate = d(21))),
                )
            val upcoming = CalendarViews.upcoming(i)
            val shown =
                upcoming.items.map {
                    it.date to
                        when (val item = it.item) {
                            is CalendarItem.Event -> item.occ.title
                            is CalendarItem.Birthday -> item.person.data.name
                            is CalendarItem.Task -> item.view.data.title
                        }
                }
            assertEquals(
                listOf(d(21) to "Отпуск", d(21) to "Днём", d(23) to "Аня", d(24) to "Бабушка Валя", d(28) to "Через неделю"),
                shown,
            )
            assertEquals(0, upcoming.more)
            val more = CalendarViews.upcoming(i.copy(events = i.events + (id() to timedEvent("Ещё", d(22), t(10), t(11)))))
            assertEquals(1, more.more)
        }

    @Test
    fun `TAG-004 CAL-005 next occurrences for a tag and a person's dates`() =
        runTest {
            val tag = id()
            val dance = id()
            val anniversary = id()
            val i =
                inputs(
                    events =
                        listOf(
                            dance to timedEvent("Танцы", d(12), t(17), t(18)).copy(recurrence = weekly, tagIds = setOf(tag)),
                            id() to timedEvent("Собрание", d(30), t(18), t(19)).copy(tagIds = setOf(tag)),
                            id() to timedEvent("Прошло", d(1), t(18), t(19)).copy(tagIds = setOf(tag)),
                            id() to timedEvent("Без тега", d(22), t(18), t(19)),
                            anniversary to
                                EventData(
                                    "Годовщина",
                                    OLGA,
                                    LocalDate(2010, 10, 10),
                                    LocalDate(2010, 10, 10),
                                    allDay = true,
                                    participantIds = setOf(P_OLGA, P_DIMA),
                                    recurrence = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.YEAR, 1),
                                    kind = EventKind.MEMORABLE_DATE,
                                    countYears = true,
                                ),
                        ),
                )
            // Today's Wednesday 17:00 has not ended yet.
            assertEquals(
                listOf("Танцы" to d(21), "Собрание" to d(30)),
                CalendarViews.forTag(i, tag).map { it.occ.title to it.occ.startDate },
            )
            val olga = CalendarViews.forPerson(i, P_OLGA)
            assertEquals(LocalDate(2027, 3, 1), olga.birthday?.date)
            assertEquals(42, olga.birthday?.age)
            assertEquals(listOf(MemorableDateView(anniversary, "Годовщина", LocalDate(2027, 10, 10), 17)), olga.memorable)
            assertEquals(PersonDates(null, emptyList()), CalendarViews.forPerson(i, P_ANYA).copy(birthday = null))
            assertNull(CalendarViews.forPerson(i, P_DIMA).birthday)
        }

    @Test
    fun `CAL-006 CAL-014 detail with my reminder and changed occurrences`() =
        runTest {
            val e = id()
            val event =
                timedEvent("Танцы", d(19), t(17), t(18), setOf(P_ANYA, "unknown-person")).copy(
                    recurrence = weekly,
                    reminderMinutesBefore = 30,
                    tagIds = setOf("unknown-tag"),
                )
            val rows =
                listOf(
                    EventOccurrenceData(e, d(26), cancelled = true),
                    EventOccurrenceData(e, d(28), title = "Танцы (зал 2)"),
                    EventOccurrenceData(e, d(2, 11)),
                )
            val i = inputs(events = listOf(e to event), rows = rows)
            val detail = CalendarViews.detail(i, e)!!
            assertEquals(d(21), detail.occ?.occurrence)
            assertEquals(listOf("Аня"), detail.participants.map { it.data.name })
            assertEquals(emptyList(), detail.tags)
            assertEquals("Ольга", detail.creator?.name)
            assertEquals(2, detail.changedCount)
            assertTrue(detail.canEdit)
            // Ольга is not a participant: no reminder unless she sets one.
            assertNull(detail.myReminder)
            assertFalse(detail.ownReminderSet)
            val own = i.copy(myReminders = listOf(EventReminderData(e, OLGA, 60)))
            assertEquals(60, CalendarViews.detail(own, e)!!.myReminder)
            assertTrue(CalendarViews.detail(own, e)!!.ownReminderSet)
            // A given occurrence; a cancelled one falls back to the next.
            assertEquals("Танцы (зал 2)", CalendarViews.detail(i, e, d(28))!!.occ?.title)
            assertEquals(d(21), CalendarViews.detail(i, e, d(26))!!.occ?.occurrence)
        }

    @Test
    fun `CAL-006 a participant gets the event reminder unless their own row says otherwise`() =
        runTest {
            val e = id()
            val event = timedEvent("Танцы", d(23), t(17), t(18), setOf(P_ANYA)).copy(reminderMinutesBefore = 30)
            val i = inputs(events = listOf(e to event), login = ANYA, kid = true)
            assertEquals(30, CalendarViews.detail(i, e)!!.myReminder)
            val none = i.copy(myReminders = listOf(EventReminderData(e, ANYA, null)))
            assertNull(CalendarViews.detail(none, e)!!.myReminder)
            assertTrue(CalendarViews.detail(none, e)!!.ownReminderSet)
            // «Как в событии» writes the default: not an own choice.
            val same = i.copy(myReminders = listOf(EventReminderData(e, ANYA, 30)))
            assertFalse(CalendarViews.detail(same, e)!!.ownReminderSet)
        }

    @Test
    fun `CAL-002 kids cannot edit others' events and unknown ids are dropped`() =
        runTest {
            val mine = id()
            val theirs = id()
            val i =
                inputs(
                    events =
                        listOf(
                            mine to timedEvent("Моё", d(23), t(10), t(11), createdBy = ANYA),
                            theirs to timedEvent("Мамино", d(23), t(12), t(13), setOf("gone", P_ANYA)),
                        ),
                    login = ANYA,
                    kid = true,
                )
            assertTrue(CalendarViews.detail(i, mine)!!.canEdit)
            assertFalse(CalendarViews.detail(i, theirs)!!.canEdit)
            val item =
                CalendarViews
                    .days(
                        i,
                        d(23),
                        d(23),
                    )[0]
                    .items
                    .filterIsInstance<CalendarItem.Event>()
                    .single { it.occ.eventId == theirs }
            assertEquals(listOf(P_ANYA), item.participants.map { it.id })
        }

    @Test
    fun `CAL-017 nothing without calendar view`() =
        runTest {
            f.family()
            f.loginAs(OLGA, app.lares.contract.permissions.Permission.TASKS_VIEW)
            val i = f.inputs().copy(events = listOf(id() to timedEvent("X", d(23), t(10), t(11))))
            assertEquals(emptyList(), CalendarViews.days(i, d(23), d(24), CalendarFilter(showTasks = false)).flatMap { it.items })
            assertNull(CalendarViews.detail(i, i.events[0].first))
        }
}
