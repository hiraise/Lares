package app.lares.server.calendar

import app.lares.contract.calendar.EventOccurrence
import app.lares.contract.calendar.EventSchedule
import app.lares.contract.entities.EventData
import app.lares.contract.entities.EventKind
import app.lares.contract.entities.EventOccurrenceData
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import java.util.UUID
import kotlin.time.Instant

/**
 * Which event notices a calendar write causes (CAL-007, architecture/calendar.md § Notifications — the table there is the
 * specification). Pure: the handlers pass the stored and new data, the household's live members by person id, `now` and
 * the household zone. Recipients are not filtered here beyond participation; the notifier drops the actor, deleted
 * members and members without `calendar.view`.
 */
object EventChanges {
    enum class Kind { ADDED, CHANGED, CANCELLED }

    /** One notice to [memberIds] about [occurrence]; [fromDate] marks a series cut short («from …»). */
    data class Notice(
        val kind: Kind,
        val occurrence: EventOccurrence,
        val memberIds: Set<UUID>,
        val fromDate: Boolean = false,
    )

    /** How far [cutOccurrence] looks ahead; the same as `EventSchedule.next`. */
    private const val LOOKAHEAD_DAYS = 400

    /** Person ids → live member ids; an empty set means the whole family, so every live member. */
    fun memberParticipants(
        participantIds: Set<String>,
        membersByPerson: Map<String, UUID>,
    ): Set<UUID> =
        if (participantIds.isEmpty()) {
            membersByPerson.values.toSet()
        } else {
            participantIds.mapNotNullTo(mutableSetOf()) { membersByPerson[it] }
        }

    /** Any field that moves occurrences or their place differs. */
    fun scheduleChanged(
        old: EventData,
        new: EventData,
    ): Boolean =
        old.startDate != new.startDate ||
            old.endDate != new.endDate ||
            old.allDay != new.allDay ||
            old.startTime != new.startTime ||
            old.endTime != new.endTime ||
            old.place != new.place ||
            old.recurrence != new.recurrence ||
            old.repeatUntil != new.repeatUntil

    /** The only difference is `repeatUntil`, set from none or moved earlier: the series was cut short. */
    fun onlyUntilEarlier(
        old: EventData,
        new: EventData,
    ): Boolean {
        val until = new.repeatUntil ?: return false
        val before = old.repeatUntil
        return (before == null || until < before) && old.copy(repeatUntil = until) == new
    }

    /** Effective times or place of an occurrence differ. */
    fun timesOrPlaceDiffer(
        a: EventOccurrence,
        b: EventOccurrence,
    ): Boolean =
        a.startDate != b.startDate || a.endDate != b.endDate || a.startTime != b.startTime || a.endTime != b.endTime || a.place != b.place

    /** The first occurrence of the [old] series after [newUntil] that has not ended: what a cut removes first. */
    fun cutOccurrence(
        eventId: String,
        old: EventData,
        rows: List<EventOccurrenceData>,
        newUntil: LocalDate,
        now: Instant,
        zone: TimeZone,
    ): EventOccurrence? {
        val today = now.toLocalDateTime(zone).date
        val span = EventSchedule.spanDays(old)
        return EventSchedule
            .occurrences(eventId, old, rows, today.minus(span, DateTimeUnit.DAY), today.plus(LOOKAHEAD_DAYS, DateTimeUnit.DAY))
            .firstOrNull { it.occurrence > newUntil && EventSchedule.endMoment(it, zone) > now }
    }

    /** A new event; [origin] is the series it was split from (`splitFrom`), if any. */
    fun onCreate(
        eventId: String,
        event: EventData,
        origin: EventData?,
        membersByPerson: Map<String, UUID>,
        now: Instant,
        zone: TimeZone,
    ): List<Notice> {
        if (event.kind == EventKind.MEMORABLE_DATE) return emptyList()
        val next = EventSchedule.next(eventId, event, emptyList(), now, zone) ?: return emptyList()
        val members = memberParticipants(event.participantIds, membersByPerson)
        if (origin == null) return notices(Notice(Kind.ADDED, next, members))
        val known = members intersect memberParticipants(origin.participantIds, membersByPerson)
        return notices(Notice(Kind.CHANGED, next, known), Notice(Kind.ADDED, next, members - known))
    }

    /** An edit of a stored event; [hasLiveSplit] tells whether a live event was split off from this one. */
    fun onUpdate(
        eventId: String,
        old: EventData,
        new: EventData,
        rows: List<EventOccurrenceData>,
        hasLiveSplit: () -> Boolean,
        membersByPerson: Map<String, UUID>,
        now: Instant,
        zone: TimeZone,
    ): List<Notice> {
        if (new.kind == EventKind.MEMORABLE_DATE) return emptyList()
        if (onlyUntilEarlier(old, new)) {
            // The second half of a split: the create with `splitFrom` already told everyone.
            if (hasLiveSplit()) return emptyList()
            val cut = cutOccurrence(eventId, old, rows, new.repeatUntil!!, now, zone) ?: return emptyList()
            return notices(Notice(Kind.CANCELLED, cut, memberParticipants(old.participantIds, membersByPerson), fromDate = true))
        }
        val next = EventSchedule.next(eventId, new, rows, now, zone) ?: return emptyList()
        val before = memberParticipants(old.participantIds, membersByPerson)
        val after = memberParticipants(new.participantIds, membersByPerson)
        val changed = if (scheduleChanged(old, new)) Notice(Kind.CHANGED, next, after intersect before) else null
        return notices(Notice(Kind.ADDED, next, after - before), changed)
    }

    /** The stored event is being deleted. */
    fun onDelete(
        eventId: String,
        stored: EventData,
        rows: List<EventOccurrenceData>,
        membersByPerson: Map<String, UUID>,
        now: Instant,
        zone: TimeZone,
    ): List<Notice> {
        if (stored.kind == EventKind.MEMORABLE_DATE) return emptyList()
        val next = EventSchedule.next(eventId, stored, rows, now, zone) ?: return emptyList()
        return notices(Notice(Kind.CANCELLED, next, memberParticipants(stored.participantIds, membersByPerson)))
    }

    /** An occurrence row of [event] written; [oldRow] is the stored row, if any. */
    fun onOccurrenceRow(
        eventId: String,
        event: EventData,
        oldRow: EventOccurrenceData?,
        newRow: EventOccurrenceData,
        membersByPerson: Map<String, UUID>,
        now: Instant,
        zone: TimeZone,
    ): List<Notice> {
        if (event.kind == EventKind.MEMORABLE_DATE || isEmpty(newRow)) return emptyList()
        val date = newRow.occurrence
        val before = EventSchedule.occurrence(eventId, event, listOfNotNull(oldRow), date) ?: return emptyList()
        val beforeMembers = memberParticipants(before.participantIds, membersByPerson)
        val beforeUpcoming = EventSchedule.endMoment(before, zone) > now
        val after =
            EventSchedule.occurrence(eventId, event, listOf(newRow), date)
                ?: return if (beforeUpcoming) notices(Notice(Kind.CANCELLED, before, beforeMembers)) else emptyList()
        val afterMembers = memberParticipants(after.participantIds, membersByPerson)
        val afterUpcoming = EventSchedule.endMoment(after, zone) > now
        val added = if (afterUpcoming) Notice(Kind.ADDED, after, afterMembers - beforeMembers) else null
        val changed =
            if (timesOrPlaceDiffer(before, after) && (beforeUpcoming || afterUpcoming)) {
                Notice(Kind.CHANGED, after, afterMembers intersect beforeMembers)
            } else {
                null
            }
        return notices(added, changed)
    }

    /** A reset row: not cancelled and no override. */
    private fun isEmpty(row: EventOccurrenceData): Boolean =
        !row.cancelled &&
            row.startDate == null &&
            row.endDate == null &&
            row.startTime == null &&
            row.endTime == null &&
            row.title == null &&
            row.place == null &&
            row.notes == null &&
            row.participantIds == null

    private fun notices(vararg candidates: Notice?): List<Notice> = candidates.filterNotNull().filter { it.memberIds.isNotEmpty() }
}
