package app.familyhub.contract.validation

import app.familyhub.contract.api.FieldErrorCode
import app.familyhub.contract.entities.PersonData
import app.familyhub.contract.entities.TagData
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
