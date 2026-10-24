package app.lares.contract.entities

import app.lares.contract.serialization.LenientEnumSerializer
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Entity payloads of the calendar module (architecture/calendar.md § Contract data, ADR-0022, ADR-0023).
// New fields are only ever appended.

/**
 * An event or, with a [recurrence], a series (CAL-001, CAL-003, CAL-013). Dates and times are wall-clock values in the
 * household time zone. All-day events have no times and [endDate] is their last day. Empty [participantIds] means the
 * whole family (CAL-002). A memorable date (CAL-005) is a yearly all-day event of kind [EventKind.MEMORABLE_DATE].
 */
@Serializable
data class EventData(
    val title: String,
    /** Member id; set on create, never changes. */
    val createdBy: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val allDay: Boolean = false,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val place: String? = null,
    val notes: String? = null,
    /** Person ids; empty = the whole family. */
    val participantIds: Set<String> = emptySet(),
    val tagIds: Set<String> = emptySet(),
    /** Mode `fixed` only; null = one-off. A rule this version cannot read decodes to `null`. */
    @Serializable(with = LenientRecurrenceSerializer::class) val recurrence: Recurrence? = null,
    /** The last date an occurrence may start; needs a [recurrence]. */
    val repeatUntil: LocalDate? = null,
    /** Default reminder of the participants (CAL-006); null for memorable dates. */
    val reminderMinutesBefore: Int? = null,
    val kind: EventKind = EventKind.EVENT,
    /** Memorable date: show the years since [startDate]'s year. */
    val countYears: Boolean = false,
    /** "This and following" (CAL-015): the series this one was cut from; set on create only. */
    val splitFrom: String? = null,
)

/**
 * A changed or cancelled occurrence of a repeating event (ADR-0022). The id is `Ids.occurrenceRowId(eventId, occurrence)`.
 * Every override field null means "as the series"; a row with nothing set and [cancelled] false has no effect (a reset).
 * Clients never delete these rows.
 */
@Serializable
data class EventOccurrenceData(
    /** Never changes. */
    val eventId: String,
    /** The scheduled start date it overrides; never changes. */
    val occurrence: LocalDate,
    val cancelled: Boolean = false,
    /** Times: all null = as the series; otherwise both dates, plus both times iff the series is timed. */
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val title: String? = null,
    /** `""` = no place for this occurrence. */
    val place: String? = null,
    /** `""` = no notes for this occurrence. */
    val notes: String? = null,
    val participantIds: Set<String>? = null,
)

/**
 * One member's own reminder for an event (CAL-006, ADR-0023), visible only to that member. The id is
 * `Ids.reminderRowId(eventId, memberId)`. Clients never delete these rows.
 */
@Serializable
data class EventReminderData(
    /** Never changes. */
    val eventId: String,
    /** Never changes; always the writer. */
    val memberId: String,
    /** null = no reminder for me. */
    val minutesBefore: Int? = null,
)

/** Unknown kinds from a newer version decode to [EVENT]: the row is shown as a plain event. */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = EventKindSerializer::class)
enum class EventKind {
    @SerialName("event")
    EVENT,

    @SerialName("memorable_date")
    MEMORABLE_DATE,
    ;

    /** The wire name, e.g. `memorable_date`. */
    val key: String get() = EventKindSerializer.serialName(this)

    companion object {
        /** Returns the kind with this wire name, or `null` if this version does not know it. */
        fun fromKey(key: String): EventKind? = EventKindSerializer.decodeOrNull(key)
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal object EventKindSerializer :
    LenientEnumSerializer<EventKind>(EventKind.generatedSerializer(), EventKind.entries, EventKind.EVENT)
