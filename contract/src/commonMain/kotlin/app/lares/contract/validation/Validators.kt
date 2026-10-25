package app.lares.contract.validation

import app.lares.contract.api.FieldErrorCode
import app.lares.contract.calendar.EventSchedule
import app.lares.contract.entities.ChecklistItemData
import app.lares.contract.entities.EventData
import app.lares.contract.entities.EventKind
import app.lares.contract.entities.EventOccurrenceData
import app.lares.contract.entities.EventReminderData
import app.lares.contract.entities.PersonData
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import app.lares.contract.entities.ShoppingItemData
import app.lares.contract.entities.ShoppingItemStatus
import app.lares.contract.entities.ShoppingListData
import app.lares.contract.entities.TagData
import app.lares.contract.entities.TaskCompletionData
import app.lares.contract.entities.TaskData
import app.lares.contract.shopping.ShoppingNames
import kotlinx.datetime.IllegalTimeZoneException
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/**
 * Input rules shared by client and server. Each validator returns `null` when the value is valid,
 * otherwise the field error code to report.
 */
object Validators {
    const val LOGIN_MIN = 3
    const val LOGIN_MAX = 32
    const val PASSWORD_MIN = 8
    const val PASSWORD_MAX = 128
    const val PERSON_NAME_MAX = 100
    const val HOUSEHOLD_NAME_MAX = 60
    const val GROUP_NAME_MAX = 40
    const val DEVICE_NAME_MAX = 100
    const val PLATFORM_MAX = 40
    const val MIN_BIRTH_YEAR = 1900
    const val TAG_NAME_MAX = 40
    const val ICON_MAX = 64
    const val RELATION_MAX = 60
    const val NOTES_MAX = 2000
    const val CONTACTS_MAX = 20
    const val CONTACT_VALUE_MAX = 200
    const val TASK_TITLE_MAX = 200
    const val CHECKLIST_TEXT_MAX = 200
    const val TASK_MAX_ASSIGNEES = 20
    const val TASK_MAX_TAGS = 20
    const val RECURRENCE_INTERVAL_MAX = 999
    const val REMINDER_MINUTES_MAX = 43200
    const val CHECKLIST_POSITION_MAX = 1_000_000
    const val SHOPPING_LIST_NAME_MAX = 100
    const val SHOPPING_ITEM_NAME_MAX = 200
    const val SHOPPING_QUANTITY_MAX = 30
    const val SHOPPING_NOTE_MAX = 500
    const val SHOPPING_MAX_TAGS = 20
    const val SHOPPING_BOUGHT_COUNT_MAX = 100_000
    const val EVENT_TITLE_MAX = 200
    const val EVENT_PLACE_MAX = 200
    const val EVENT_MAX_PARTICIPANTS = 30
    const val EVENT_MAX_TAGS = 20

    /** The only rule of a memorable date (CAL-005). */
    val MEMORABLE_RECURRENCE = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.YEAR, 1)

    val LANGUAGES: Set<String> = setOf("en", "ru")

    private val loginPattern = Regex("[a-z0-9._-]+")
    private val currencyPattern = Regex("[A-Z]{3}")

    /** Logins are case-insensitive: validate (and store) the lowercase form. */
    fun normalizeLogin(login: String): String = login.trim().lowercase()

    fun login(value: String): FieldErrorCode? {
        val login = normalizeLogin(value)
        return when {
            login.isEmpty() -> FieldErrorCode.REQUIRED
            login.length < LOGIN_MIN -> FieldErrorCode.TOO_SHORT
            login.length > LOGIN_MAX -> FieldErrorCode.TOO_LONG
            !loginPattern.matches(login) -> FieldErrorCode.INVALID_FORMAT
            else -> null
        }
    }

    fun password(value: String): FieldErrorCode? =
        when {
            value.isEmpty() -> FieldErrorCode.REQUIRED
            value.length < PASSWORD_MIN -> FieldErrorCode.TOO_SHORT
            value.length > PASSWORD_MAX -> FieldErrorCode.TOO_LONG
            else -> null
        }

    fun personName(value: String): FieldErrorCode? = requiredText(value, PERSON_NAME_MAX)

    fun householdName(value: String): FieldErrorCode? = requiredText(value, HOUSEHOLD_NAME_MAX)

    fun groupName(value: String): FieldErrorCode? = requiredText(value, GROUP_NAME_MAX)

    /** Tag names are trimmed; uniqueness (TAG-006) is checked by the server. */
    fun tagName(value: String): FieldErrorCode? = requiredText(value, TAG_NAME_MAX)

    /** An optional icon key; when given it must not be blank. */
    fun icon(value: String?): FieldErrorCode? = value?.let { requiredText(it, ICON_MAX)?.let { FieldErrorCode.INVALID_FORMAT } }

    fun tag(data: TagData): Map<String, FieldErrorCode> =
        ValidationErrors()
            .add("name", tagName(data.name))
            .add("icon", icon(data.icon))
            .fields

    /** All person fields (FND-031). Errors are keyed by field name; contact problems by `contacts`. */
    fun person(
        data: PersonData,
        currentYear: Int,
    ): Map<String, FieldErrorCode> {
        val errors = ValidationErrors().add("name", personName(data.name))
        errors.addAll(birthDate(data.birthYear, data.birthMonth, data.birthDay, currentYear))
        errors.add("relation", optionalText(data.relation, RELATION_MAX))
        errors.add("notes", optionalText(data.notes, NOTES_MAX))
        val contactProblem =
            when {
                data.contacts.size > CONTACTS_MAX -> FieldErrorCode.OUT_OF_RANGE
                else -> data.contacts.firstNotNullOfOrNull { requiredText(it.value, CONTACT_VALUE_MAX) }
            }
        errors.add("contacts", contactProblem)
        return errors.fields
    }

    /** Trims the title and notes; blank notes become null. Server and client normalize before validating and storing. */
    fun normalizeTask(data: TaskData): TaskData = data.copy(title = data.title.trim(), notes = data.notes?.trim()?.ifEmpty { null })

    fun normalizeChecklistItem(data: ChecklistItemData): ChecklistItemData = data.copy(text = data.text.trim())

    /**
     * Field rules of a task (architecture/tasks.md § Validation). Rules that need the database (members, tags, creator,
     * routine → one-off) are checked by the server handlers.
     */
    fun task(data: TaskData): Map<String, FieldErrorCode> {
        val errors =
            ValidationErrors()
                .add("title", requiredText(data.title, TASK_TITLE_MAX))
                .add("notes", optionalText(data.notes, NOTES_MAX))
        val needsDate = data.dueTime != null || data.recurrence != null || data.reminderMinutesBefore != null
        if (needsDate && data.dueDate == null) errors.add("dueDate", FieldErrorCode.REQUIRED)
        val time = data.dueTime
        if (time != null && (time.second != 0 || time.nanosecond != 0)) errors.add("dueTime", FieldErrorCode.INVALID_FORMAT)
        errors.add("recurrence", data.recurrence?.let(::recurrence))
        val reminder = data.reminderMinutesBefore
        if (reminder != null && reminder !in 0..REMINDER_MINUTES_MAX) {
            errors.add("reminderMinutesBefore", FieldErrorCode.OUT_OF_RANGE)
        }
        if (data.assigneeIds.size > TASK_MAX_ASSIGNEES) errors.add("assigneeIds", FieldErrorCode.OUT_OF_RANGE)
        if (data.tagIds.size > TASK_MAX_TAGS) errors.add("tagIds", FieldErrorCode.OUT_OF_RANGE)
        return errors.fields
    }

    /** Weekdays only for fixed weekly rules, and then at least one. */
    fun recurrence(rule: Recurrence): FieldErrorCode? {
        val weekly = rule.mode == RecurrenceMode.FIXED && rule.unit == RecurrenceUnit.WEEK
        return when {
            rule.interval !in 1..RECURRENCE_INTERVAL_MAX -> FieldErrorCode.OUT_OF_RANGE
            weekly == rule.weekdays.isEmpty() -> FieldErrorCode.INVALID_FORMAT
            else -> null
        }
    }

    fun checklistItem(data: ChecklistItemData): Map<String, FieldErrorCode> =
        ValidationErrors()
            .add("text", requiredText(data.text, CHECKLIST_TEXT_MAX))
            .add("position", if (data.position in 0..CHECKLIST_POSITION_MAX) null else FieldErrorCode.OUT_OF_RANGE)
            .fields

    /** [task] is the parent; skipping is for routines only. Other completion rules need the server (handlers). */
    fun taskCompletion(
        data: TaskCompletionData,
        task: TaskData,
    ): Map<String, FieldErrorCode> =
        ValidationErrors()
            .add("skipped", if (data.skipped && task.recurrence == null) FieldErrorCode.INVALID_FORMAT else null)
            .fields

    /** Trims the list name. Server and client normalize before validating and storing. */
    fun normalizeShoppingList(data: ShoppingListData): ShoppingListData = data.copy(name = data.name.trim())

    /** Normalizes the item name (ShoppingNames.normalize); quantity and note trimmed, blank ones become null. */
    fun normalizeShoppingItem(data: ShoppingItemData): ShoppingItemData =
        data.copy(
            name = ShoppingNames.normalize(data.name),
            quantity = data.quantity?.trim()?.ifEmpty { null },
            note = data.note?.trim()?.ifEmpty { null },
        )

    /** Field rules of a shopping list (architecture/shopping.md § Validation); creator and tags are checked by the server. */
    fun shoppingList(data: ShoppingListData): Map<String, FieldErrorCode> {
        val errors = ValidationErrors().add("name", requiredText(data.name, SHOPPING_LIST_NAME_MAX))
        if (data.tagIds.size > SHOPPING_MAX_TAGS) errors.add("tagIds", FieldErrorCode.OUT_OF_RANGE)
        return errors.fields
    }

    /** Field rules of a shopping item; the parent list, tags and times are checked by the server. */
    fun shoppingItem(data: ShoppingItemData): Map<String, FieldErrorCode> {
        val errors =
            ValidationErrors()
                .add("name", requiredText(data.name, SHOPPING_ITEM_NAME_MAX))
                .add("quantity", optionalText(data.quantity, SHOPPING_QUANTITY_MAX))
                .add("note", optionalText(data.note, SHOPPING_NOTE_MAX))
        if (data.tagIds.size > SHOPPING_MAX_TAGS) errors.add("tagIds", FieldErrorCode.OUT_OF_RANGE)
        if (data.boughtCount !in 0..SHOPPING_BOUGHT_COUNT_MAX) errors.add("boughtCount", FieldErrorCode.OUT_OF_RANGE)
        if (data.status == ShoppingItemStatus.BOUGHT) {
            if (data.boughtBy == null) errors.add("boughtBy", FieldErrorCode.REQUIRED)
            if (data.boughtAt == null) errors.add("boughtAt", FieldErrorCode.REQUIRED)
        }
        return errors.fields
    }

    /** Trims the title; blank place and notes become null; all-day events lose their times. */
    fun normalizeEvent(data: EventData): EventData =
        data.copy(
            title = data.title.trim(),
            place = data.place?.trim()?.ifEmpty { null },
            notes = data.notes?.trim()?.ifEmpty { null },
            startTime = if (data.allDay) null else data.startTime,
            endTime = if (data.allDay) null else data.endTime,
        )

    /** Trims the texts; a blank title becomes null (as the series), but place and notes keep `""` ("none here"). */
    fun normalizeEventOccurrence(data: EventOccurrenceData): EventOccurrenceData =
        data.copy(
            title = data.title?.trim()?.ifEmpty { null },
            place = data.place?.trim(),
            notes = data.notes?.trim(),
        )

    /**
     * Field rules of an event (architecture/calendar.md § Validation). Creator, participants, tags and `splitFrom` need
     * the database and are checked by the server handlers.
     */
    fun event(data: EventData): Map<String, FieldErrorCode> {
        val errors = ValidationErrors()
        val memorable = data.kind == EventKind.MEMORABLE_DATE
        if (memorable) {
            if (!data.allDay) errors.add("allDay", FieldErrorCode.INVALID_FORMAT)
            if (data.endDate != data.startDate) errors.add("endDate", FieldErrorCode.INVALID_FORMAT)
            if (data.recurrence != MEMORABLE_RECURRENCE) errors.add("recurrence", FieldErrorCode.INVALID_FORMAT)
            if (data.repeatUntil != null) errors.add("repeatUntil", FieldErrorCode.INVALID_FORMAT)
            if (data.reminderMinutesBefore != null) errors.add("reminderMinutesBefore", FieldErrorCode.INVALID_FORMAT)
        } else if (data.countYears) {
            errors.add("countYears", FieldErrorCode.INVALID_FORMAT)
        }
        errors
            .add("title", requiredText(data.title, EVENT_TITLE_MAX))
            .add("place", optionalText(data.place, EVENT_PLACE_MAX))
            .add("notes", optionalText(data.notes, NOTES_MAX))
        // A memorable date is all-day even when the flag is wrong: its times are only checked for being absent.
        val timed = !data.allDay && !memorable
        times(errors, timed, data.startDate, data.endDate, data.startTime, data.endTime, required = timed)
        val rule = data.recurrence
        if (rule != null) {
            errors.add("recurrence", recurrence(rule))
            if (rule.mode == RecurrenceMode.AFTER_COMPLETION) errors.add("recurrence", FieldErrorCode.INVALID_FORMAT)
        }
        val until = data.repeatUntil
        when {
            until == null -> Unit
            rule == null -> errors.add("repeatUntil", FieldErrorCode.INVALID_FORMAT)
            until < data.startDate -> errors.add("repeatUntil", FieldErrorCode.OUT_OF_RANGE)
        }
        errors.add("reminderMinutesBefore", reminderMinutes(data.reminderMinutesBefore))
        if (data.participantIds.size > EVENT_MAX_PARTICIPANTS) errors.add("participantIds", FieldErrorCode.OUT_OF_RANGE)
        if (data.tagIds.size > EVENT_MAX_TAGS) errors.add("tagIds", FieldErrorCode.OUT_OF_RANGE)
        return errors.fields
    }

    /**
     * Field rules of an occurrence row of [event]: times all null or the full set for the event's kind of time (both dates,
     * plus both times iff the event is timed). Id, parent and scheduled date are checked by the server handlers.
     */
    fun eventOccurrence(
        data: EventOccurrenceData,
        event: EventData,
    ): Map<String, FieldErrorCode> {
        val errors = ValidationErrors()
        val timed = !event.allDay && event.kind != EventKind.MEMORABLE_DATE
        val start = data.startDate
        val end = data.endDate
        val anySet = start != null || end != null || data.startTime != null || data.endTime != null
        if (anySet) {
            if (start == null) errors.add("startDate", FieldErrorCode.INVALID_FORMAT)
            if (end == null) errors.add("endDate", FieldErrorCode.INVALID_FORMAT)
            if (start != null && end != null) {
                times(
                    errors,
                    timed,
                    start,
                    end,
                    data.startTime,
                    data.endTime,
                    required = timed,
                    requiredCode = FieldErrorCode.INVALID_FORMAT,
                )
            } else if (timed) {
                if (data.startTime == null) errors.add("startTime", FieldErrorCode.INVALID_FORMAT)
                if (data.endTime == null) errors.add("endTime", FieldErrorCode.INVALID_FORMAT)
            }
        }
        errors
            .add("title", optionalText(data.title, EVENT_TITLE_MAX))
            .add("place", optionalText(data.place, EVENT_PLACE_MAX))
            .add("notes", optionalText(data.notes, NOTES_MAX))
        val participants = data.participantIds
        if (participants != null && participants.size > EVENT_MAX_PARTICIPANTS) {
            errors.add("participantIds", FieldErrorCode.OUT_OF_RANGE)
        }
        return errors.fields
    }

    /** Field rules of a personal reminder; the member and the event are checked by the server handlers. */
    fun eventReminder(data: EventReminderData): Map<String, FieldErrorCode> =
        ValidationErrors().add("minutesBefore", reminderMinutes(data.minutesBefore)).fields

    /**
     * Shared time rules of events and occurrence rows. Timed: both times [required] (else [requiredCode]), whole
     * minutes. All-day: no times. `endDate` is out of range when before the start date, not after the start moment, or
     * more than [EventSchedule.MAX_SPAN_DAYS] days away.
     */
    @Suppress("LongParameterList")
    private fun times(
        errors: ValidationErrors,
        timed: Boolean,
        startDate: LocalDate,
        endDate: LocalDate,
        startTime: LocalTime?,
        endTime: LocalTime?,
        required: Boolean,
        requiredCode: FieldErrorCode? = FieldErrorCode.REQUIRED,
    ) {
        for ((field, time) in listOf("startTime" to startTime, "endTime" to endTime)) {
            val code =
                when {
                    time == null -> if (required) requiredCode else null
                    !timed -> FieldErrorCode.INVALID_FORMAT
                    time.second != 0 || time.nanosecond != 0 -> FieldErrorCode.INVALID_FORMAT
                    else -> null
                }
            errors.add(field, code)
        }
        val endBeforeStart =
            if (timed && startTime != null && endTime != null) {
                LocalDateTime(endDate, endTime) <= LocalDateTime(startDate, startTime)
            } else {
                endDate < startDate
            }
        val tooLong = startDate.toEpochDays() + EventSchedule.MAX_SPAN_DAYS < endDate.toEpochDays()
        if (endBeforeStart || tooLong) errors.add("endDate", FieldErrorCode.OUT_OF_RANGE)
    }

    private fun reminderMinutes(value: Int?): FieldErrorCode? =
        if (value != null && value !in 0..REMINDER_MINUTES_MAX) FieldErrorCode.OUT_OF_RANGE else null

    fun deviceName(value: String): FieldErrorCode? = requiredText(value, DEVICE_NAME_MAX)

    fun platform(value: String): FieldErrorCode? = requiredText(value, PLATFORM_MAX)

    fun timeZone(value: String): FieldErrorCode? {
        if (value.isBlank()) return FieldErrorCode.REQUIRED
        return try {
            TimeZone.of(value)
            null
        } catch (_: IllegalTimeZoneException) {
            FieldErrorCode.INVALID_FORMAT
        }
    }

    fun currency(value: String): FieldErrorCode? =
        when {
            value.isEmpty() -> FieldErrorCode.REQUIRED
            !currencyPattern.matches(value) -> FieldErrorCode.INVALID_FORMAT
            else -> null
        }

    fun language(value: String): FieldErrorCode? =
        when {
            value.isEmpty() -> FieldErrorCode.REQUIRED
            value !in LANGUAGES -> FieldErrorCode.INVALID_FORMAT
            else -> null
        }

    /**
     * A birth date with an optionally unknown year (and day). Errors are keyed by `birthYear`, `birthMonth`, `birthDay`.
     * February 29 is valid when the year is unknown or a leap year.
     */
    fun birthDate(
        year: Int?,
        month: Int?,
        day: Int?,
        currentYear: Int,
    ): Map<String, FieldErrorCode> {
        val errors = ValidationErrors()
        if (year != null && year !in MIN_BIRTH_YEAR..currentYear) errors.add("birthYear", FieldErrorCode.OUT_OF_RANGE)
        when {
            month == null -> if (day != null) errors.add("birthMonth", FieldErrorCode.REQUIRED)
            month !in 1..12 -> errors.add("birthMonth", FieldErrorCode.OUT_OF_RANGE)
            day != null && day !in 1..daysInMonth(month, year) -> errors.add("birthDay", FieldErrorCode.OUT_OF_RANGE)
        }
        return errors.fields
    }

    private fun daysInMonth(
        month: Int,
        year: Int?,
    ): Int =
        when (month) {
            2 -> if (year == null || isLeapYear(year)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }

    private fun isLeapYear(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    private fun optionalText(
        value: String?,
        max: Int,
    ): FieldErrorCode? = if (value != null && value.length > max) FieldErrorCode.TOO_LONG else null

    private fun requiredText(
        value: String,
        max: Int,
    ): FieldErrorCode? {
        val text = value.trim()
        return when {
            text.isEmpty() -> FieldErrorCode.REQUIRED
            text.length > max -> FieldErrorCode.TOO_LONG
            else -> null
        }
    }
}

/** Collects field errors; the first error per field wins. */
class ValidationErrors {
    private val errors = LinkedHashMap<String, FieldErrorCode>()

    val fields: Map<String, FieldErrorCode> get() = errors.toMap()

    val isEmpty: Boolean get() = errors.isEmpty()

    fun add(
        field: String,
        code: FieldErrorCode?,
    ): ValidationErrors {
        if (code != null && field !in errors) errors[field] = code
        return this
    }

    fun addAll(fields: Map<String, FieldErrorCode>): ValidationErrors {
        fields.forEach { (field, code) -> add(field, code) }
        return this
    }
}
