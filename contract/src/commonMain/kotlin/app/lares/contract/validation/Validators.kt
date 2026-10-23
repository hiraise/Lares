package app.lares.contract.validation

import app.lares.contract.api.FieldErrorCode
import app.lares.contract.entities.ChecklistItemData
import app.lares.contract.entities.PersonData
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import app.lares.contract.entities.TagData
import app.lares.contract.entities.TaskCompletionData
import app.lares.contract.entities.TaskData
import kotlinx.datetime.IllegalTimeZoneException
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
