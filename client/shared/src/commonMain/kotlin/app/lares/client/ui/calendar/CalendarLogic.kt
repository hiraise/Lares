package app.lares.client.ui.calendar

import app.lares.client.data.Person
import app.lares.client.data.calendar.CalendarItem
import app.lares.client.data.calendar.DatedItem
import app.lares.client.data.tasks.TaskView
import app.lares.client.resources.Res
import app.lares.client.resources.cal_all_day
import app.lares.client.resources.cal_birthday
import app.lares.client.resources.cal_from_date
import app.lares.client.resources.cal_in_days
import app.lares.client.resources.cal_month_year
import app.lares.client.resources.cal_range
import app.lares.client.resources.cal_reminder_all_day_on_day
import app.lares.client.resources.cal_reminder_as_event_suffix
import app.lares.client.resources.cal_reminder_at_start
import app.lares.client.resources.cal_reminder_date_all_day
import app.lares.client.resources.cal_reminder_years
import app.lares.client.resources.cal_rule_until
import app.lares.client.resources.cal_task_meta
import app.lares.client.resources.cal_tomorrow_lower
import app.lares.client.resources.cal_until_date
import app.lares.client.resources.cal_until_time
import app.lares.client.resources.cal_when_all_day
import app.lares.client.resources.cal_when_timed
import app.lares.client.resources.cal_when_yearly
import app.lares.client.resources.cal_whole_family
import app.lares.client.resources.date_day_month
import app.lares.client.resources.date_day_month_year
import app.lares.client.resources.date_with_weekday
import app.lares.client.resources.day_today
import app.lares.client.resources.day_tomorrow
import app.lares.client.resources.months
import app.lares.client.resources.months_standalone
import app.lares.client.resources.person_birthday_in
import app.lares.client.resources.person_birthday_today
import app.lares.client.resources.person_turns
import app.lares.client.resources.person_turns_today
import app.lares.client.resources.task_anyone
import app.lares.client.resources.task_reminder_none
import app.lares.client.resources.weekdays
import app.lares.client.ui.common.UiText
import app.lares.client.ui.common.raw
import app.lares.client.ui.common.uiText
import app.lares.client.ui.tasks.TasksLogic
import app.lares.contract.calendar.EventOccurrence
import app.lares.contract.calendar.EventSchedule
import app.lares.contract.entities.EventKind
import app.lares.contract.entities.Recurrence
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

/** What the time column of an event row shows: [top] in `labelLarge` 600, [bottom] in `bodySmall`. */
data class TimeColumn(
    val top: UiText,
    val bottom: UiText?,
)

/** Pure texts of the Calendar tab and the event page (plan M3-05); resolved with `UiText.text()`. */
object CalendarLogic {
    /** Reminder choices of a timed event, minutes before the start (none is offered separately). */
    val TIMED_REMINDERS = listOf(0, 5, 10, 15, 30, 60, 120, 1440, 2880, 10080)

    /** Reminder choices of an all-day event: 0 = on the day at 9:00, then days before at 9:00. */
    val ALL_DAY_REMINDERS = listOf(0, 1440, 2880, 10080)

    fun reminderOptions(allDay: Boolean): List<Int> = if (allDay) ALL_DAY_REMINDERS else TIMED_REMINDERS

    /** "None", "At the start", "1 hour before"; all-day: "On the day at 9:00", "1 day before at 9:00". */
    fun reminder(
        minutes: Int?,
        allDay: Boolean,
    ): UiText =
        when {
            minutes == null -> uiText(Res.string.task_reminder_none)
            allDay && minutes <= 0 -> uiText(Res.string.cal_reminder_all_day_on_day)
            minutes <= 0 -> uiText(Res.string.cal_reminder_at_start)
            else -> TasksLogic.reminder(minutes, hasTime = !allDay)
        }

    /** The «Напомнить мне» supporting line: the effective choice, «(как в событии)» when the member set nothing. */
    fun myReminder(
        minutes: Int?,
        allDay: Boolean,
        own: Boolean,
    ): UiText {
        val text = reminder(minutes, allDay)
        return if (own) text else uiText(Res.string.cal_reminder_as_event_suffix, text)
    }

    /** "October 2026" / "Октябрь 2026". */
    fun monthTitle(
        year: Int,
        month: Int,
    ): UiText = uiText(Res.string.cal_month_year, UiText.Item(Res.array.months_standalone, month - 1), raw(year.toString()))

    /** The day under the month grid: "Friday, October 23", today "Today, October 23". */
    fun dayHeader(
        date: LocalDate,
        today: LocalDate,
    ): UiText {
        val first = if (date == today) uiText(Res.string.day_today) else weekday(date)
        return uiText(Res.string.date_with_weekday, first, dayMonth(date, today))
    }

    /** A day of «Список»: "Today", "Tomorrow", "Saturday, October 24" (with the year when not this year). */
    fun listHeader(
        date: LocalDate,
        today: LocalDate,
    ): UiText =
        when (today.daysUntil(date)) {
            0 -> uiText(Res.string.day_today)
            1 -> uiText(Res.string.day_tomorrow)
            else -> uiText(Res.string.date_with_weekday, weekday(date), dayMonth(date, today))
        }

    /**
     * The time column of an event row: "17:30" over "18:45"; all-day "All day"; a day of a multi-day occurrence
     * "until July 20" (more days follow) or "from July 10" (its last day). A timed occurrence over several days shows
     * its start on the first day, "All day" in the middle and "until 7:30" on the last day.
     */
    fun timeColumn(
        item: CalendarItem.Event,
        today: LocalDate,
    ): TimeColumn {
        val occ = item.occ
        val until = { uiText(Res.string.cal_until_date, dayMonth(lastDay(item), today)) }
        val from = { uiText(Res.string.cal_from_date, dayMonth(occ.startDate, today)) }
        if (occ.allDay) {
            val bottom =
                when {
                    item.continuesAfter -> until()
                    item.continuesBefore -> from()
                    else -> null
                }
            return TimeColumn(uiText(Res.string.cal_all_day), bottom)
        }
        val start = occ.startTime!!
        val end = occ.endTime!!
        return when {
            !item.continuesBefore && !item.continuesAfter -> TimeColumn(raw(TasksLogic.time(start)), raw(TasksLogic.time(end)))
            !item.continuesBefore -> TimeColumn(raw(TasksLogic.time(start)), until())
            item.continuesAfter -> TimeColumn(uiText(Res.string.cal_all_day), until())
            else -> TimeColumn(uiText(Res.string.cal_until_time, raw(TasksLogic.time(end))), from())
        }
    }

    /** Participant names joined with ", " (or "Whole family"), then " · place". */
    fun eventMeta(
        participants: List<Person>,
        place: String?,
    ): UiText {
        val who = if (participants.isEmpty()) uiText(Res.string.cal_whole_family) else raw(participants.joinToString { it.data.name })
        val where = place?.trim()?.takeIf { it.isNotEmpty() } ?: return who
        return UiText.Concat(listOf(who, raw(where)), " · ")
    }

    /** "Grandma Valya, 78 years" / "Бабушка Валя, 78 лет"; without a birth year "Birthday: Uncle Kolya". */
    fun birthdayTitle(
        name: String,
        age: Int?,
    ): UiText =
        if (age == null) {
            uiText(Res.string.cal_birthday, raw(name))
        } else {
            UiText.Concat(listOf(raw(name), years(age)), ", ")
        }

    /** "Wedding anniversary · 16 years"; just the title without a count. */
    fun memorableTitle(
        title: String,
        years: Int?,
    ): UiText = if (years == null) raw(title) else UiText.Concat(listOf(raw(title), years(years)), " · ")

    /** "1 год", "2 года", "5 лет". */
    fun years(n: Int): UiText = UiText.Plural(Res.plurals.cal_reminder_years, n, listOf(raw(n.toString())))

    /** "Task · Dmitry" (assignees) or "Task · Anyone". */
    fun taskMeta(view: TaskView): UiText {
        val who = if (view.assignees.isEmpty()) uiText(Res.string.task_anyone) else raw(view.assignees.joinToString { it.name })
        return uiText(Res.string.cal_task_meta, who)
    }

    /**
     * When the occurrence is (event page): "Tuesday, October 28 · 16:00–17:00", "Tuesday, October 28 · all day",
     * several days "July 10 – July 20", a timed one over several days "July 10, 22:00 – July 11, 7:30".
     */
    fun detailWhen(
        occ: EventOccurrence,
        today: LocalDate,
    ): UiText {
        val last = EventSchedule.lastDay(occ)
        if (occ.allDay) {
            if (last != occ.startDate) return uiText(Res.string.cal_range, dayMonth(occ.startDate, today), dayMonth(last, today))
            return uiText(Res.string.cal_when_all_day, fullDay(occ.startDate, today))
        }
        val start = raw(TasksLogic.time(occ.startTime!!))
        val end = raw(TasksLogic.time(occ.endTime!!))
        if (occ.endDate != occ.startDate) {
            return uiText(
                Res.string.cal_range,
                uiText(Res.string.date_with_weekday, dayMonth(occ.startDate, today), start),
                uiText(Res.string.date_with_weekday, dayMonth(occ.endDate, today), end),
            )
        }
        return uiText(Res.string.cal_when_timed, fullDay(occ.startDate, today), start, end)
    }

    /** The title of a row outside the Calendar tab: the event title, «Годовщина свадьбы · 16 лет», «Бабушка Валя, 78 лет». */
    fun itemTitle(item: CalendarItem): UiText =
        when (item) {
            is CalendarItem.Event -> {
                if (item.kind ==
                    EventKind.MEMORABLE_DATE
                ) {
                    memorableTitle(item.occ.title, item.years)
                } else {
                    raw(item.occ.title)
                }
            }

            is CalendarItem.Birthday -> {
                birthdayTitle(item.person.data.name, item.age)
            }

            is CalendarItem.Task -> {
                raw(item.view.data.title)
            }
        }

    /**
     * The supporting line of a Today «Скоро» row (CAL-019), relative to the household's [today]: a birthday or memorable
     * date «Понедельник · через 3 дня» («Сегодня», «Завтра»); an event «Сегодня, 16:00 · Клиника на Ленина», «Завтра,
     * весь день», several days «Пятница, до 30 октября», a night continuing from yesterday «Сегодня, до 7:30».
     */
    fun upcomingWhen(
        dated: DatedItem,
        today: LocalDate,
    ): UiText {
        val days = today.daysUntil(dated.date)
        val day = relativeDay(dated.date, today)
        val item = dated.item
        if (item !is CalendarItem.Event || item.kind == EventKind.MEMORABLE_DATE) {
            if (days <= 1) return day
            return UiText.Concat(listOf(day, UiText.Plural(Res.plurals.cal_in_days, days, listOf(raw(days.toString())))), " · ")
        }
        val occ = item.occ
        val last = EventSchedule.lastDay(occ)
        val time =
            when {
                occ.allDay && last > dated.date -> {
                    uiText(
                        Res.string.date_with_weekday,
                        day,
                        uiText(Res.string.cal_until_date, dayMonth(last, today)),
                    )
                }

                occ.allDay -> {
                    uiText(Res.string.cal_reminder_date_all_day, day)
                }

                item.continuesBefore -> {
                    uiText(
                        Res.string.date_with_weekday,
                        day,
                        uiText(Res.string.cal_until_time, raw(TasksLogic.time(occ.endTime!!))),
                    )
                }

                else -> {
                    uiText(Res.string.date_with_weekday, day, raw(TasksLogic.time(occ.startTime!!)))
                }
            }
        return withPlace(time, occ.place)
    }

    /**
     * When the next occurrence is, on the tag page (TAG-004): «Вторник, 28 октября · 16:00», «Вторник, 28 октября ·
     * весь день», several days «10 июля – 20 июля»; a memorable date «12 июня · каждый год».
     */
    fun tagWhen(
        item: CalendarItem.Event,
        today: LocalDate,
    ): UiText {
        val occ = item.occ
        if (item.kind ==
            EventKind.MEMORABLE_DATE
        ) {
            return uiText(Res.string.date_with_weekday, weekday(occ.startDate), dayMonth(occ.startDate, today))
        }
        if (occ.allDay || occ.endDate != occ.startDate) return detailWhen(occ, today)
        return UiText.Concat(listOf(fullDay(occ.startDate, today), raw(TasksLogic.time(occ.startTime!!))), " · ")
    }

    /**
     * The line under the birthday on the person page (CAL-004): «Исполнится 78 · через 3 дня», «Исполнится 78 · завтра»,
     * «Сегодня 78 лет»; without a birth year «День рождения через 3 дня», «Сегодня день рождения».
     */
    fun birthdayCountdown(
        birthday: CalendarItem.Birthday,
        today: LocalDate,
    ): UiText {
        val days = today.daysUntil(birthday.date)
        val age = birthday.age
        if (days <=
            0
        ) {
            return if (age == null) uiText(Res.string.person_birthday_today) else uiText(Res.string.person_turns_today, years(age))
        }
        val whenText =
            if (days ==
                1
            ) {
                uiText(Res.string.cal_tomorrow_lower)
            } else {
                UiText.Plural(Res.plurals.cal_in_days, days, listOf(raw(days.toString())))
            }
        return if (age ==
            null
        ) {
            uiText(Res.string.person_birthday_in, whenText)
        } else {
            uiText(Res.string.person_turns, raw(age.toString()), whenText)
        }
    }

    /** «Сегодня», «Завтра», else the weekday («Понедельник»). */
    fun relativeDay(
        date: LocalDate,
        today: LocalDate,
    ): UiText =
        when (today.daysUntil(date)) {
            0 -> uiText(Res.string.day_today)
            1 -> uiText(Res.string.day_tomorrow)
            else -> weekday(date)
        }

    private fun withPlace(
        text: UiText,
        place: String?,
    ): UiText {
        val where = place?.trim()?.takeIf { it.isNotEmpty() } ?: return text
        return UiText.Concat(listOf(text, raw(where)), " · ")
    }

    /** A memorable date: "June 12 · every year" (never with a year). */
    fun memorableWhen(date: LocalDate): UiText = uiText(Res.string.cal_when_yearly, dayMonth(date, date))

    /** The repeat rule with its end: "Every week on Mon and Wed · until May 31". */
    fun rule(
        rule: Recurrence,
        start: LocalDate,
        until: LocalDate?,
        today: LocalDate,
    ): UiText {
        val summary = TasksLogic.rule(rule, start)
        return if (until == null) summary else uiText(Res.string.cal_rule_until, summary, dayMonth(until, today))
    }

    /** "October 23", with the year when it is not [today]'s year. */
    fun dayMonth(
        date: LocalDate,
        today: LocalDate,
    ): UiText {
        val month = UiText.Item(Res.array.months, date.month.ordinal)
        return if (date.year == today.year) {
            uiText(Res.string.date_day_month, raw(date.day.toString()), month)
        } else {
            uiText(Res.string.date_day_month_year, raw(date.day.toString()), month, raw(date.year.toString()))
        }
    }

    private fun fullDay(
        date: LocalDate,
        today: LocalDate,
    ): UiText = uiText(Res.string.date_with_weekday, weekday(date), dayMonth(date, today))

    private fun weekday(date: LocalDate): UiText = UiText.Item(Res.array.weekdays, date.dayOfWeek.ordinal)

    private fun lastDay(item: CalendarItem.Event): LocalDate = EventSchedule.lastDay(item.occ)
}
