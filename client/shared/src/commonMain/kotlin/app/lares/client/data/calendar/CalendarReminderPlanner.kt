package app.lares.client.data.calendar

import app.lares.client.data.tasks.ReminderDay
import app.lares.client.db.LocalDb
import app.lares.client.notifications.NotificationSettingsRepository
import app.lares.client.notifications.ReminderScheduler
import app.lares.contract.api.notifications.NotificationChannel
import app.lares.contract.api.notifications.NotificationType
import app.lares.contract.calendar.Birthdays
import app.lares.contract.calendar.EventSchedule
import app.lares.contract.entities.EventKind
import app.lares.contract.permissions.EventRules
import app.lares.contract.permissions.Permission
import app.lares.contract.tasks.TaskSchedule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Calendar reminder texts in the app language (architecture/calendar.md § Reminders). Days are relative to the day the
 * reminder fires, so a reminder planned two weeks ahead still says «Сегодня». The core binds
 * [EnglishCalendarReminderTexts]; the app UI binds a resource-backed version.
 */
interface CalendarReminderTexts {
    /** «Сегодня в 16:00 · Клиника на Ленина» / «Завтра, весь день». [time] is null for all-day occurrences. */
    suspend fun eventBody(
        day: ReminderDay,
        date: LocalDate,
        time: LocalTime?,
        place: String?,
    ): String

    /** «День рождения: Бабушка Валя». */
    suspend fun birthdayTitle(name: String): String

    /** «Через 3 дня, исполнится 78» / «Сегодня, 78 лет»; [daysLeft] 0 = today. */
    suspend fun birthdayBody(
        daysLeft: Int,
        age: Int?,
    ): String

    /** «Через 3 дня · 16 лет». */
    suspend fun memorableBody(
        daysLeft: Int,
        years: Int?,
    ): String
}

/** "9:05". */
internal fun clockTime(time: LocalTime): String = "${time.hour}:${time.minute.toString().padStart(2, '0')}"

/** Fallback without UI resources (integration tests, a core without the app UI). */
class EnglishCalendarReminderTexts : CalendarReminderTexts {
    override suspend fun eventBody(
        day: ReminderDay,
        date: LocalDate,
        time: LocalTime?,
        place: String?,
    ): String {
        val dayText =
            when (day) {
                ReminderDay.TODAY -> "Today"
                ReminderDay.TOMORROW -> "Tomorrow"
                ReminderDay.OTHER -> date.toString()
            }
        val whenText = if (time == null) "$dayText, all day" else "$dayText at ${clockTime(time)}"
        return if (place == null) whenText else "$whenText · $place"
    }

    override suspend fun birthdayTitle(name: String) = "Birthday: $name"

    override suspend fun birthdayBody(
        daysLeft: Int,
        age: Int?,
    ): String =
        when {
            age == null -> inDays(daysLeft)
            daysLeft == 0 -> "Today, $age years"
            else -> "${inDays(daysLeft)}, turns $age"
        }

    override suspend fun memorableBody(
        daysLeft: Int,
        years: Int?,
    ): String = if (years == null) inDays(daysLeft) else "${inDays(daysLeft)} · $years years"

    private fun inDays(days: Int) =
        when (days) {
            0 -> "Today"
            1 -> "Tomorrow"
            else -> "In $days days"
        }
}

/**
 * Local reminders of the calendar (architecture/calendar.md § Reminders, CAL-006, CAL-016, FND-050): makes the stored
 * reminders with ids `event:…`, `birthday:…` and `memorable:…` match the wanted ones within [MAX_AHEAD]:
 * - events: every occurrence with [EventRules.myReminder] set and its time in the future, at most
 *   [MAX_EVENT_REMINDERS], nearest first; none while `event.reminder` is muted;
 * - birthdays (not the member's own) and memorable dates: at 09:00 on each day from `birthdayDaysBefore` days before up
 *   to the day; none while `birthday.reminder` is muted.
 * Nothing without `calendar.view`.
 */
class CalendarReminderPlanner(
    private val repository: CalendarRepository,
    private val localDb: LocalDb,
    private val reminders: ReminderScheduler,
    private val settings: NotificationSettingsRepository,
    private val texts: CalendarReminderTexts,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    suspend fun reconcile() =
        mutex.withLock {
            val wanted = wanted()
            val stored =
                PREFIXES
                    .flatMap { prefix -> localDb.read { reminderQueries.selectByPrefix("$prefix%").executeAsList() } }
                    .associateBy { it.id }
            for ((id, reminder) in wanted) {
                val row = stored[id]
                val same =
                    row != null &&
                        row.at == reminder.at.toEpochMilliseconds() &&
                        row.title == reminder.title &&
                        row.body == reminder.body &&
                        row.channel == NotificationChannel.REMINDERS.id
                if (!same) reminders.schedule(id, reminder.at, reminder.title, reminder.body, NotificationChannel.REMINDERS)
            }
            (stored.keys - wanted.keys).forEach { reminders.cancel(it) }
        }

    /**
     * Reconciles after every change of the calendar inputs (debounced 1 s), of the mute settings and of the birthday lead
     * time, until the returned job is cancelled (logout).
     */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            combine(repository.observeInputs(), settings.muted, settings.birthdayDaysBefore) { inputs, _, _ ->
                // The minute ticks alone change nothing here.
                inputs.copy(now = Instant.DISTANT_PAST)
            }.distinctUntilChanged()
                .debounce(1.seconds)
                .collectLatest {
                    try {
                        reconcile()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Retried on the next change and after the next background sync.
                    }
                }
        }

    private suspend fun wanted(): Map<String, Wanted> {
        val now = clock.now()
        val inputs = repository.observeInputs().first().copy(now = now)
        val viewer = inputs.viewer ?: return emptyMap()
        if (!viewer.permissions.has(Permission.CALENDAR_VIEW)) return emptyMap()
        val muted = settings.muted.first()
        val zone = inputs.zone
        val horizon = now + MAX_AHEAD
        val today = now.toLocalDateTime(zone).date
        val lastDay = horizon.toLocalDateTime(zone).date
        val result = mutableMapOf<String, Wanted>()

        if (NotificationType.EVENT_REMINDER !in muted) {
            val rows = inputs.occurrenceRows.groupBy { it.eventId }
            val candidates = mutableListOf<Pair<String, Wanted>>()
            // A reminder may be up to 30 days before the start: look that far past the horizon.
            val to = lastDay.plus(REMINDER_MAX_DAYS, DateTimeUnit.DAY)
            for ((id, event) in inputs.events) {
                if (event.kind != EventKind.EVENT) continue
                val own = inputs.myReminders.firstOrNull { it.eventId == id && it.memberId == viewer.memberId }
                for (occ in EventSchedule.occurrences(id, event, rows[id].orEmpty(), today, to)) {
                    val minutes = EventRules.myReminder(viewer, event, occ, own) ?: continue
                    val at = EventSchedule.reminderAt(occ, minutes, zone)
                    if (at <= now || at > horizon) continue
                    val day = reminderDay(occ.startDate, at.toLocalDateTime(zone).date)
                    val body = texts.eventBody(day, occ.startDate, occ.startTime, occ.place)
                    candidates += "$EVENT_PREFIX$id:${occ.occurrence}" to Wanted(at, occ.title, body)
                }
            }
            candidates
                .sortedWith(compareBy({ it.second.at }, { it.first }))
                .take(MAX_EVENT_REMINDERS)
                .forEach { (id, wanted) -> result[id] = wanted }
        }

        if (NotificationType.BIRTHDAY_REMINDER !in muted) {
            val daysBefore = settings.birthdayDaysBefore()
            val to = lastDay.plus(daysBefore, DateTimeUnit.DAY)

            /** (day, at, days left) of the reminders for [date] that are in the future and within the horizon. */
            fun dayReminders(date: LocalDate): List<Triple<LocalDate, Instant, Int>> =
                Birthdays.reminderDays(date, daysBefore).mapNotNull { day ->
                    val at = LocalDateTime(day, TaskSchedule.DAY_START).toInstant(zone)
                    if (at <= now || at > horizon) null else Triple(day, at, day.daysUntil(date))
                }
            val days = CalendarViews.days(inputs, today, to, CalendarFilter(showTasks = false))
            for (dayView in days) {
                for (item in dayView.items) {
                    when (item) {
                        is CalendarItem.Birthday -> {
                            if (item.person.id == viewer.personId) continue
                            val title = texts.birthdayTitle(item.person.data.name)
                            for ((day, at, left) in dayReminders(item.date)) {
                                result["$BIRTHDAY_PREFIX${item.person.id}:$day"] = Wanted(at, title, texts.birthdayBody(left, item.age))
                            }
                        }

                        is CalendarItem.Event -> {
                            if (item.kind != EventKind.MEMORABLE_DATE || item.continuesBefore) continue
                            for ((day, at, left) in dayReminders(item.occ.startDate)) {
                                result["$MEMORABLE_PREFIX${item.occ.eventId}:$day"] =
                                    Wanted(at, item.occ.title, texts.memorableBody(left, item.years))
                            }
                        }

                        is CalendarItem.Task -> {}
                    }
                }
            }
        }
        return result
    }

    private fun reminderDay(
        date: LocalDate,
        firesOn: LocalDate,
    ): ReminderDay =
        when (date) {
            firesOn -> ReminderDay.TODAY
            firesOn.plus(1, DateTimeUnit.DAY) -> ReminderDay.TOMORROW
            else -> ReminderDay.OTHER
        }

    private data class Wanted(
        val at: Instant,
        val title: String,
        val body: String,
    )

    companion object {
        const val EVENT_PREFIX = "event:"
        const val BIRTHDAY_PREFIX = "birthday:"
        const val MEMORABLE_PREFIX = "memorable:"
        val PREFIXES = listOf(EVENT_PREFIX, BIRTHDAY_PREFIX, MEMORABLE_PREFIX)
        val MAX_AHEAD = 14.days
        const val MAX_EVENT_REMINDERS = 100

        /** The largest reminder lead time, 43200 minutes (`Validators`). */
        private const val REMINDER_MAX_DAYS = 30
    }
}
