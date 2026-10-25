package app.lares.client.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import app.lares.client.ui.CalendarUiFixture.E_ANNIV
import app.lares.client.ui.CalendarUiFixture.E_DANCE
import app.lares.client.ui.CalendarUiFixture.E_DENTIST
import app.lares.client.ui.CalendarUiFixture.E_HOLIDAY
import app.lares.client.ui.calendar.EventActions
import app.lares.client.ui.calendar.EventArgs
import app.lares.client.ui.calendar.EventScreen
import app.lares.client.ui.calendar.EventUiState
import app.lares.client.ui.calendar.EventViewModel
import app.lares.client.ui.calendar.RepeatScope
import app.lares.client.ui.theme.LaresTheme
import app.lares.contract.api.auth.Me
import kotlinx.datetime.LocalDate
import org.koin.core.parameter.parametersOf
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The event page (plan M3-05, CAL-014, CAL-006, CAL-015): facts, my reminder, scope choices, rights. */
@OptIn(ExperimentalTestApi::class)
class EventScreenTest {
    @BeforeTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    private fun state(
        id: String,
        occurrence: LocalDate? = null,
        me: Me = FamilyFixture.olga,
    ): EventUiState =
        CalendarUiFixture.core(me).use { core ->
            val vm = core.koin.get<EventViewModel> { parametersOf(EventArgs(id, occurrence)) }
            awaitState(vm.state) { it.detail != null }
        }

    /** The row tagged [tag] shows [text] (rows that are not clickable keep their texts in child nodes). */
    private fun ComposeUiTest.has(
        tag: String,
        text: String,
    ) {
        onNodeWithTag(tag).assert(hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true)))
    }

    private fun ComposeUiTest.show(
        state: EventUiState,
        actions: EventActions = EventActions(),
    ) = setContent { LaresTheme(darkTheme = false) { EventScreen(state, actions) } }

    @Test
    fun `a timed one-off - facts, my reminder sheet, delete confirmation`() =
        runComposeUiTest {
            val calls = mutableListOf<String>()
            show(
                state(E_DENTIST),
                EventActions(onMyReminder = { calls += "remind:$it" }, onDelete = { calls += "delete:$it" }),
            )
            onNodeWithTag("event_title").assertTextContains("Dentist")
            has("event_when", "16:00–17:00")
            has("event_place", "Clinic")
            has("event_participants", "Carol")
            has("event_reminder", "None (as in the event)")
            onNodeWithText("Created by Olga", useUnmergedTree = true).assertExists()
            onNodeWithTag("event_rule").assertDoesNotExist()
            onNodeWithTag("event_changed_note").assertDoesNotExist()

            onNodeWithTag("event_reminder").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("my_reminder_120")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("my_reminder_event").assertDoesNotExist()
            onNodeWithTag("my_reminder_120").performClick()

            onNodeWithTag("event_more").performClick()
            onNodeWithTag("event_delete").performClick()
            onNodeWithText("Delete the event «Dentist»?").assertExists()
            onNode(hasText("Delete") and !hasText("Delete the event «Dentist»?")).performClick()
            assertEquals(listOf("remind:120", "delete:ALL"), calls)
        }

    @Test
    fun `my reminder sheet offers «as in the event» to participants`() =
        runComposeUiTest {
            var asEvent = 0
            show(state(E_DENTIST, me = FamilyFixture.carol), EventActions(onAsEvent = { asEvent++ }))
            has("event_reminder", "1 hour before (as in the event)")
            onNodeWithTag("event_edit").assertDoesNotExist()
            onNodeWithTag("event_more").assertDoesNotExist()
            onNodeWithTag("event_reminder").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("my_reminder_event")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("my_reminder_event").performClick()
            assertEquals(1, asEvent)
        }

    @Test
    fun `an all-day holiday over several days`() =
        runComposeUiTest {
            show(state(E_HOLIDAY))
            has("event_when", " – ")
            has("event_participants", "Whole family")
            onNodeWithTag("event_tag_t1", useUnmergedTree = true).assertExists()
        }

    @Test
    fun `a repeating event with an end - changed note and scope dialogs`() =
        runComposeUiTest {
            val calls = mutableListOf<String>()
            show(
                state(E_DANCE, CalendarUiFixture.week),
                EventActions(onEdit = { calls += "edit:$it" }, onDelete = { calls += "delete:$it" }),
            )
            onNodeWithTag("event_changed_note").assertExists()
            has("event_when", "18:00–19:15")
            has("event_rule", "until")

            for (scope in RepeatScope.entries) {
                onNodeWithTag("event_more").performClick()
                onNodeWithTag("event_delete").performClick()
                onNodeWithText("Delete repeating event").assertExists()
                onNodeWithTag("scope_${scope.name}").performClick()
                onNodeWithTag("scope_confirm").performClick()
            }
            onNodeWithTag("event_edit").performClick()
            onNodeWithText("Edit repeating event").assertExists()
            onNodeWithTag("scope_FOLLOWING").performClick()
            onNodeWithTag("scope_confirm").performClick()
            assertEquals(listOf("delete:THIS", "delete:FOLLOWING", "delete:ALL", "edit:FOLLOWING"), calls)
        }

    @Test
    fun `a memorable date - yearly, years, birthday reminders`() =
        runComposeUiTest {
            val calls = mutableListOf<String>()
            show(state(E_ANNIV), EventActions(onEdit = { calls += "edit:$it" }))
            has("event_when", "every year")
            has("event_when", "years")
            has("event_reminder", "Like birthdays, in Settings")
            onNodeWithTag("event_edit").performClick()
            assertEquals(listOf("edit:ALL"), calls, "no scope dialog for memorable dates")
        }
}
