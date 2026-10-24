package app.lares.client.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import app.lares.client.ui.tasks.ReminderOptions
import app.lares.client.ui.tasks.TaskEditorActions
import app.lares.client.ui.tasks.TaskEditorScreen
import app.lares.client.ui.tasks.TaskEditorUiState
import app.lares.client.ui.tasks.TaskForm
import app.lares.client.ui.theme.LaresTheme
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import app.lares.contract.entities.TaskData
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class TaskEditorUiTest {
    @BeforeTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    private val today = LocalDate(2026, 10, 21)

    @Test
    fun `a routine shows Starts and the next time`() =
        runComposeUiTest {
            val trash =
                TaskData(
                    "Trash",
                    "m1",
                    dueDate = LocalDate(2026, 10, 1),
                    dueTime = LocalTime(20, 0),
                    recurrence =
                        Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.WEEK, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)),
                )
            val form = TaskForm.of(trash)
            val state =
                TaskEditorUiState(
                    loaded = true,
                    isNew = false,
                    form = form,
                    initial = form,
                    today = today,
                    nextTime = LocalDate(2026, 10, 22),
                    canEdit = true,
                )
            setContent { LaresTheme(darkTheme = false) { TaskEditorScreen(state, TaskEditorActions()) } }
            onNodeWithText("Starts").assertIsDisplayed()
            onNodeWithText("Next time: Tomorrow").assertIsDisplayed()
            onNodeWithText("Every Mon and Thu").assertIsDisplayed()
            onNodeWithTag("field_reminder").assertIsDisplayed()
            onNodeWithText("Tell me when it's done").assertExists()
            onNodeWithText("Checklist").assertDoesNotExist()
        }

    @Test
    fun `a new one-off shows Date, no time row without a date, and the checklist`() =
        runComposeUiTest {
            val form = TaskForm(checklist = listOf("Shoes"))
            val state = TaskEditorUiState(loaded = true, form = form, today = today, canCreate = true)
            setContent { LaresTheme(darkTheme = false) { TaskEditorScreen(state, TaskEditorActions()) } }
            onNodeWithText("Date").assertIsDisplayed()
            onNodeWithText("Not set").assertIsDisplayed()
            onNodeWithTag("field_time").assertDoesNotExist()
            onNodeWithTag("field_reminder").assertDoesNotExist()
            onNodeWithTag("next_time").assertDoesNotExist()
            onNodeWithText("Anyone").assertIsDisplayed()
            onNodeWithTag("checklist_item_0").assertExists()
        }

    @Test
    fun `reminder options differ with and without a time`() =
        runComposeUiTest {
            val hasTime = mutableStateOf(true)
            setContent { LaresTheme(darkTheme = false) { ReminderOptions(hasTime.value, selected = 15) {} } }
            onNodeWithText("At the due time").assertIsDisplayed()
            onNodeWithText("15 minutes before").assertIsDisplayed()
            onNodeWithText("1 hour before").assertIsDisplayed()
            onNodeWithText("On the day at 9:00").assertDoesNotExist()

            hasTime.value = false
            waitForIdle()
            onNodeWithText("On the day at 9:00").assertIsDisplayed()
            onNodeWithText("1 day before at 9:00").assertIsDisplayed()
            onNodeWithText("2 days before at 9:00").assertIsDisplayed()
            onNodeWithText("1 hour before").assertDoesNotExist()
        }
}
