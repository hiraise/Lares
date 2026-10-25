package app.lares.client.ui

import app.lares.client.ui.FamilyFixture.change
import app.lares.contract.Ids
import app.lares.contract.api.auth.Me
import app.lares.contract.entities.EventData
import app.lares.contract.entities.EventKind
import app.lares.contract.entities.EventOccurrenceData
import app.lares.contract.entities.EventReminderData
import app.lares.contract.entities.GroupData
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.PersonData
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.PermissionCatalogue
import app.lares.contract.sync.Change
import app.lares.contract.sync.EntityTypes
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Calendar data of the [FamilyFixture] household (Moscow), dated relative to the real clock, plus the tasks of
 * [TaskUiFixture] (overdue "Pay internet", today's "Pick up Carol", tomorrow's "Doctor"):
 * - `e-dentist` "Dentist": in 2 days 16:00–17:00 for Carol at "Clinic", reminder 1 hour, by Olga;
 * - `e-dance` "Dance": weekly on today's weekday 17:30–18:45 since two weeks ago, until in 8 weeks, for Carol, by Olga;
 *   the occurrence in a week is moved to 18:00–19:15;
 * - `e-holiday` "Holiday": all day from in 3 days to in 6 days, the whole family, tag `t1`, by Boris;
 * - `e-anniv` "Wedding anniversary": memorable date tomorrow since 2010, Olga and Boris;
 * - Grandma (p4) has her birthday today, born 1948.
 */
object CalendarUiFixture {
    const val E_DENTIST = "01928c4e-0000-7000-8000-00000000e001"
    const val E_DANCE = "01928c4e-0000-7000-8000-00000000e002"
    const val E_HOLIDAY = "01928c4e-0000-7000-8000-00000000e003"
    const val E_ANNIV = "01928c4e-0000-7000-8000-00000000e004"

    val today = TaskUiFixture.today
    val week: LocalDate = today.plus(7, DateTimeUnit.DAY)

    private fun day(n: Int) = today.plus(n, DateTimeUnit.DAY)

    val events =
        listOf(
            E_DENTIST to
                EventData(
                    "Dentist",
                    "m1",
                    day(2),
                    day(2),
                    startTime = LocalTime(16, 0),
                    endTime = LocalTime(17, 0),
                    place = "Clinic",
                    participantIds = setOf("p3"),
                    reminderMinutesBefore = 60,
                ),
            E_DANCE to
                EventData(
                    "Dance",
                    "m1",
                    today.minus(14, DateTimeUnit.DAY),
                    today.minus(14, DateTimeUnit.DAY),
                    startTime = LocalTime(17, 30),
                    endTime = LocalTime(18, 45),
                    participantIds = setOf("p3"),
                    recurrence = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.WEEK, 1, setOf(today.dayOfWeek)),
                    repeatUntil = day(56),
                ),
            E_HOLIDAY to
                EventData("Holiday", "m2", day(3), day(6), allDay = true, tagIds = setOf("t1")),
            E_ANNIV to
                EventData(
                    "Wedding anniversary",
                    "m1",
                    LocalDate(2010, day(1).month, day(1).day),
                    LocalDate(2010, day(1).month, day(1).day),
                    allDay = true,
                    participantIds = setOf("p1", "p2"),
                    recurrence = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.YEAR),
                    kind = EventKind.MEMORABLE_DATE,
                    countYears = true,
                ),
        )

    val changes: List<Change> =
        TaskUiFixture.changes +
            events.map { (id, data) -> change(EntityTypes.EVENT, id, EventData.serializer(), data) } +
            change(
                EntityTypes.EVENT_OCCURRENCE,
                Ids.occurrenceRowId(E_DANCE, week),
                EventOccurrenceData.serializer(),
                EventOccurrenceData(
                    E_DANCE,
                    week,
                    startDate = week,
                    endDate = week,
                    startTime = LocalTime(18, 0),
                    endTime = LocalTime(19, 15),
                ),
            ) +
            change(
                EntityTypes.PERSON,
                "p4",
                PersonData.serializer(),
                PersonData(
                    "Grandma",
                    birthYear = 1948,
                    birthMonth = today.month.ordinal + 1,
                    birthDay = today.day,
                    relation = "grandmother",
                    color = PaletteColor.ORANGE,
                ),
            )

    /** Kids without `calendar.create`. */
    val kidsCannotCreate =
        change(
            EntityTypes.GROUP,
            FamilyFixture.KIDS,
            GroupData.serializer(),
            GroupData("Kids", null, PermissionCatalogue.kidsTemplate - Permission.CALENDAR_CREATE, setOf("m3")),
        )

    fun reminder(
        eventId: String,
        memberId: String,
        minutes: Int?,
    ) = change(
        EntityTypes.EVENT_REMINDER,
        Ids.reminderRowId(eventId, memberId),
        EventReminderData.serializer(),
        EventReminderData(eventId, memberId, minutes),
    )

    fun core(
        me: Me = FamilyFixture.olga,
        vararg extra: Change,
    ): UiTestCore = FamilyFixture.core(me, changes + extra)
}
