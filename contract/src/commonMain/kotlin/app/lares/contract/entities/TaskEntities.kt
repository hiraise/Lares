package app.lares.contract.entities

import app.lares.contract.serialization.LenientEnumSerializer
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlin.time.Instant

// Entity payloads of the tasks module (architecture/tasks.md § Contract data, ADR-0017). New fields are only ever appended.

/**
 * A task or, with a [recurrence], a routine. [dueDate] is the due date of a one-off and the start date (anchor) of a
 * routine. Dates and times are in the household time zone. Empty [assigneeIds] means "anyone" (TSK-002).
 */
@Serializable
data class TaskData(
    val title: String,
    val createdBy: String,
    val notes: String? = null,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val assigneeIds: Set<String> = emptySet(),
    val tagIds: Set<String> = emptySet(),
    /** A rule this version cannot read (unknown mode or unit) decodes to `null`: the task is shown as a one-off. */
    @Serializable(with = LenientRecurrenceSerializer::class) val recurrence: Recurrence? = null,
    val reminderMinutesBefore: Int? = null,
    val notifyCreatorOnDone: Boolean = false,
)

/** The repeat rule of a routine (TSK-010, TSK-011). [weekdays] only for [RecurrenceMode.FIXED] + [RecurrenceUnit.WEEK]. */
@Serializable
data class Recurrence(
    val mode: RecurrenceMode,
    val unit: RecurrenceUnit,
    val interval: Int = 1,
    @Serializable(with = WeekdaySetSerializer::class) val weekdays: Set<DayOfWeek> = emptySet(),
)

@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = RecurrenceModeSerializer::class)
enum class RecurrenceMode {
    /** On scheduled dates counted from the start date. */
    @SerialName("fixed")
    FIXED,

    /** N units after the last completion. */
    @SerialName("after_completion")
    AFTER_COMPLETION,
}

@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = RecurrenceUnitSerializer::class)
enum class RecurrenceUnit {
    @SerialName("day")
    DAY,

    @SerialName("week")
    WEEK,

    @SerialName("month")
    MONTH,

    @SerialName("year")
    YEAR,
}

/** Unknown modes fail to decode; [LenientRecurrenceSerializer] then turns the whole rule into `null`. */
@OptIn(ExperimentalSerializationApi::class)
internal object RecurrenceModeSerializer :
    LenientEnumSerializer<RecurrenceMode>(RecurrenceMode.generatedSerializer(), RecurrenceMode.entries, fallback = null)

/** Unknown units fail to decode; [LenientRecurrenceSerializer] then turns the whole rule into `null`. */
@OptIn(ExperimentalSerializationApi::class)
internal object RecurrenceUnitSerializer :
    LenientEnumSerializer<RecurrenceUnit>(RecurrenceUnit.generatedSerializer(), RecurrenceUnit.entries, fallback = null)

/** A checklist item of a task (TSK-004). In a routine the tick belongs to [doneOccurrence] only. */
@Serializable
data class ChecklistItemData(
    val taskId: String,
    val text: String,
    val position: Int,
    val done: Boolean = false,
    val doneOccurrence: LocalDate? = null,
)

/**
 * Completing ([skipped] false) or skipping an occurrence (ADR-0017). Immutable: undo deletes it.
 * [occurrence] is null for a one-off without a date.
 */
@Serializable
data class TaskCompletionData(
    val taskId: String,
    val completedBy: String,
    val completedAt: Instant,
    val occurrence: LocalDate? = null,
    val skipped: Boolean = false,
)

/** Weekdays as ISO names (`MONDAY`); names this version does not know are dropped. */
object WeekdaySetSerializer : KSerializer<Set<DayOfWeek>> {
    private val names = ListSerializer(String.serializer())
    private val byName = DayOfWeek.entries.associateBy { it.name }

    override val descriptor: SerialDescriptor = names.descriptor

    override fun serialize(
        encoder: Encoder,
        value: Set<DayOfWeek>,
    ) = names.serialize(encoder, value.sortedBy { it.ordinal }.map { it.name })

    override fun deserialize(decoder: Decoder): Set<DayOfWeek> = names.deserialize(decoder).mapNotNull(byName::get).toSet()
}

/**
 * Decodes a [Recurrence] this version cannot read (unknown mode or unit, malformed) as `null`, so an older client shows
 * the task as a one-off instead of dropping it. Works for JSON (the only wire format); other formats decode strictly.
 * The server must not accept such a rule silently: it compares the raw JSON with the decoded value (M1-02).
 */
object LenientRecurrenceSerializer : KSerializer<Recurrence?> {
    private val strict = Recurrence.serializer()

    override val descriptor: SerialDescriptor = strict.descriptor.nullable

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(
        encoder: Encoder,
        value: Recurrence?,
    ) {
        if (value == null) encoder.encodeNull() else encoder.encodeSerializableValue(strict, value)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): Recurrence? {
        if (decoder !is JsonDecoder) {
            return if (decoder.decodeNotNullMark()) decoder.decodeSerializableValue(strict) else decoder.decodeNull()
        }
        val element = decoder.decodeJsonElement()
        if (element is JsonNull) return null
        // SerializationException and date parse errors are both IllegalArgumentExceptions.
        return try {
            decoder.json.decodeFromJsonElement(strict, element)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
