package app.lares.client.data.calendar

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.lares.client.data.HouseholdRepository
import app.lares.client.data.MemberView
import app.lares.client.data.MembersRepository
import app.lares.client.data.PermissionsRepository
import app.lares.client.data.Person
import app.lares.client.data.PersonsRepository
import app.lares.client.data.Tag
import app.lares.client.data.TagsRepository
import app.lares.client.data.minuteTicks
import app.lares.client.data.observeViewer
import app.lares.client.data.tasks.TasksRepository
import app.lares.client.data.tasks.householdZone
import app.lares.client.db.LocalDb
import app.lares.client.sync.toData
import app.lares.contract.entities.EventData
import app.lares.contract.entities.EventOccurrenceData
import app.lares.contract.entities.EventReminderData
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.Viewer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Read side of the calendar (architecture/calendar.md § Models and views): combines the three calendar tables, persons,
 * members, tags, the task views (only with `tasks.view`), the household zone, the member's permissions and a minute
 * ticker, and computes the views with [CalendarViews]. [ticks] is [minuteTicks] of [clock]; tests pass their own.
 */
class CalendarRepository(
    private val localDb: LocalDb,
    private val members: MembersRepository,
    private val persons: PersonsRepository,
    private val tags: TagsRepository,
    private val household: HouseholdRepository,
    private val permissions: PermissionsRepository,
    private val tasks: TasksRepository,
    clock: Clock,
    private val ticks: Flow<Instant> = minuteTicks(clock),
) {
    private val db get() = localDb.db

    private fun rows() =
        combine(
            db.eventQueries
                .selectAll()
                .asFlow()
                .mapToList(localDb.dispatcher),
            db.event_occurrenceQueries
                .selectAll()
                .asFlow()
                .mapToList(localDb.dispatcher),
            db.event_reminderQueries
                .selectAll()
                .asFlow()
                .mapToList(localDb.dispatcher),
        ) { events, occurrences, reminders ->
            Rows(events.map { it.id to it.toData() }, occurrences.map { it.toData() }, reminders.map { it.toData() })
        }

    /** All inputs of the views; a new value on every data change and every minute. */
    fun observeInputs(): Flow<CalendarInputs> {
        val context =
            combine(
                members.observeAll(),
                persons.observeAll(),
                tags.observeAll(includeArchived = true),
                household.observe().map { householdZone(it?.data?.timeZone) },
                observeViewer(localDb, permissions),
            ) { allMembers, allPersons, allTags, zone, viewer -> Context(allMembers, allPersons, allTags, zone, viewer) }
        return combine(rows(), context, tasks.observeViews(), ticks) { rows, ctx, taskViews, now ->
            val viewer = ctx.viewer
            CalendarInputs(
                events = rows.events,
                occurrenceRows = rows.occurrences,
                myReminders = rows.reminders.filter { it.memberId == viewer?.memberId },
                persons = ctx.persons,
                members = ctx.members,
                tags = ctx.tags,
                tasks = if (viewer?.permissions?.has(Permission.TASKS_VIEW) == true) taskViews else emptyList(),
                zone = ctx.zone,
                viewer = viewer,
                now = now,
            )
        }
    }

    fun observeDays(
        from: LocalDate,
        to: LocalDate,
        filter: CalendarFilter = CalendarFilter(),
    ): Flow<List<DayView>> = observeInputs().map { CalendarViews.days(it, from, to, filter) }.distinctUntilChanged()

    fun observeMonth(
        year: Int,
        month: Int,
        filter: CalendarFilter = CalendarFilter(),
    ): Flow<List<MonthCell>> = observeInputs().map { CalendarViews.month(it, year, month, filter) }.distinctUntilChanged()

    fun observeWeek(
        monday: LocalDate,
        filter: CalendarFilter = CalendarFilter(),
    ): Flow<List<WeekDay>> = observeInputs().map { CalendarViews.week(it, monday, filter) }.distinctUntilChanged()

    fun observeUpcoming(): Flow<Upcoming> = observeInputs().map(CalendarViews::upcoming).distinctUntilChanged()

    fun observeForTag(tagId: String): Flow<List<CalendarItem.Event>> =
        observeInputs().map { CalendarViews.forTag(it, tagId) }.distinctUntilChanged()

    fun observeForPerson(personId: String): Flow<PersonDates> =
        observeInputs().map { CalendarViews.forPerson(it, personId) }.distinctUntilChanged()

    fun observeDetail(
        eventId: String,
        occurrence: LocalDate? = null,
    ): Flow<EventDetail?> = observeInputs().map { CalendarViews.detail(it, eventId, occurrence) }.distinctUntilChanged()

    private data class Rows(
        val events: List<Pair<String, EventData>>,
        val occurrences: List<EventOccurrenceData>,
        val reminders: List<EventReminderData>,
    )

    private data class Context(
        val members: List<MemberView>,
        val persons: List<Person>,
        val tags: List<Tag>,
        val zone: TimeZone,
        val viewer: Viewer?,
    )
}
