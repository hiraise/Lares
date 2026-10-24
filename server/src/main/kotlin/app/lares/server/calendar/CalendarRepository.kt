package app.lares.server.calendar

import app.lares.contract.api.LaresJson
import app.lares.contract.entities.EventData
import app.lares.contract.entities.EventKind
import app.lares.contract.entities.EventOccurrenceData
import app.lares.contract.entities.EventReminderData
import app.lares.contract.entities.Recurrence
import app.lares.server.sync.StoredEntity
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalTime
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toKotlinLocalTime
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/** Shared reads and row mapping of the calendar tables. All functions run inside `dbQuery`. */
object CalendarRepository {
    private val idSet = SetSerializer(String.serializer())

    /** The event by id in any household (the sync handlers tell "other household" apart themselves). */
    fun load(id: UUID): StoredEntity<EventData>? =
        EventTable.selectAll().where { EventTable.id eq id }.singleOrNull()?.let {
            StoredEntity(it[EventTable.householdId], it[EventTable.deleted], toEventData(it))
        }

    /** The event of [householdId] with [id], tombstoned or not; null if missing or in another household. */
    fun get(
        householdId: UUID,
        id: UUID,
    ): StoredEntity<EventData>? = load(id)?.takeIf { it.householdId == householdId }

    /** The event [splitFrom] may point to: any event of the household, tombstoned or not. */
    fun existsInHousehold(
        householdId: UUID,
        id: UUID,
    ): Boolean = get(householdId, id) != null

    /** Ids of the live occurrence rows of an event, ordered by id. */
    fun liveOccurrenceIds(eventId: UUID): List<UUID> =
        EventOccurrenceTable
            .select(EventOccurrenceTable.id)
            .where { (EventOccurrenceTable.eventId eq eventId) and (EventOccurrenceTable.deleted eq false) }
            .orderBy(EventOccurrenceTable.id)
            .map { it[EventOccurrenceTable.id] }

    /** Ids of the live reminder rows of an event, ordered by id. */
    fun liveReminderIds(eventId: UUID): List<UUID> =
        EventReminderTable
            .select(EventReminderTable.id)
            .where { (EventReminderTable.eventId eq eventId) and (EventReminderTable.deleted eq false) }
            .orderBy(EventReminderTable.id)
            .map { it[EventReminderTable.id] }

    /** The live event of [householdId] split off from [eventId] ("this and following"), if any. */
    fun liveSplitOf(
        householdId: UUID,
        eventId: UUID,
    ): StoredEntity<EventData>? =
        EventTable
            .selectAll()
            .where {
                (EventTable.householdId eq householdId) and (EventTable.splitFrom eq eventId) and (EventTable.deleted eq false)
            }.orderBy(EventTable.id)
            .firstOrNull()
            ?.let { StoredEntity(it[EventTable.householdId], it[EventTable.deleted], toEventData(it)) }

    fun toEventData(row: ResultRow) =
        EventData(
            title = row[EventTable.title],
            createdBy = row[EventTable.createdBy].toString(),
            startDate = row[EventTable.startDate].toKotlinLocalDate(),
            endDate = row[EventTable.endDate].toKotlinLocalDate(),
            allDay = row[EventTable.allDay],
            startTime = row[EventTable.startTime]?.toKotlinLocalTime(),
            endTime = row[EventTable.endTime]?.toKotlinLocalTime(),
            place = row[EventTable.place],
            notes = row[EventTable.notes],
            participantIds = LaresJson.decodeFromString(idSet, row[EventTable.participantIds]),
            tagIds = LaresJson.decodeFromString(idSet, row[EventTable.tagIds]),
            recurrence = row[EventTable.recurrence]?.let { LaresJson.decodeFromString(Recurrence.serializer(), it) },
            repeatUntil = row[EventTable.repeatUntil]?.toKotlinLocalDate(),
            reminderMinutesBefore = row[EventTable.reminderMinutesBefore],
            kind = EventKind.fromKey(row[EventTable.kind]) ?: EventKind.EVENT,
            countYears = row[EventTable.countYears],
            splitFrom = row[EventTable.splitFrom]?.toString(),
        )

    fun writeEvent(
        it: UpdateBuilder<*>,
        data: EventData,
    ) {
        it[EventTable.title] = data.title
        it[EventTable.createdBy] = UUID.fromString(data.createdBy)
        it[EventTable.startDate] = data.startDate.toJavaLocalDate()
        it[EventTable.endDate] = data.endDate.toJavaLocalDate()
        it[EventTable.allDay] = data.allDay
        it[EventTable.startTime] = data.startTime?.toJavaLocalTime()
        it[EventTable.endTime] = data.endTime?.toJavaLocalTime()
        it[EventTable.place] = data.place
        it[EventTable.notes] = data.notes
        it[EventTable.participantIds] = LaresJson.encodeToString(idSet, data.participantIds)
        it[EventTable.tagIds] = LaresJson.encodeToString(idSet, data.tagIds)
        it[EventTable.recurrence] = data.recurrence?.let { rule -> LaresJson.encodeToString(Recurrence.serializer(), rule) }
        it[EventTable.repeatUntil] = data.repeatUntil?.toJavaLocalDate()
        it[EventTable.reminderMinutesBefore] = data.reminderMinutesBefore
        it[EventTable.kind] = data.kind.key
        it[EventTable.countYears] = data.countYears
        it[EventTable.splitFrom] = data.splitFrom?.let(UUID::fromString)
    }

    fun toOccurrenceData(row: ResultRow) =
        EventOccurrenceData(
            eventId = row[EventOccurrenceTable.eventId].toString(),
            occurrence = row[EventOccurrenceTable.occurrence].toKotlinLocalDate(),
            cancelled = row[EventOccurrenceTable.cancelled],
            startDate = row[EventOccurrenceTable.startDate]?.toKotlinLocalDate(),
            endDate = row[EventOccurrenceTable.endDate]?.toKotlinLocalDate(),
            startTime = row[EventOccurrenceTable.startTime]?.toKotlinLocalTime(),
            endTime = row[EventOccurrenceTable.endTime]?.toKotlinLocalTime(),
            title = row[EventOccurrenceTable.title],
            place = row[EventOccurrenceTable.place],
            notes = row[EventOccurrenceTable.notes],
            participantIds = row[EventOccurrenceTable.participantIds]?.let { LaresJson.decodeFromString(idSet, it) },
        )

    fun writeOccurrence(
        it: UpdateBuilder<*>,
        data: EventOccurrenceData,
    ) {
        it[EventOccurrenceTable.eventId] = UUID.fromString(data.eventId)
        it[EventOccurrenceTable.occurrence] = data.occurrence.toJavaLocalDate()
        it[EventOccurrenceTable.cancelled] = data.cancelled
        it[EventOccurrenceTable.startDate] = data.startDate?.toJavaLocalDate()
        it[EventOccurrenceTable.endDate] = data.endDate?.toJavaLocalDate()
        it[EventOccurrenceTable.startTime] = data.startTime?.toJavaLocalTime()
        it[EventOccurrenceTable.endTime] = data.endTime?.toJavaLocalTime()
        it[EventOccurrenceTable.title] = data.title
        it[EventOccurrenceTable.place] = data.place
        it[EventOccurrenceTable.notes] = data.notes
        it[EventOccurrenceTable.participantIds] = data.participantIds?.let { ids -> LaresJson.encodeToString(idSet, ids) }
    }

    fun toReminderData(row: ResultRow) =
        EventReminderData(
            eventId = row[EventReminderTable.eventId].toString(),
            memberId = row[EventReminderTable.memberId].toString(),
            minutesBefore = row[EventReminderTable.minutesBefore],
        )

    fun writeReminder(
        it: UpdateBuilder<*>,
        data: EventReminderData,
    ) {
        it[EventReminderTable.eventId] = UUID.fromString(data.eventId)
        it[EventReminderTable.memberId] = UUID.fromString(data.memberId)
        it[EventReminderTable.minutesBefore] = data.minutesBefore
    }
}
