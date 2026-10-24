package app.lares.server.calendar

import app.lares.server.household.MemberTable
import app.lares.server.platform.db.HouseholdTable
import app.lares.server.sync.SyncColumns
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.time
import org.jetbrains.exposed.v1.javatime.timestamp

// Tables of the calendar module (V8__calendar.sql, architecture/calendar.md § Server › Tables).

/** `event`: an event or a memorable date (ADR-0022). Id sets and the repeat rule are JSON text encoded with `LaresJson`. */
object EventTable : Table("event") {
    val id = javaUUID("id")
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val createdBy = javaUUID("created_by").references(MemberTable.id)
    val title = text("title")
    val startDate = date("start_date")
    val endDate = date("end_date")
    val allDay = bool("all_day").default(false)
    val startTime = time("start_time").nullable()
    val endTime = time("end_time").nullable()
    val place = text("place").nullable()
    val notes = text("notes").nullable()
    val participantIds = text("participant_ids").default("[]")
    val tagIds = text("tag_ids").default("[]")
    val recurrence = text("recurrence").nullable()
    val repeatUntil = date("repeat_until").nullable()
    val reminderMinutesBefore = integer("reminder_minutes_before").nullable()
    val kind = text("kind").default("event")
    val countYears = bool("count_years").default(false)
    val splitFrom = javaUUID("split_from").nullable()
    val seq = long("seq")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)

    val sync = SyncColumns(this, householdId, seq, deleted)
}

/** `event_occurrence`: a changed or cancelled occurrence of a repeating event; id derived from event and date. */
object EventOccurrenceTable : Table("event_occurrence") {
    val id = javaUUID("id")
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val eventId = javaUUID("event_id").references(EventTable.id, onDelete = ReferenceOption.CASCADE)
    val occurrence = date("occurrence")
    val cancelled = bool("cancelled").default(false)
    val startDate = date("start_date").nullable()
    val endDate = date("end_date").nullable()
    val startTime = time("start_time").nullable()
    val endTime = time("end_time").nullable()
    val title = text("title").nullable()
    val place = text("place").nullable()
    val notes = text("notes").nullable()
    val participantIds = text("participant_ids").nullable()
    val seq = long("seq")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)

    val sync = SyncColumns(this, householdId, seq, deleted)
}

/** `event_reminder`: one member's own reminder for an event (ADR-0023); id derived from event and member. */
object EventReminderTable : Table("event_reminder") {
    val id = javaUUID("id")
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val eventId = javaUUID("event_id").references(EventTable.id, onDelete = ReferenceOption.CASCADE)
    val memberId = javaUUID("member_id").references(MemberTable.id)
    val minutesBefore = integer("minutes_before").nullable()
    val seq = long("seq")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)

    val sync = SyncColumns(this, householdId, seq, deleted)
}
