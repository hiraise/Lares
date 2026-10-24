package app.lares.contract.permissions

import app.lares.contract.calendar.EventOccurrence
import app.lares.contract.entities.EventData
import app.lares.contract.entities.EventKind
import app.lares.contract.entities.EventReminderData

/**
 * Entity-level write rules of the calendar (calendar spec § Permissions and privacy, architecture/calendar.md § Write
 * rules). Called by the server's sync handlers and by the client's adapters; [WriteRules.canMutate] is only the coarse
 * `calendar.view` gate.
 */
object EventRules {
    /** Create an event or a memorable date: `calendar.create`, with the viewer as creator. */
    fun canCreate(
        viewer: Viewer,
        event: EventData,
    ): Boolean = viewer.sees() && viewer.permissions.has(Permission.CALENDAR_CREATE) && event.createdBy == viewer.memberId

    /**
     * Edit, delete or split the event and write its occurrence rows: the creator or `calendar.edit_any`.
     */
    fun canEdit(
        viewer: Viewer,
        event: EventData,
    ): Boolean = viewer.sees() && (event.createdBy == viewer.memberId || viewer.permissions.has(Permission.CALENDAR_EDIT_ANY))

    /** "My reminder": everybody who sees the calendar, for themselves only (ADR-0023). */
    fun canSetReminder(
        viewer: Viewer,
        reminder: EventReminderData,
    ): Boolean = viewer.sees() && reminder.memberId == viewer.memberId

    /**
     * The viewer's reminder for [occ] in minutes before (CAL-006, Q-021): their own row if present, else the event's
     * reminder when the occurrence concerns the whole family or the viewer; null for none. Memorable dates follow the
     * birthday rules instead and return null.
     */
    fun myReminder(
        viewer: Viewer,
        event: EventData,
        occ: EventOccurrence,
        own: EventReminderData?,
    ): Int? {
        if (event.kind == EventKind.MEMORABLE_DATE) return null
        if (own != null) return own.minutesBefore
        val concernsMe = occ.participantIds.isEmpty() || viewer.personId in occ.participantIds
        return if (concernsMe) event.reminderMinutesBefore else null
    }

    private fun Viewer.sees(): Boolean = permissions.has(Permission.CALENDAR_VIEW)
}
