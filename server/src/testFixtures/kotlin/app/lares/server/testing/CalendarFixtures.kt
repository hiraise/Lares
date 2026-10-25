package app.lares.server.testing

import app.lares.contract.Ids
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.Mutation
import app.lares.server.AppDeps
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Helpers for calendar tests (M3-02); reused by M3-03 and integration-tests.

/** Event fields as JSON: a timed event 10:00–11:00 on [startDate] unless [allDay]. [extra] adds or overrides fields. */
fun eventJson(
    createdBy: String,
    title: String = "Dentist",
    startDate: String = "2026-11-02",
    endDate: String = startDate,
    allDay: Boolean = false,
    participantIds: List<String> = emptyList(),
    extra: Map<String, Any?> = emptyMap(),
): JsonObject {
    val base: Map<String, JsonElement> =
        fields(
            "title" to title,
            "createdBy" to createdBy,
            "startDate" to startDate,
            "endDate" to endDate,
            "allDay" to allDay,
            "startTime" to if (allDay) null else "10:00",
            "endTime" to if (allDay) null else "11:00",
            "participantIds" to JsonArray(participantIds.map(::JsonPrimitive)),
        )
    return JsonObject(base + fields(*extra.toList().toTypedArray()))
}

/** A new event by [createdBy] (a member id); see [eventJson]. */
fun AppDeps.newEvent(
    createdBy: String,
    title: String = "Dentist",
    startDate: String = "2026-11-02",
    endDate: String = startDate,
    allDay: Boolean = false,
    participantIds: List<String> = emptyList(),
    extra: Map<String, Any?> = emptyMap(),
    id: String = Ids.newId(),
): Mutation = upsert(EntityTypes.EVENT, id, eventJson(createdBy, title, startDate, endDate, allDay, participantIds, extra))

/** An upsert of the occurrence row of [eventId] on [occurrence] (ISO date) with its derived id unless [id] is given. */
fun AppDeps.newOccurrenceRow(
    eventId: String,
    occurrence: String,
    fields: Map<String, Any?> = emptyMap(),
    id: String = Ids.occurrenceRowId(eventId, LocalDate.parse(occurrence)),
): Mutation =
    upsert(
        EntityTypes.EVENT_OCCURRENCE,
        id,
        JsonObject(fields("eventId" to eventId, "occurrence" to occurrence) + fields(*fields.toList().toTypedArray())),
    )

/** An upsert of [memberId]'s reminder row for [eventId] with its derived id unless [id] is given. */
fun AppDeps.newEventReminder(
    eventId: String,
    memberId: String,
    minutes: Int?,
    id: String = Ids.reminderRowId(eventId, memberId),
): Mutation = upsert(EntityTypes.EVENT_REMINDER, id, fields("eventId" to eventId, "memberId" to memberId, "minutesBefore" to minutes))
