package app.lares.contract.permissions

import app.lares.contract.calendar.EventOccurrence
import app.lares.contract.entities.EventData
import app.lares.contract.entities.EventKind
import app.lares.contract.entities.EventReminderData
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The permission table of the calendar spec for admin, Adults, Kids and view-only as creator and other member. */
class EventRulesTest {
    private val admin = EffectivePermissions(isAdmin = true, permissions = Permission.entries.toSet())
    private val adults = EffectivePermissions(isAdmin = false, permissions = PermissionCatalogue.adultsTemplate)
    private val kids =
        EffectivePermissions(isAdmin = false, permissions = PermissionCatalogue.withImplied(PermissionCatalogue.kidsTemplate))
    private val viewOnly = EffectivePermissions(isAdmin = false, permissions = setOf(Permission.CALENDAR_VIEW))
    private val noCalendar = EffectivePermissions(isAdmin = false, permissions = setOf(Permission.TASKS_VIEW, Permission.CALENDAR_CREATE))

    private fun viewer(
        member: String,
        permissions: EffectivePermissions,
    ) = Viewer(member, "p-$member", permissions)

    private val date = LocalDate(2026, 10, 28)
    private val event =
        EventData("Dentist", createdBy = "creator", startDate = date, endDate = date, allDay = true, reminderMinutesBefore = 60)

    private fun occ(participants: Set<String>) =
        EventOccurrence("e1", date, date, date, null, null, "Dentist", null, null, participants, changed = false)

    @Test
    fun `CAL-001 create needs calendar create and the viewer as creator`() {
        for ((role, permissions) in mapOf("admin" to admin, "adults" to adults, "kids" to kids)) {
            assertTrue(EventRules.canCreate(viewer("creator", permissions), event), role)
            assertFalse(EventRules.canCreate(viewer("other", permissions), event), "$role for someone else")
        }
        assertFalse(EventRules.canCreate(viewer("creator", viewOnly), event))
        assertFalse(EventRules.canCreate(viewer("creator", noCalendar), event))
    }

    @Test
    fun `CAL-015 edit by the creator or edit any`() {
        val expected =
            mapOf(
                "admin" to (admin to setOf("creator", "other")),
                "adults" to (adults to setOf("creator", "other")),
                "kids" to (kids to setOf("creator")),
                "view-only" to (viewOnly to setOf("creator")),
                "no calendar" to (noCalendar to emptySet()),
            )
        for ((role, pair) in expected) {
            val (permissions, allowed) = pair
            for (who in listOf("creator", "other")) {
                assertEquals(who in allowed, EventRules.canEdit(viewer(who, permissions), event), "$role $who")
            }
        }
    }

    @Test
    fun `CAL-006 a reminder only for oneself`() {
        for (permissions in listOf(admin, adults, kids, viewOnly)) {
            assertTrue(EventRules.canSetReminder(viewer("me", permissions), EventReminderData("e1", "me", 10)))
            assertFalse(EventRules.canSetReminder(viewer("me", permissions), EventReminderData("e1", "other", 10)))
        }
        assertFalse(EventRules.canSetReminder(viewer("me", noCalendar), EventReminderData("e1", "me", 10)))
    }

    @Test
    fun `CAL-006 my reminder for participants, the whole family and own rows`() {
        val me = viewer("me", kids)
        assertEquals(60, EventRules.myReminder(me, event, occ(setOf("p-me", "p-other")), null))
        assertNull(EventRules.myReminder(me, event, occ(setOf("p-other")), null))
        assertEquals(60, EventRules.myReminder(me, event, occ(emptySet()), null))
        assertEquals(15, EventRules.myReminder(me, event, occ(setOf("p-other")), EventReminderData("e1", "me", 15)))
        assertNull(EventRules.myReminder(me, event, occ(setOf("p-me")), EventReminderData("e1", "me", null)))
        val memorable = event.copy(kind = EventKind.MEMORABLE_DATE, reminderMinutesBefore = null)
        assertNull(EventRules.myReminder(me, memorable, occ(emptySet()), EventReminderData("e1", "me", 15)))
    }
}
