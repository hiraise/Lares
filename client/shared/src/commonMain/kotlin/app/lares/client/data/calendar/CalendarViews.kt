package app.lares.client.data.calendar

import app.lares.client.data.Person
import app.lares.client.data.tasks.TaskFilter
import app.lares.client.data.tasks.TaskView
import app.lares.client.data.tasks.TaskViews
import app.lares.contract.calendar.Birthdays
import app.lares.contract.calendar.EventOccurrence
import app.lares.contract.calendar.EventSchedule
import app.lares.contract.entities.EventData
import app.lares.contract.entities.EventKind
import app.lares.contract.entities.EventOccurrenceData
import app.lares.contract.entities.PaletteColor
import app.lares.contract.permissions.EventRules
import app.lares.contract.permissions.Permission
import app.lares.contract.tasks.TaskGroup
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Pure view logic of the calendar (architecture/calendar.md § Models and views, CAL-004, CAL-008, CAL-009, CAL-013,
 * CAL-019, TAG-003, TAG-004); no database, no clock. Without `calendar.view` every view is empty.
 */
object CalendarViews {
    const val MONTH_CELLS = 42
    const val MAX_DOTS = 3
    const val UPCOMING_DAYS = 7
    const val UPCOMING_MAX = 5
    const val TAG_DAYS = 365

    /** Blocks shorter than this count as this long in the week grid (overlap and height). */
    const val MIN_BLOCK_MINUTES = 30
    private const val DAY_MINUTES = 24 * 60

    /**
     * Every date of `[from, to]` with its items: birthdays, memorable dates, all-day events (by title), then timed events
     * and timed tasks by time, then untimed and overdue tasks. Multi-day occurrences show on every day they cover.
     */
    fun days(
        inputs: CalendarInputs,
        from: LocalDate,
        to: LocalDate,
        filter: CalendarFilter = CalendarFilter(),
    ): List<DayView> {
        if (to < from) return emptyList()
        val byDay = mutableMapOf<LocalDate, MutableList<Entry>>()

        fun add(
            date: LocalDate,
            entry: Entry,
        ) {
            if (date in from..to) byDay.getOrPut(date) { mutableListOf() } += entry
        }
        if (inputs.seesCalendar()) {
            val rows = inputs.occurrenceRows.groupBy { it.eventId }
            for ((id, event) in inputs.events) {
                if (filter.tagId != null && filter.tagId !in event.tagIds) continue
                for (occ in EventSchedule.occurrences(id, event, rows[id].orEmpty(), from, to)) {
                    if (!matchesPerson(occ.participantIds, filter.personId)) continue
                    val last = EventSchedule.lastDay(occ)
                    var day = maxOf(occ.startDate, from)
                    val end = minOf(last, to)
                    while (day <= end) {
                        val item = eventItem(inputs, event, occ, day)
                        add(day, Entry(eventRank(event, occ), eventTime(occ, day), occ.title, occ.eventId, item))
                        day = day.plus(1, DateTimeUnit.DAY)
                    }
                }
            }
            if (filter.tagId == null) {
                for (person in inputs.persons) {
                    if (filter.personId != null && person.id != filter.personId) continue
                    birthdaysIn(person, from, to).forEach { add(it.date, Entry(RANK_BIRTHDAY, null, person.data.name, person.id, it)) }
                }
            }
        }
        if (filter.showTasks) {
            val today = inputs.today()
            for (view in taskRows(inputs)) {
                if (!matchesTask(inputs, view, filter)) continue
                val overdue = view.group == TaskGroup.OVERDUE
                val date = if (overdue || view.isDoneTodayRow) today else view.state.occurrence ?: continue
                val time = if (overdue) null else view.data.dueTime
                val rank =
                    when {
                        overdue -> RANK_OVERDUE_TASK
                        time != null -> RANK_TIMED
                        else -> RANK_UNTIMED_TASK
                    }
                add(date, Entry(rank, time, view.data.title, view.id, CalendarItem.Task(view, overdue)))
            }
        }
        val result = ArrayList<DayView>(from.daysUntil(to) + 1)
        var date = from
        while (date <= to) {
            result += DayView(date, byDay[date].orEmpty().sortedWith(ENTRY_ORDER).map { it.item })
            date = date.plus(1, DateTimeUnit.DAY)
        }
        return result
    }

    /** The 42 cells of a month grid from the Monday on or before day 1 (CAL-008); tasks give no dots (CAL-009). */
    fun month(
        inputs: CalendarInputs,
        year: Int,
        month: Int,
        filter: CalendarFilter = CalendarFilter(),
    ): List<MonthCell> {
        val first = LocalDate(year, month, 1)
        val start = mondayOnOrBefore(first)
        val today = inputs.today()
        val days = days(inputs, start, start.plus(MONTH_CELLS - 1, DateTimeUnit.DAY), filter.copy(showTasks = false))
        return days.map { day ->
            val colors = LinkedHashSet<PaletteColor?>()
            for (item in day.items) {
                when (item) {
                    is CalendarItem.Event -> {
                        if (item.occ.participantIds.isEmpty() || item.participants.isEmpty()) {
                            colors += null
                        } else {
                            item.participants.forEach { colors += it.color }
                        }
                    }

                    is CalendarItem.Birthday -> {
                        colors += item.person.color
                    }

                    is CalendarItem.Task -> {}
                }
            }
            MonthCell(
                date = day.date,
                inMonth = day.date.month == first.month && day.date.year == year,
                today = day.date == today,
                dots = colors.take(MAX_DOTS),
                more = colors.size > MAX_DOTS,
            )
        }
    }

    /**
     * Seven days from [monday] (CAL-008, Q-024): all-day items in the strip, timed events as blocks clipped to the day.
     * Blocks are sorted by start and longest first; a cluster is a run of transitively overlapping blocks; each block
     * takes the lowest free column and `columns` is the number of columns its cluster uses. Tasks are left out.
     */
    fun week(
        inputs: CalendarInputs,
        monday: LocalDate,
        filter: CalendarFilter = CalendarFilter(),
    ): List<WeekDay> =
        days(inputs, monday, monday.plus(6, DateTimeUnit.DAY), filter.copy(showTasks = false)).map { day ->
            val allDay = mutableListOf<CalendarItem>()
            val timed = mutableListOf<Pair<CalendarItem.Event, IntRange>>()
            for (item in day.items) {
                if (item is CalendarItem.Event && !item.occ.allDay) {
                    val start = if (day.date == item.occ.startDate) item.occ.startTime!!.minutes() else 0
                    val rawEnd = if (day.date == item.occ.endDate) item.occ.endTime!!.minutes() else DAY_MINUTES
                    val end = maxOf(rawEnd, start + MIN_BLOCK_MINUTES).coerceAtMost(DAY_MINUTES)
                    timed += item to (start until end)
                } else {
                    allDay += item
                }
            }
            WeekDay(day.date, allDay, layout(timed))
        }

    /** Today «Скоро» (CAL-019): events, birthdays and memorable dates of today … today + 7; ended ones left out. */
    fun upcoming(inputs: CalendarInputs): Upcoming {
        val today = inputs.today()
        val seen = mutableSetOf<Pair<String, LocalDate>>()
        val items = mutableListOf<DatedItem>()
        for (day in days(inputs, today, today.plus(UPCOMING_DAYS, DateTimeUnit.DAY), CalendarFilter(showTasks = false))) {
            for (item in day.items) {
                if (item is CalendarItem.Event) {
                    if (!seen.add(item.occ.eventId to item.occ.occurrence)) continue
                    if (EventSchedule.endMoment(item.occ, inputs.zone) <= inputs.now) continue
                }
                items += DatedItem(day.date, item)
            }
        }
        return Upcoming(items.take(UPCOMING_MAX), (items.size - UPCOMING_MAX).coerceAtLeast(0), today)
    }

    /** The tag page «События» (TAG-004): the next occurrence of each event with the tag within 365 days, in display order. */
    fun forTag(
        inputs: CalendarInputs,
        tagId: String,
    ): List<CalendarItem.Event> {
        if (!inputs.seesCalendar()) return emptyList()
        val limit = inputs.today().plus(TAG_DAYS, DateTimeUnit.DAY)
        val rows = inputs.occurrenceRows.groupBy { it.eventId }
        return inputs.events
            .filter { tagId in it.second.tagIds }
            .mapNotNull { (id, event) ->
                EventSchedule.next(id, event, rows[id].orEmpty(), inputs.now, inputs.zone)?.takeIf { it.startDate <= limit }?.let {
                    event to
                        it
                }
            }.sortedWith(compareBy(EventSchedule.ORDER) { it.second })
            .map { (event, occ) -> eventItem(inputs, event, occ, occ.startDate) }
    }

    /** The person page (CAL-005): the next birthday and the memorable dates the person takes part in, nearest first. */
    fun forPerson(
        inputs: CalendarInputs,
        personId: String,
    ): PersonDates {
        if (!inputs.seesCalendar()) return PersonDates(null, emptyList(), inputs.today())
        val today = inputs.today()
        val person = inputs.persons.firstOrNull { it.id == personId }
        val birthday = person?.let { birthdaysIn(it, today, today.plus(DatePeriod(years = 1))).firstOrNull() }
        val rows = inputs.occurrenceRows.groupBy { it.eventId }
        val memorable =
            inputs.events
                .filter { (_, event) -> event.kind == EventKind.MEMORABLE_DATE && personId in event.participantIds }
                .mapNotNull { (id, event) ->
                    EventSchedule.next(id, event, rows[id].orEmpty(), inputs.now, inputs.zone)?.let {
                        MemorableDateView(id, it.title, it.startDate, Birthdays.years(event, it.occurrence))
                    }
                }.sortedWith(compareBy({ it.next }, { it.title }, { it.eventId }))
        return PersonDates(birthday, memorable, today)
    }

    /**
     * The event page of [eventId] (CAL-014) at [occurrence], or at the next occurrence when [occurrence] is null, not
     * scheduled or cancelled; a series without one left falls back to its first date. Null if the event is unknown.
     */
    fun detail(
        inputs: CalendarInputs,
        eventId: String,
        occurrence: LocalDate? = null,
    ): EventDetail? {
        if (!inputs.seesCalendar()) return null
        val event = inputs.events.firstOrNull { it.first == eventId }?.second ?: return null
        val rows = inputs.occurrenceRows.filter { it.eventId == eventId }
        val occ =
            occurrence?.let { EventSchedule.occurrence(eventId, event, rows, it) }
                ?: EventSchedule.next(eventId, event, rows, inputs.now, inputs.zone)
                ?: EventSchedule.occurrence(eventId, event, rows, event.startDate)
        val viewer = inputs.viewer
        val own = viewer?.let { me -> inputs.myReminders.firstOrNull { it.eventId == eventId && it.memberId == me.memberId } }
        val reminderOcc = occ ?: naturalOccurrence(eventId, event)
        val myReminder = viewer?.let { EventRules.myReminder(it, event, reminderOcc, own) }
        val eventDefault = viewer?.let { EventRules.myReminder(it, event, reminderOcc, null) }
        return EventDetail(
            id = eventId,
            event = event,
            occ = occ,
            participants = personsOf(inputs, occ?.participantIds ?: event.participantIds),
            tags = inputs.tags.filter { it.id in event.tagIds },
            creator = inputs.members.firstOrNull { it.id == event.createdBy },
            rule = event.recurrence,
            years = occ?.let { Birthdays.years(event, it.occurrence) },
            myReminder = myReminder,
            ownReminderSet = own != null && own.minutesBefore != eventDefault,
            eventReminder = eventDefault,
            canEdit = viewer?.let { EventRules.canEdit(it, event) } ?: false,
            changedCount = rows.count { !it.isEmpty() },
        )
    }

    /** True if [row] changes or cancels its occurrence (a reset row has no effect). */
    fun EventOccurrenceData.isEmpty(): Boolean =
        !cancelled &&
            startDate == null &&
            endDate == null &&
            startTime == null &&
            endTime == null &&
            title == null &&
            place == null &&
            notes == null &&
            participantIds == null

    fun mondayOnOrBefore(date: LocalDate): LocalDate =
        date.minus(date.dayOfWeek.isoDayNumber - DayOfWeek.MONDAY.isoDayNumber, DateTimeUnit.DAY)

    // --- helpers ---

    private data class Entry(
        val rank: Int,
        val time: LocalTime?,
        val title: String,
        val id: String,
        val item: CalendarItem,
    )

    private const val RANK_BIRTHDAY = 0
    private const val RANK_MEMORABLE = 1
    private const val RANK_ALL_DAY = 2
    private const val RANK_TIMED = 3
    private const val RANK_OVERDUE_TASK = 4
    private const val RANK_UNTIMED_TASK = 5

    // Timed events before timed tasks at the same minute.
    private val ENTRY_ORDER: Comparator<Entry> =
        compareBy<Entry>({ it.rank }, { it.time }, { it.item is CalendarItem.Task }, { it.title }, { it.id })

    private fun eventRank(
        event: EventData,
        occ: EventOccurrence,
    ): Int =
        when {
            event.kind == EventKind.MEMORABLE_DATE -> RANK_MEMORABLE
            occ.allDay -> RANK_ALL_DAY
            else -> RANK_TIMED
        }

    /** A timed occurrence continuing from an earlier day sorts at 00:00. */
    private fun eventTime(
        occ: EventOccurrence,
        day: LocalDate,
    ): LocalTime? =
        when {
            occ.allDay -> null
            day == occ.startDate -> occ.startTime
            else -> LocalTime(0, 0)
        }

    private fun eventItem(
        inputs: CalendarInputs,
        event: EventData,
        occ: EventOccurrence,
        day: LocalDate,
    ) = CalendarItem.Event(
        occ = occ,
        kind = event.kind,
        participants = personsOf(inputs, occ.participantIds),
        tags = inputs.tags.filter { it.id in event.tagIds },
        repeating = event.kind == EventKind.EVENT && event.recurrence != null,
        years = Birthdays.years(event, occ.occurrence),
        continuesBefore = day > occ.startDate,
        continuesAfter = day < EventSchedule.lastDay(occ),
    )

    /** Known persons among [ids], in the persons' order (by name). */
    private fun personsOf(
        inputs: CalendarInputs,
        ids: Set<String>,
    ): List<Person> = if (ids.isEmpty()) emptyList() else inputs.persons.filter { it.id in ids }

    private fun birthdaysIn(
        person: Person,
        from: LocalDate,
        to: LocalDate,
    ): List<CalendarItem.Birthday> {
        val month = person.data.birthMonth ?: return emptyList()
        val day = person.data.birthDay ?: return emptyList()
        return (from.year..to.year)
            .map { Birthdays.dateIn(it, month, day) }
            .filter { it in from..to }
            .map { CalendarItem.Birthday(person, it, Birthdays.age(person.data.birthYear, it)) }
    }

    /** An event of the whole family counts for every person. */
    private fun matchesPerson(
        participants: Set<String>,
        personId: String?,
    ): Boolean = personId == null || participants.isEmpty() || personId in participants

    /** Person: tasks assigned to their member or to "anyone" (none for a relative without a member); tag: the task's tags. */
    private fun matchesTask(
        inputs: CalendarInputs,
        view: TaskView,
        filter: CalendarFilter,
    ): Boolean {
        if (filter.tagId != null && filter.tagId !in view.data.tagIds) return false
        val personId = filter.personId ?: return true
        val member = inputs.members.firstOrNull { it.data.personId == personId } ?: return false
        return view.data.assigneeIds.isEmpty() || member.id in view.data.assigneeIds
    }

    /** Open tasks plus "done today" rows; tasks without a date are left out by [days]. */
    private fun taskRows(inputs: CalendarInputs): List<TaskView> {
        val viewer = inputs.viewer ?: return emptyList()
        if (!viewer.permissions.has(Permission.TASKS_VIEW)) return emptyList()
        return TaskViews.filter(inputs.tasks, TaskFilter(), viewer.memberId)
    }

    private fun layout(timed: List<Pair<CalendarItem.Event, IntRange>>): List<TimedBlock> {
        val sorted = timed.sortedWith(compareBy({ it.second.first }, { -(it.second.last - it.second.first) }))
        val result = mutableListOf<TimedBlock>()
        var cluster = mutableListOf<Pair<Pair<CalendarItem.Event, IntRange>, Int>>()
        var columnEnds = mutableListOf<Int>()
        var clusterEnd = -1

        fun flush() {
            val width = columnEnds.size
            cluster.forEach { (block, column) ->
                result += TimedBlock(block.first, block.second.first, block.second.last + 1, column, width)
            }
            cluster = mutableListOf()
            columnEnds = mutableListOf()
        }
        for (block in sorted) {
            val start = block.second.first
            val end = block.second.last + 1
            if (start >= clusterEnd) flush()
            var column = columnEnds.indexOfFirst { it <= start }
            if (column < 0) {
                column = columnEnds.size
                columnEnds += end
            } else {
                columnEnds[column] = end
            }
            cluster += block to column
            clusterEnd = maxOf(clusterEnd, end)
        }
        flush()
        return result
    }

    /** The occurrence on the series' own dates, for rules that only need its participants. */
    private fun naturalOccurrence(
        eventId: String,
        event: EventData,
    ) = EventOccurrence(
        eventId,
        event.startDate,
        event.startDate,
        event.endDate,
        event.startTime,
        event.endTime,
        event.title,
        event.place,
        event.notes,
        event.participantIds,
        changed = false,
    )

    private fun LocalTime.minutes(): Int = hour * 60 + minute

    private fun CalendarInputs.today(): LocalDate = now.toLocalDateTime(zone).date

    private fun CalendarInputs.seesCalendar(): Boolean = viewer?.permissions?.has(Permission.CALENDAR_VIEW) == true
}
