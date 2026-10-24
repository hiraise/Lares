package app.lares.client.ui

import app.lares.client.data.tasks.TaskCompletion
import app.lares.client.data.tasks.TaskView
import app.lares.client.ui.common.UiText
import app.lares.client.ui.common.load
import app.lares.client.ui.tasks.MetaKind
import app.lares.client.ui.tasks.TasksLogic
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import app.lares.contract.entities.TaskCompletionData
import app.lares.contract.entities.TaskData
import app.lares.contract.tasks.TaskGroup
import app.lares.contract.tasks.TaskState
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class TasksLogicTest {
    private val today = LocalDate(2026, 10, 21) // Wednesday
    private val zone = TimeZone.of("Europe/Moscow")
    private val start = LocalDate(2026, 10, 15)

    @AfterTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    /** [text] in English and in Russian. */
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

    private fun fixed(
        unit: RecurrenceUnit,
        interval: Int = 1,
        vararg days: DayOfWeek,
    ) = Recurrence(RecurrenceMode.FIXED, unit, interval, days.toSet())

    @Test
    fun `fixed rule summaries`() {
        assertTexts("Every day", "Каждый день", TasksLogic.rule(fixed(RecurrenceUnit.DAY), start))
        assertTexts("Every 3 days", "Раз в 3 дня", TasksLogic.rule(fixed(RecurrenceUnit.DAY, 3), start))
        assertTexts("Every 5 days", "Раз в 5 дней", TasksLogic.rule(fixed(RecurrenceUnit.DAY, 5), start))
        assertTexts("Every 21 days", "Раз в 21 день", TasksLogic.rule(fixed(RecurrenceUnit.DAY, 21), start))
        assertTexts(
            "Every Saturday",
            "По субботам",
            TasksLogic.rule(fixed(RecurrenceUnit.WEEK, 1, DayOfWeek.SATURDAY), start),
        )
        assertTexts("Every Thursday", "По четвергам", TasksLogic.rule(fixed(RecurrenceUnit.WEEK), start))
        assertTexts(
            "Every Mon and Thu",
            "По пн и чт",
            TasksLogic.rule(fixed(RecurrenceUnit.WEEK, 1, DayOfWeek.THURSDAY, DayOfWeek.MONDAY), start),
        )
        assertTexts(
            "Every Mon, Wed and Fri",
            "По пн, ср и пт",
            TasksLogic.rule(fixed(RecurrenceUnit.WEEK, 1, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), start),
        )
        assertTexts(
            "Every day",
            "Каждый день",
            TasksLogic.rule(fixed(RecurrenceUnit.WEEK, 1, *DayOfWeek.entries.toTypedArray()), start),
        )
        assertTexts(
            "Every 2 weeks on Tue and Sat",
            "Раз в 2 недели: вт и сб",
            TasksLogic.rule(fixed(RecurrenceUnit.WEEK, 2, DayOfWeek.TUESDAY, DayOfWeek.SATURDAY), start),
        )
        assertTexts("Every month on day 15", "Каждый месяц, 15 числа", TasksLogic.rule(fixed(RecurrenceUnit.MONTH), start))
        assertTexts(
            "Every 6 months on day 15",
            "Раз в 6 месяцев, 15 числа",
            TasksLogic.rule(fixed(RecurrenceUnit.MONTH, 6), start),
        )
        assertTexts(
            "Every year on October 15",
            "Каждый год, 15 октября",
            TasksLogic.rule(fixed(RecurrenceUnit.YEAR), start),
        )
        assertTexts(
            "Every 2 years on October 15",
            "Раз в 2 года, 15 октября",
            TasksLogic.rule(fixed(RecurrenceUnit.YEAR, 2), start),
        )
    }

    @Test
    fun `after-completion rule summaries`() {
        fun after(
            unit: RecurrenceUnit,
            n: Int,
        ) = TasksLogic.rule(Recurrence(RecurrenceMode.AFTER_COMPLETION, unit, n), start)
        assertTexts("Every day after completion", "Раз в день после выполнения", after(RecurrenceUnit.DAY, 1))
        assertTexts("Every 2 days after completion", "Раз в 2 дня после выполнения", after(RecurrenceUnit.DAY, 2))
        assertTexts("Every week after completion", "Раз в неделю после выполнения", after(RecurrenceUnit.WEEK, 1))
        assertTexts("Every 5 weeks after completion", "Раз в 5 недель после выполнения", after(RecurrenceUnit.WEEK, 5))
        assertTexts("Every month after completion", "Раз в месяц после выполнения", after(RecurrenceUnit.MONTH, 1))
        assertTexts("Every 3 months after completion", "Раз в 3 месяца после выполнения", after(RecurrenceUnit.MONTH, 3))
        assertTexts("Every year after completion", "Раз в год после выполнения", after(RecurrenceUnit.YEAR, 1))
        assertTexts("Every 5 years after completion", "Раз в 5 лет после выполнения", after(RecurrenceUnit.YEAR, 5))
    }

    @Test
    fun `rule with time`() {
        val task = TaskData("Trash", "m1", dueDate = start, dueTime = LocalTime(20, 0), recurrence = fixed(RecurrenceUnit.DAY))
        assertTexts("Every day · by 20:00", "Каждый день · до 20:00", TasksLogic.ruleWithTime(task))
        assertNull(TasksLogic.ruleWithTime(task.copy(recurrence = null)))
    }

    @Test
    fun `relative days around today`() {
        assertTexts("Today", "Сегодня", TasksLogic.day(today, today))
        assertTexts("Tomorrow", "Завтра", TasksLogic.day(LocalDate(2026, 10, 22), today))
        assertTexts("Yesterday", "Вчера", TasksLogic.day(LocalDate(2026, 10, 20), today))
        assertTexts("Saturday, October 24", "Суббота, 24 октября", TasksLogic.day(LocalDate(2026, 10, 24), today))
        assertTexts("Tuesday, October 27", "Вторник, 27 октября", TasksLogic.day(LocalDate(2026, 10, 27), today))
        assertTexts("October 28", "28 октября", TasksLogic.day(LocalDate(2026, 10, 28), today))
        assertTexts("October 14", "14 октября", TasksLogic.day(LocalDate(2026, 10, 14), today))
        assertTexts("January 5, 2027", "5 января 2027", TasksLogic.day(LocalDate(2027, 1, 5), today))
        assertTexts("Today, 17:30", "Сегодня, 17:30", TasksLogic.due(today, LocalTime(17, 30), today))
        assertTexts("Tomorrow, 9:05", "Завтра, 9:05", TasksLogic.due(LocalDate(2026, 10, 22), LocalTime(9, 5), today))
        // Around midnight: the day changes with the date, not with 24 hours.
        val lateYesterday = LocalDate(2026, 10, 20)
        assertTexts("Yesterday, 23:59", "Вчера, 23:59", TasksLogic.due(lateYesterday, LocalTime(23, 59), today))
        assertTexts("Today, 0:00", "Сегодня, 0:00", TasksLogic.due(today, LocalTime(0, 0), today))
    }

    @Test
    fun `overdue texts`() {
        assertTexts("Yesterday, 21:00", "Вчера, 21:00", TasksLogic.overdue(LocalDate(2026, 10, 20), LocalTime(21, 0), today))
        assertTexts("Today, 9:00", "Сегодня, 9:00", TasksLogic.overdue(today, LocalTime(9, 0), today))
        assertTexts("Yesterday", "Вчера", TasksLogic.overdue(LocalDate(2026, 10, 20), null, today))
        assertTexts("Overdue since Oct 19", "Просрочено с 19 окт.", TasksLogic.overdue(LocalDate(2026, 10, 19), LocalTime(21, 0), today))
        assertTexts("Overdue since May 3", "Просрочено с 3 мая", TasksLogic.overdue(LocalDate(2026, 5, 3), null, today))
    }

    @Test
    fun `reminder labels`() {
        assertTexts("At the due time", "В момент срока", TasksLogic.reminder(0, hasTime = true))
        assertTexts("On the day at 9:00", "В день задачи, в 9:00", TasksLogic.reminder(0, hasTime = false))
        assertTexts("15 minutes before", "За 15 минут", TasksLogic.reminder(15, hasTime = true))
        assertTexts("1 minute before", "За 1 минуту", TasksLogic.reminder(1, hasTime = true))
        assertTexts("1 hour before", "За 1 час", TasksLogic.reminder(60, hasTime = true))
        assertTexts("2 hours before", "За 2 часа", TasksLogic.reminder(120, hasTime = true))
        assertTexts("90 minutes before", "За 90 минут", TasksLogic.reminder(90, hasTime = true))
        assertTexts("1 day before", "За 1 день", TasksLogic.reminder(1440, hasTime = false))
        assertTexts("7 days before", "За 7 дней", TasksLogic.reminder(7 * 1440, hasTime = false))
    }

    @Test
    fun `meta lines, status and history`() {
        val names = mapOf("m1" to "Olga", "m3" to "Anya")
        val plain = view(TaskData("Pay", "m1", dueDate = LocalDate(2026, 10, 24)), TaskGroup.UPCOMING)
        assertEquals(MetaKind.PLAIN, TasksLogic.meta(plain, today, zone, names).kind)
        assertTexts("Saturday, October 24", "Суббота, 24 октября", TasksLogic.meta(plain, today, zone, names).text)

        val late =
            view(TaskData("Pay", "m1", dueDate = LocalDate(2026, 10, 20), dueTime = LocalTime(21, 0)), TaskGroup.OVERDUE, overdue = true)
        val lateMeta = TasksLogic.meta(late, today, zone, names)
        assertEquals(MetaKind.OVERDUE, lateMeta.kind)
        assertTexts("Yesterday, 21:00", "Вчера, 21:00", lateMeta.text)
        assertTexts("Overdue · Yesterday, 21:00", "Просрочено · Вчера, 21:00", TasksLogic.status(late, today, zone, names))

        val routine =
            view(
                TaskData("Trash", "m1", dueDate = start, dueTime = LocalTime(20, 0), recurrence = fixed(RecurrenceUnit.DAY)),
                TaskGroup.TODAY,
            )
        assertEquals(MetaKind.ROUTINE, TasksLogic.meta(routine, today, zone, names).kind)

        val notes = view(TaskData("Call", "m1", notes = "\nLeak on the balcony\nsecond line"), TaskGroup.NO_DATE, occurrence = null)
        assertTexts("Leak on the balcony", "Leak on the balcony", TasksLogic.meta(notes, today, zone, names).text)
        assertNull(TasksLogic.meta(view(TaskData("X", "m1"), TaskGroup.NO_DATE, occurrence = null), today, zone, names).text)

        // 06:12 UTC = 09:12 Moscow.
        val completion = TaskCompletion("c1", TaskCompletionData("t", "m3", Instant.parse("2026-10-21T06:12:00Z"), today))
        val doneToday = view(TaskData("Dishes", "m1"), TaskGroup.TODAY, occurrence = null, completedToday = completion)
        assertTexts("Anya · at 9:12", "Anya · в 9:12", TasksLogic.meta(doneToday, today, zone, names).text)

        val done =
            view(TaskData("Dishes", "m1"), TaskGroup.DONE, occurrence = null).let {
                it.copy(state = TaskState(null, done = true, doneBy = completion.data))
            }
        assertTexts("Done: Anya, Oct 21, 9:12", "Выполнено: Anya, 21 окт., 9:12", TasksLogic.status(done, today, zone, names))

        assertTexts("Anya · Wed, Oct 21, 9:12", "Anya · ср, 21 окт., 9:12", TasksLogic.history(completion, zone, names))
        val skip = completion.copy(data = completion.data.copy(completedBy = "m1", skipped = true))
        assertTexts("Skipped · Olga · Wed, Oct 21, 9:12", "Пропущено · Olga · ср, 21 окт., 9:12", TasksLogic.history(skip, zone, names))
    }

    @Test
    fun `next occurrence of fixed routines only`() {
        val task =
            TaskData("Trash", "m1", dueDate = start, recurrence = fixed(RecurrenceUnit.WEEK, 1, DayOfWeek.MONDAY, DayOfWeek.THURSDAY))
        val state = TaskState(LocalDate(2026, 10, 22), done = false, doneBy = null)
        assertEquals(LocalDate(2026, 10, 26), TasksLogic.nextOccurrence(task, state))
        val after = task.copy(recurrence = Recurrence(RecurrenceMode.AFTER_COMPLETION, RecurrenceUnit.MONTH, 3))
        assertNull(TasksLogic.nextOccurrence(after, state))
        assertNull(TasksLogic.nextOccurrence(task.copy(recurrence = null), state))
    }

    private fun view(
        data: TaskData,
        group: TaskGroup,
        overdue: Boolean = false,
        occurrence: LocalDate? = data.dueDate,
        completedToday: TaskCompletion? = null,
    ) = TaskView(
        id = "t",
        data = data,
        state = TaskState(occurrence, done = false, doneBy = null),
        group = group,
        overdue = overdue,
        dueMoment = null,
        assignees = emptyList(),
        tags = emptyList(),
        checklistDone = 0,
        checklistTotal = 0,
        canEdit = true,
        canComplete = true,
        completedToday = completedToday,
    )
}
