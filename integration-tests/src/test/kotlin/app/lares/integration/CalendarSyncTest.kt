package app.lares.integration

import app.lares.client.auth.SessionState
import app.lares.client.data.calendar.OccurrenceChanges
import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.admin.CreateGroupRequest
import app.lares.contract.api.admin.SetGroupsRequest
import app.lares.contract.api.admin.UpdateGroupRequest
import app.lares.contract.entities.EventData
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.PermissionCatalogue
import app.lares.contract.sync.EntityTypes
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** The calendar on several phones against the real server (plan M3-04, ADR-0022, ADR-0023). */
class CalendarSyncTest {
    private fun today(): LocalDate =
        Clock.System
            .now()
            .toLocalDateTime(TimeZone.of("Europe/Moscow"))
            .date

    private fun day(n: Int) = today().plus(n, DateTimeUnit.DAY)

    /** A daily series from tomorrow, 17:00–18:00, for the whole family. */
    private fun daily(title: String = "Dance") =
        EventData(
            title,
            createdBy = "",
            startDate = day(1),
            endDate = day(1),
            startTime = LocalTime(17, 0),
            endTime = LocalTime(18, 0),
            place = "Hall",
            recurrence = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.DAY),
        )

    private val TestClient.memberId: String
        get() = (session.state.value as SessionState.LoggedIn).me.memberId

    private suspend fun TestClient.occurrence(
        id: String,
        date: LocalDate,
    ) = calendar.observeDetail(id, date).first()!!.occ!!

    @Test
    fun `CAL-015 two phones change different fields of one occurrence offline`() =
        familyTest {
            val id = anna.calendarWriter.create(daily())
            anna.sync()
            boris.sync()
            anna.network.online = false
            boris.network.online = false
            anna.calendarWriter.editOccurrence(id, day(3), OccurrenceChanges(place = "Hall 2"))
            boris.calendarWriter.editOccurrence(id, day(3), OccurrenceChanges(title = "Dance (open lesson)"))

            anna.network.online = true
            boris.network.online = true
            assertConverged(all)
            val rows = anna.snapshot().eventOccurrences
            assertEquals(setOf(Ids.occurrenceRowId(id, day(3))), rows.keys)
            all.forEach {
                val occ = it.occurrence(id, day(3))
                assertEquals("Hall 2", occ.place)
                assertEquals("Dance (open lesson)", occ.title)
            }
        }

    @Test
    fun `CAL-015 a series time change and a cancelled occurrence offline both survive`() =
        familyTest {
            val id = anna.calendarWriter.create(daily())
            anna.sync()
            boris.sync()
            anna.network.online = false
            boris.network.online = false
            val series =
                anna.calendar
                    .observeDetail(id)
                    .first()!!
                    .event
            anna.calendarWriter.update(id, series.copy(startTime = LocalTime(18, 0), endTime = LocalTime(19, 0)))
            boris.calendarWriter.cancelOccurrence(id, day(2))

            anna.network.online = true
            boris.network.online = true
            assertConverged(all)
            all.forEach {
                assertEquals(LocalTime(18, 0), it.occurrence(id, day(3)).startTime)
                val days = it.calendar.observeDays(day(1), day(3)).first()
                assertEquals(listOf(1, 0, 1), days.map { d -> d.items.size })
            }
        }

    @Test
    fun `CAL-015 a split on one phone while the other is offline converges`() =
        familyTest {
            val id = anna.calendarWriter.create(daily())
            anna.sync()
            boris.sync()
            boris.network.online = false
            boris.calendarWriter.editOccurrence(id, day(5), OccurrenceChanges(place = "Garden"))
            val series =
                anna.calendar
                    .observeDetail(id)
                    .first()!!
                    .event
            val newId =
                anna.calendarWriter.editFollowing(
                    id,
                    day(4),
                    series.copy(startDate = day(4), endDate = day(4), startTime = LocalTime(19, 0), endTime = LocalTime(20, 0)),
                )
            anna.sync()

            boris.network.online = true
            assertConverged(all)
            val events = anna.snapshot().events
            assertEquals(day(3), events.getValue(id).repeatUntil)
            assertEquals(id, events.getValue(newId).splitFrom)
            all.forEach {
                val items =
                    it.calendar
                        .observeDays(day(1), day(6))
                        .first()
                        .map { d -> d.items.size }
                assertEquals(List(6) { 1 }, items)
                assertEquals(LocalTime(19, 0), it.occurrence(newId, day(5)).startTime)
            }
        }

    @Test
    fun `CAL-017 an event deleted while another phone edits an occurrence offline is gone everywhere`() =
        familyTest {
            val id = anna.calendarWriter.create(daily())
            anna.sync()
            boris.sync()
            boris.network.online = false
            boris.calendarWriter.editOccurrence(id, day(2), OccurrenceChanges(title = "Moved"))
            anna.calendarWriter.delete(id)
            anna.sync()

            boris.network.online = true
            assertConverged(all)
            all.forEach {
                val snapshot = it.snapshot()
                assertTrue(snapshot.events.isEmpty() && snapshot.eventOccurrences.isEmpty())
                assertNull(it.calendar.observeDetail(id).first())
            }
        }

    @Test
    fun `CAL-006 a personal reminder reaches only its member`() =
        familyTest {
            val id = anna.calendarWriter.create(daily().copy(reminderMinutesBefore = 15))
            anna.sync()
            boris.sync()
            boris.calendarWriter.setMyReminder(id, 60)
            boris.sync()
            assertConverged(all)
            assertEquals(setOf(Ids.reminderRowId(id, boris.memberId)), boris.snapshot().eventReminders.keys)
            assertTrue(anna.snapshot().eventReminders.isEmpty())
            assertTrue(anna.serverSnapshot().eventReminders.isEmpty())
            assertEquals(
                60,
                boris.calendar
                    .observeDetail(id)
                    .first()!!
                    .myReminder,
            )
            assertEquals(
                15,
                anna.calendar
                    .observeDetail(id)
                    .first()!!
                    .myReminder,
            )
            // The cascade tombstones reach everybody.
            anna.calendarWriter.delete(id)
            anna.sync()
            assertConverged(all)
            assertTrue(boris.snapshot().eventReminders.isEmpty())
        }

    @Test
    fun `3 - a kid's edit of an adult's event allowed by stale permissions is rejected and reverted`() =
        familyTest {
            anna.api.updateGroup(kidsId, UpdateGroupRequest(permissions = PermissionCatalogue.kidsTemplate + Permission.CALENDAR_EDIT_ANY))
            val id = boris.calendarWriter.create(daily("Football"))
            boris.sync()
            all.forEach { it.sync() }
            carol.permissionsRepository.observeMine().await { it.has(Permission.CALENDAR_EDIT_ANY) }

            carol.network.online = false
            anna.api.updateGroup(kidsId, UpdateGroupRequest(permissions = PermissionCatalogue.kidsTemplate))
            val series =
                carol.calendar
                    .observeDetail(id)
                    .first()!!
                    .event
            carol.calendarWriter.update(id, series.copy(title = "Ice cream"))
            assertEquals(
                "Ice cream",
                carol.calendar
                    .observeDetail(id)
                    .first()!!
                    .event.title,
            )

            carol.network.online = true
            carol.sync()
            assertEquals(
                "Football",
                carol.calendar
                    .observeDetail(id)
                    .first()!!
                    .event.title,
            )
            val rejection =
                carol.engine.rejections
                    .first()
                    .single()
            assertEquals(ErrorCode.FORBIDDEN, rejection.code)
            assertEquals(EntityTypes.EVENT, rejection.type)
            assertConverged(all)
        }

    @Test
    fun `CAL-017 a member without calendar view has no event rows after the reset`() =
        familyTest {
            val id = anna.calendarWriter.create(daily())
            anna.calendarWriter.cancelOccurrence(id, day(2))
            anna.sync()
            carol.sync()
            assertEquals(1, carol.snapshot().events.size)

            val guests = anna.api.createGroup(CreateGroupRequest("Guests", setOf(Permission.TASKS_VIEW))).groupId
            anna.api.setMemberGroups(carolMemberId, SetGroupsRequest(setOf(guests)))
            carol.sync()

            val snapshot = carol.snapshot()
            assertTrue(snapshot.events.isEmpty() && snapshot.eventOccurrences.isEmpty() && snapshot.eventReminders.isEmpty())
            assertEquals(1, anna.snapshot().events.size)
        }
}
