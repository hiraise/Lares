package app.lares.client.data.calendar

import app.lares.client.auth.LocalSession
import app.lares.client.data.calendar.CalendarViews.isEmpty
import app.lares.client.db.LaresDb
import app.lares.client.db.LocalDb
import app.lares.client.sync.LocalForbiddenException
import app.lares.client.sync.LocalWriteException
import app.lares.client.sync.LocalWriter
import app.lares.client.sync.toData
import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.calendar.EventSchedule
import app.lares.contract.entities.EventData
import app.lares.contract.entities.EventKind
import app.lares.contract.entities.EventOccurrenceData
import app.lares.contract.entities.EventReminderData
import app.lares.contract.permissions.EventRules
import app.lares.contract.permissions.Viewer
import app.lares.contract.sync.EntityTypes
import app.lares.contract.tasks.Recurrences
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock

/**
 * One occurrence's changes for [CalendarWriter.editOccurrence]; null = as the series. Times go together: the dates and
 * (for a timed event) both times, or none. `""` place or notes = none for this occurrence.
 */
data class OccurrenceChanges(
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val title: String? = null,
    val place: String? = null,
    val notes: String? = null,
    val participantIds: Set<String>? = null,
)

/**
 * Every user action on events (architecture/calendar.md § Writer), through [LocalWriter]: rights and validation are
 * checked locally and the change works offline. Every write sends only the fields that differ from the local row, so
 * offline edits of different fields of one occurrence on two phones merge (ADR-0022). Occurrence and reminder rows are
 * never deleted: a reset writes an empty override, "no reminder for me" writes `minutesBefore = null`.
 * Throws [LocalWriteException] when the change is not allowed or invalid.
 */
class CalendarWriter(
    private val writer: LocalWriter,
    private val localDb: LocalDb,
    @Suppress("unused") private val clock: Clock,
) {
    /** Creates an event or memorable date and returns its id; `createdBy` is set to the member. */
    suspend fun create(draft: EventData): String {
        val me = localDb.read { viewer() }
        val id = Ids.newId()
        writer.upsert(EntityTypes.EVENT, id, encode(EventData.serializer(), draft.copy(createdBy = me.memberId)))
        return id
    }

    /**
     * «Все»: saves [newData] as the series (changed fields only). A change of the start date, all-day or the repeat rule
     * resets every changed or cancelled occurrence afterwards (CAL-015 ASSUMPTION).
     */
    suspend fun update(
        id: String,
        newData: EventData,
    ) {
        val current = localDb.read { event(id) }
        patch(EntityTypes.EVENT, id, EventData.serializer(), current, newData)
        val reset =
            current.startDate != newData.startDate || current.allDay != newData.allDay || current.recurrence != newData.recurrence
        if (reset) resetRows(id) { true }
    }

    /** «Только это»: writes the occurrence's override row; values equal to the series are written as null. */
    suspend fun editOccurrence(
        eventId: String,
        occurrence: LocalDate,
        changes: OccurrenceChanges,
    ) {
        val event = localDb.read { event(eventId) }
        val naturalEnd = occurrence.plus(EventSchedule.spanDays(event), DateTimeUnit.DAY)
        val natural = listOf(occurrence, naturalEnd, event.startTime, event.endTime)
        val wanted =
            listOf(
                changes.startDate ?: occurrence,
                changes.endDate ?: naturalEnd,
                changes.startTime ?: event.startTime,
                changes.endTime ?: event.endTime,
            )
        val timesChanged = wanted != natural
        val place = changes.place?.takeIf { it != (event.place ?: "") }
        val notes = changes.notes?.takeIf { it != (event.notes ?: "") }
        val row =
            EventOccurrenceData(
                eventId = eventId,
                occurrence = occurrence,
                cancelled = false,
                startDate = if (timesChanged) wanted[0] as LocalDate else null,
                endDate = if (timesChanged) wanted[1] as LocalDate else null,
                startTime = if (timesChanged) wanted[2] as LocalTime? else null,
                endTime = if (timesChanged) wanted[3] as LocalTime? else null,
                title = changes.title?.takeIf { it.trim() != event.title },
                place = place,
                notes = notes,
                participantIds = changes.participantIds?.takeIf { it != event.participantIds },
            )
        writeRow(row)
    }

    /** «Только это» on delete: cancels the occurrence (CAL-015). */
    suspend fun cancelOccurrence(
        eventId: String,
        occurrence: LocalDate,
    ) {
        val current = localDb.read { row(eventId, occurrence) }
        writeRow((current ?: EventOccurrenceData(eventId, occurrence)).copy(cancelled = true))
    }

    /**
     * «Это и следующие» (CAL-015): on the first scheduled date the same as [update]; otherwise creates the new series
     * ([newData] with `splitFrom`), ends the old one the day before [from] and resets its rows from [from] on.
     * Returns the id of the series that now holds [from].
     */
    suspend fun editFollowing(
        eventId: String,
        from: LocalDate,
        newData: EventData,
    ): String {
        val current = localDb.read { editable(eventId) }
        if (from <= firstScheduled(current)) {
            update(eventId, newData)
            return eventId
        }
        val newId = create(newData.copy(splitFrom = eventId))
        endBefore(eventId, current, from)
        return newId
    }

    /** «Это и следующие» on delete: on the first scheduled date deletes the series, otherwise ends it before [from]. */
    suspend fun deleteFollowing(
        eventId: String,
        from: LocalDate,
    ) {
        val current = localDb.read { editable(eventId) }
        if (from <= firstScheduled(current)) delete(eventId) else endBefore(eventId, current, from)
    }

    /** Deletes the event; the server's cascade removes its occurrence and reminder rows. */
    suspend fun delete(eventId: String) = writer.delete(EntityTypes.EVENT, eventId)

    /** «Напомнить мне» (CAL-006): [minutes] before, or null for "no reminder for me". */
    suspend fun setMyReminder(
        eventId: String,
        minutes: Int?,
    ) {
        val (me, current) =
            localDb.read {
                val me = viewer()
                me to reminder(eventId, me.memberId)
            }
        val data = EventReminderData(eventId, me.memberId, minutes)
        patch(EntityTypes.EVENT_REMINDER, Ids.reminderRowId(eventId, me.memberId), EventReminderData.serializer(), current, data)
    }

    /** «Как в событии»: the event's reminder when the event concerns me (participant or whole family), else none. */
    suspend fun clearMyReminder(eventId: String) {
        val (me, event) = localDb.read { viewer() to event(eventId) }
        val concernsMe = event.participantIds.isEmpty() || me.personId in event.participantIds
        val minutes = if (event.kind == EventKind.EVENT && concernsMe) event.reminderMinutesBefore else null
        setMyReminder(eventId, minutes)
    }

    private suspend fun endBefore(
        eventId: String,
        current: EventData,
        from: LocalDate,
    ) {
        patch(EntityTypes.EVENT, eventId, EventData.serializer(), current, current.copy(repeatUntil = from.minus(1, DateTimeUnit.DAY)))
        resetRows(eventId) { it.occurrence >= from }
    }

    /** Writes an empty override over every non-empty row of [eventId] that matches [which]. */
    private suspend fun resetRows(
        eventId: String,
        which: (EventOccurrenceData) -> Boolean,
    ) {
        val rows =
            localDb.read {
                event_occurrenceQueries.selectByEvent(eventId).executeAsList().map { it.toData() }
            }
        rows.filter { !it.isEmpty() && which(it) }.forEach { writeRow(EventOccurrenceData(it.eventId, it.occurrence)) }
    }

    private suspend fun writeRow(row: EventOccurrenceData) {
        val current = localDb.read { row(row.eventId, row.occurrence) }
        patch(
            EntityTypes.EVENT_OCCURRENCE,
            Ids.occurrenceRowId(row.eventId, row.occurrence),
            EventOccurrenceData.serializer(),
            current,
            row,
        )
    }

    /** Sends the top-level fields of [newData] that differ from [current] (all of them for a new row). */
    private suspend fun <T> patch(
        type: String,
        id: String,
        serializer: KSerializer<T>,
        current: T?,
        newData: T,
    ) {
        val currentJson = current?.let { encode(serializer, it) } ?: JsonObject(emptyMap())
        val newJson = encode(serializer, newData)
        val keys = (currentJson.keys + newJson.keys).filter { currentJson[it] != newJson[it] }
        if (current != null && keys.isEmpty()) return
        writer.upsert(type, id, JsonObject(keys.associateWith { newJson[it] ?: JsonNull }))
    }

    private fun firstScheduled(event: EventData): LocalDate =
        event.recurrence?.let { Recurrences.firstOnOrAfter(it, event.startDate, event.startDate) } ?: event.startDate

    private fun LaresDb.viewer(): Viewer = LocalSession.viewer(this) ?: throw LocalForbiddenException("Not logged in")

    private fun LaresDb.event(id: String): EventData =
        eventQueries.selectById(id).executeAsOneOrNull()?.toData()
            ?: throw LocalWriteException(ErrorCode.ENTITY_NOT_FOUND, message = "Event not found")

    /** The event, checked up front so a split the member may not make writes nothing at all. */
    private fun LaresDb.editable(id: String): EventData {
        val event = event(id)
        if (!EventRules.canEdit(viewer(), event)) throw LocalForbiddenException("Not allowed to change this event")
        return event
    }

    private fun LaresDb.row(
        eventId: String,
        occurrence: LocalDate,
    ): EventOccurrenceData? = event_occurrenceQueries.selectById(Ids.occurrenceRowId(eventId, occurrence)).executeAsOneOrNull()?.toData()

    private fun LaresDb.reminder(
        eventId: String,
        memberId: String,
    ): EventReminderData? = event_reminderQueries.selectById(Ids.reminderRowId(eventId, memberId)).executeAsOneOrNull()?.toData()

    private fun <T> encode(
        serializer: KSerializer<T>,
        data: T,
    ): JsonObject = LaresJson.encodeToJsonElement(serializer, data).jsonObject
}
