package app.lares.client.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import app.lares.client.ui.calendar.EventEditorActions
import app.lares.client.ui.calendar.EventEditorScreen
import app.lares.client.ui.calendar.EventEditorUiState
import app.lares.client.ui.calendar.EventForm
import app.lares.client.ui.calendar.EventReminderOptions
import app.lares.client.ui.calendar.ParticipantChoice
import app.lares.client.ui.calendar.RepeatScope
import app.lares.client.ui.common.RepeatPreset
import app.lares.client.ui.theme.LaresTheme
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.entities.EventData
import app.lares.contract.entities.PaletteColor
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EventEditorUiTest {
    @BeforeTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    private val today = LocalDate(2026, 10, 25)
    private val tue = LocalDate(2026, 10, 27)

    private val persons =
        listOf(
            ParticipantChoice("p1", "Olga", PaletteColor.PINK, null, member = true),
            ParticipantChoice("p3", "Carol", PaletteColor.PURPLE, "daughter", member = true),
        )

    private fun newState(form: EventForm = EventForm.new(tue, 16)) =
        EventEditorUiState(loaded = true, form = form, initial = form, today = today, participants = persons, canCreate = true)

    private fun dance(): EventData =
        EventForm
            .new(tue, 17)
            .withTitle("Dance")
            .withPreset(RepeatPreset.WEEKLY)
            .toData("m1")

    @Test
    fun `a new timed event shows its rows, times and the whole family`() =
        runComposeUiTest {
            setContent { LaresTheme(darkTheme = false) { EventEditorScreen(newState(), EventEditorActions()) } }
            onNodeWithTag("event_kind").assertIsDisplayed()
            onNodeWithText("New event").assertIsDisplayed()
            onNodeWithText("For example, Dentist").assertIsDisplayed()
            onNodeWithTag("all_day").assertIsDisplayed()
            onNodeWithTag("field_start").assertTextContains("Tue, Oct 27")
            onNodeWithTag("start_time").assertTextContains("16:00")
            onNodeWithTag("end_time").assertTextContains("17:00")
            onNodeWithTag("field_until").assertDoesNotExist()
            onNodeWithTag("field_participants").assertTextContains("Whole family")
            onNodeWithTag("field_reminder").assertTextContains("None")
            onNodeWithTag("event_save").assertIsNotEnabled()
            onNodeWithTag("scope_note").assertDoesNotExist()
        }

    @Test
    fun `an all-day repeating event hides the times and shows Until`() =
        runComposeUiTest {
            val form =
                EventForm
                    .new(tue, 9)
                    .withTitle("Camp")
                    .withAllDay(true)
                    .withPreset(RepeatPreset.WEEKLY)
                    .withReminder(1440)
                    .toggleParticipant("p3")
            setContent { LaresTheme(darkTheme = false) { EventEditorScreen(newState(form), EventEditorActions()) } }
            onNodeWithTag("start_time").assertDoesNotExist()
            onNodeWithTag("field_until").assertTextContains("No end")
            onNodeWithTag("field_participants").assertTextContains("Carol")
            onNodeWithTag("field_reminder").assertTextContains("participants", substring = true)
        }

    @Test
    fun `only this hides repeat, reminder, tags, all day and the type, with a note`() =
        runComposeUiTest {
            val form = EventForm.of(dance(), today)
            val state =
                EventEditorUiState(
                    loaded = true,
                    isNew = false,
                    scope = RepeatScope.THIS,
                    occurrence = tue,
                    form = form,
                    initial = form,
                    today = today,
                    canEdit = true,
                )
            setContent { LaresTheme(darkTheme = false) { EventEditorScreen(state, EventEditorActions()) } }
            onNodeWithText("Edit event").assertIsDisplayed()
            onNodeWithTag("event_kind").assertDoesNotExist()
            onNodeWithTag("all_day").assertDoesNotExist()
            onNodeWithTag("field_repeat").assertDoesNotExist()
            onNodeWithTag("field_until").assertDoesNotExist()
            onNodeWithTag("field_reminder").assertDoesNotExist()
            onNodeWithTag("field_tags").assertDoesNotExist()
            onNodeWithTag("field_participants").assertExists()
            onNodeWithTag("scope_note").assertTextContains("Changes only for Tuesday, October 27")
        }

    @Test
    fun `this and following keeps the repeat rows and says from when`() =
        runComposeUiTest {
            val form = EventForm.of(dance(), today)
            val state =
                EventEditorUiState(
                    loaded = true,
                    isNew = false,
                    scope = RepeatScope.FOLLOWING,
                    occurrence = tue,
                    form = form,
                    initial = form,
                    today = today,
                    canEdit = true,
                )
            setContent { LaresTheme(darkTheme = false) { EventEditorScreen(state, EventEditorActions()) } }
            onNodeWithTag("field_repeat").assertExists()
            onNodeWithTag("field_until").assertExists()
            onNodeWithTag("scope_note").assertTextContains("Changes from Tuesday, October 27")
        }

    @Test
    fun `a memorable date shows the date, years and the birthday hint`() =
        runComposeUiTest {
            val form = EventForm.new(tue, 9, memorable = true).withMemorableDate(12, 6, 2010, today).withCountYears(true)
            setContent { LaresTheme(darkTheme = false) { EventEditorScreen(newState(form), EventEditorActions()) } }
            onNodeWithText("For example, Wedding anniversary").assertIsDisplayed()
            onNodeWithTag("field_date").assertTextContains("June 12, 2010")
            onNodeWithTag("count_years").assertTextContains("16 years this year")
            onNodeWithText("We’ll remind you as we do for birthdays").assertExists()
            onNodeWithTag("field_start").assertDoesNotExist()
            onNodeWithTag("field_repeat").assertDoesNotExist()
            onNodeWithTag("field_reminder").assertDoesNotExist()
            onNodeWithTag("event_place").assertDoesNotExist()
        }

    @Test
    fun `field errors and the reset question are shown`() =
        runComposeUiTest {
            val form = EventForm.new(tue, 9).withTitle("Holiday")
            val state =
                newState(form).copy(
                    fieldErrors =
                        EventForm.fieldErrors(
                            mapOf(
                                "endDate" to FieldErrorCode.OUT_OF_RANGE,
                                "repeatUntil" to FieldErrorCode.OUT_OF_RANGE,
                            ),
                        ),
                    confirmReset = true,
                    changedCount = 2,
                )
            setContent {
                LaresTheme(
                    darkTheme = false,
                ) { EventEditorScreen(state, EventEditorActions()) }
            }
            onNodeWithTag("error_end").assertTextContains("The event is too long")
            onNodeWithTag("error_until").assertTextContains("Before the start")
            onNodeWithText("Changed single occurrences (2) will be reset.").assertIsDisplayed()
            onNodeWithText("Reset changed occurrences?").assertIsDisplayed()
        }

    @Test
    fun `reminder options differ for timed and all-day events`() =
        runComposeUiTest {
            setContent { LaresTheme(darkTheme = false) { EventReminderOptions(allDay = false, selected = 60) {} } }
            onNodeWithText("At the start").assertIsDisplayed()
            onNodeWithText("1 hour before").assertIsDisplayed()
            onNodeWithTag("reminder_10").assertExists()
        }

    @Test
    fun `a member who may not create sees why`() =
        runComposeUiTest {
            val state = newState().copy(canCreate = false)
            setContent { LaresTheme(darkTheme = false) { EventEditorScreen(state, EventEditorActions()) } }
            onNodeWithText("You can’t create events. Ask the family administrator.").assertIsDisplayed()
            onNodeWithTag("event_save").assertDoesNotExist()
            onNodeWithTag("start_time").assertDoesNotExist()
            assertTrue(LocalTime(16, 0) == state.form.startTime)
        }
}
